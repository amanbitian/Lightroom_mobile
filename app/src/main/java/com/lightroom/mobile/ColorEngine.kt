package com.lightroom.mobile

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Weight (0..1) of brush and AI masks, whose rasters live on the Android side. Indices follow [ColorEngine.localMasks]. */
fun interface MaskSampler {
    fun weight(maskIndex: Int, px: Float, py: Float): Float
}

/** Low-resolution, blurred linear luminance of the frame: the "base layer" for local tone (shadows, highlights, clarity). */
class LuminanceBase(val width: Int, val height: Int, val values: FloatArray) {
    fun sample(px: Float, py: Float): Float {
        val fx = (px * (width - 1)).coerceIn(0f, width - 1f); val fy = (py * (height - 1)).coerceIn(0f, height - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(x0 + 1, width - 1); val y1 = min(y0 + 1, height - 1)
        val tx = fx - x0; val ty = fy - y0
        val top = values[y0 * width + x0] + (values[y0 * width + x1] - values[y0 * width + x0]) * tx
        val bottom = values[y1 * width + x0] + (values[y1 * width + x1] - values[y1 * width + x0]) * tx
        return top + (bottom - top) * ty
    }
}

/**
 * The per-pixel colour pipeline shared by the editor preview and export. Pure Kotlin, so it is unit-tested on the JVM.
 *
 * Order follows Adobe Camera Raw: calibration (linear) -> white balance and exposure (linear) -> tone (local shadows /
 * highlights, then whites, blacks, contrast on luminance) -> tone curves -> colour in OKLab/OKLCh (saturation,
 * vibrance, HSL mixer or B&W mix, colour grading) -> local masks -> dehaze -> frame effects (vignette, grain).
 * OKLCh is perceptually uniform: hue shifts move only that colour, luminance changes don't shift hue, and greys
 * (which have no reliable hue) are left alone by hue and luminance adjustments.
 */
class ColorEngine(
    private val state: DevelopState,
    private val width: Int,
    private val height: Int,
    private val frameEffects: Boolean = true,
    private val sampler: MaskSampler? = null,
    private val base: LuminanceBase? = null,
) {
    /** Local masks that actually adjust something, in the order [MaskSampler] indices refer to. */
    val localMasks: List<LocalMask> = state.masks.filter { mask ->
        mask.enabled && (mask.exposure != 0f || mask.contrast != 0f || mask.temperature != 0f || mask.saturation != 0f ||
            mask.highlights != 0f || mask.shadows != 0f || mask.tint != 0f)
    }

    private val monochrome = state.profile == "Monochrome"
    private val profile = profileTweak(state.profile)
    private val calibration = calibrationMatrix(state)
    private val gainR: Float; private val gainG: Float; private val gainB: Float; private val lumaGain: Float
    private val localTone = localToneLut(state)
    private val globalTone = globalToneLut(state, profile.first)
    private val clarity = state.clarity / 100f
    private val curvesActive: Boolean
    private val lutR = FloatArray(CURVE_SIZE); private val lutG = FloatArray(CURVE_SIZE); private val lutB = FloatArray(CURVE_SIZE)
    private val saturation = (1f + (state.saturation + profile.second) / 100f).coerceAtLeast(0f)
    private val vibrance = state.vibrance / 100f
    private val mixHue = FloatArray(8); private val mixSat = FloatArray(8); private val mixLum = FloatArray(8)
    private val mixActive: Boolean
    private val bwWeights = FloatArray(8) { state.bwMix[colorMixChannels[it]] ?: 0f }
    private val gradeShadow = gradeVector(state.shadowHue, state.shadowGrade)
    private val gradeMid = gradeVector(state.midtoneHue, state.midtoneGrade)
    private val gradeHighlight = gradeVector(state.highlightHue, state.highlightGrade)
    private val gradingActive = state.shadowGrade > 0f || state.midtoneGrade > 0f || state.highlightGrade > 0f || state.shadowTint != 0f
    private val colorActive: Boolean
    private val maskCos = FloatArray(localMasks.size) { cos(Math.toRadians(localMasks[it].angle.toDouble())).toFloat() }
    private val maskSin = FloatArray(localMasks.size) { sin(Math.toRadians(localMasks[it].angle.toDouble())).toFloat() }
    private val bands = FloatArray(2); private val bandIdx = IntArray(2)
    private val shortSide = min(width, height).coerceAtLeast(1).toFloat()

    init {
        // White balance as linear channel gains normalized to keep brightness, then exposure in stops.
        val temp = state.temperature / 100f; val tint = state.tint / 100f
        var r = 1f + temp * .3f + tint * .06f; var g = 1f - tint * .2f; var b = 1f - temp * .3f + tint * .06f
        val luma = .2126f * r + .7152f * g + .0722f * b
        val exposure = 2.0.pow(state.exposure.toDouble()).toFloat()
        r = r / luma * exposure; g = g / luma * exposure; b = b / luma * exposure
        gainR = r; gainG = g; gainB = b; lumaGain = exposure
        val curveRgb = state.curves["RGB"].orEmpty(); val curveRed = state.curves["Red"].orEmpty()
        val curveGreen = state.curves["Green"].orEmpty(); val curveBlue = state.curves["Blue"].orEmpty()
        val parametric = state.curveShadows != 0f || state.curveMidtones != 0f || state.curveHighlights != 0f
        curvesActive = parametric || !curveRgb.isIdentityCurve() || !curveRed.isIdentityCurve() || !curveGreen.isIdentityCurve() || !curveBlue.isIdentityCurve()
        if (curvesActive) for (i in 0 until CURVE_SIZE) {
            var x = i / (CURVE_SIZE - 1f)
            if (parametric) {
                val shadowMask = (1f - x) * (1f - x); val highlightMask = x * x; val midMask = (1f - abs(x - .5f) * 2f).coerceIn(0f, 1f)
                x = (x + state.curveShadows / 100f * shadowMask * .32f + state.curveMidtones / 100f * midMask * .28f +
                    state.curveHighlights / 100f * highlightMask * .32f).coerceIn(0f, 1f)
            }
            val master = evaluateCurve(curveRgb, x)
            lutR[i] = evaluateCurve(curveRed, master); lutG[i] = evaluateCurve(curveGreen, master); lutB[i] = evaluateCurve(curveBlue, master)
        }
        colorMixChannels.forEachIndexed { i, ch -> val m = state.colorMix[ch] ?: ColorMix(); mixHue[i] = m.hue; mixSat[i] = m.saturation; mixLum[i] = m.luminance }
        mixActive = !monochrome && (mixHue.any { it != 0f } || mixSat.any { it != 0f } || mixLum.any { it != 0f })
        colorActive = monochrome || mixActive || saturation != 1f || vibrance != 0f || gradingActive
    }

    /** Processes [rows] rows of ARGB [pixels] (row-major, this frame's width) starting at frame row [top], in place. */
    fun process(pixels: IntArray, top: Int, rows: Int, out16: ShortBuffer? = null) {
        val lab = FloatArray(3); val rgb = FloatArray(3)
        val vignetteAmount = if (frameEffects) state.vignette / 100f else 0f
        val lensShading = if (frameEffects) state.opticsVignette / 100f else 0f
        val vignetteStart = .2f + state.vignetteMidpoint / 100f * .75f
        val vignetteWidth = .05f + state.vignetteFeather / 100f * .7f
        val roundness = (state.vignetteRoundness / 100f + 1f) / 2f
        val aspect = width.toFloat() / max(1, height)
        // Grain is position-independent noise, so it shows in the preview too (vignette needs the final frame).
        val grainAmount = state.grain / 100f * .14f
        val grainCell = ((1f + state.grainSize / 22f) * max(width, height) / 2400f).coerceAtLeast(.6f)
        val grainRough = state.grainRoughness / 100f * .6f
        val dehaze = state.dehaze / 100f
        for (localY in 0 until rows) {
            val y = top + localY
            val py = y.toFloat() / max(1, height - 1)
            for (x in 0 until width) {
                val index = localY * width + x
                val color = pixels[index]
                val px = x.toFloat() / max(1, width - 1)
                // 1. Linear light: calibration primaries, white balance, exposure.
                var r = SRGB_TO_LINEAR[(color shr 16) and 255]; var g = SRGB_TO_LINEAR[(color shr 8) and 255]; var b = SRGB_TO_LINEAR[color and 255]
                if (calibration != null) {
                    val cr = calibration[0] * r + calibration[1] * g + calibration[2] * b
                    val cg = calibration[3] * r + calibration[4] * g + calibration[5] * b
                    val cb = calibration[6] * r + calibration[7] * g + calibration[8] * b
                    r = cr; g = cg; b = cb
                }
                r = encode(r * gainR); g = encode(g * gainG); b = encode(b * gainB)
                // 2. Tone on luminance, applied as a ratio so hue is preserved. Shadows/highlights read the blurred base
                //    layer (like Lightroom's local tone mapping) so local contrast survives; clarity adds back detail.
                val luma = .2126f * r + .7152f * g + .0722f * b
                val baseLuma = base?.let { encode(it.sample(px, py) * lumaGain) } ?: luma
                val local = lookup(localTone, baseLuma) - baseLuma
                val pixelLocal = lookup(localTone, luma) - luma
                var toned = luma + local * .6f + pixelLocal * .4f
                if (clarity != 0f && base != null) {
                    val mid = 1f - abs(luma.coerceIn(0f, 1f) - .5f) * 2f
                    toned += clarity * .6f * (luma - baseLuma) * (.35f + .65f * mid)
                }
                toned = lookup(globalTone, toned)
                val ratio = (toned + .015f) / (luma + .015f)
                r *= ratio; g *= ratio; b *= ratio
                // 3. Tone curves per channel.
                if (curvesActive) {
                    r = lutR[(r.coerceIn(0f, 1f) * (CURVE_SIZE - 1)).toInt()]
                    g = lutG[(g.coerceIn(0f, 1f) * (CURVE_SIZE - 1)).toInt()]
                    b = lutB[(b.coerceIn(0f, 1f) * (CURVE_SIZE - 1)).toInt()]
                }
                // 4. Colour in OKLab: presence, HSL / B&W mixer, colour grading.
                if (colorActive) {
                    rgb[0] = decode(r); rgb[1] = decode(g); rgb[2] = decode(b)
                    linearToOklab(rgb, lab)
                    adjustColor(lab)
                    oklabToLinear(lab, rgb)
                    gamutClip(rgb, lab)
                    r = encode(rgb[0]); g = encode(rgb[1]); b = encode(rgb[2])
                }
                if (state.defringe > 0f) {
                    val amount = state.defringe / 100f
                    val magentaExcess = ((r + b) * .5f - g - .08f).coerceAtLeast(0f)
                    val greenExcess = (g - (r + b) * .5f - .08f).coerceAtLeast(0f)
                    g += magentaExcess * amount * .9f; r += greenExcess * amount * .45f; b += greenExcess * amount * .45f
                }
                // 5. Local masks.
                for (m in localMasks.indices) {
                    val mask = localMasks[m]
                    // Geometric masks measure in real pixels, in units of the frame's short side, so a radial mask
                    // is round and a gradient's angle is true whatever the aspect (and survives crop/rotate mapping).
                    var weight = when (mask.type) {
                        "Linear" -> {
                            val projected = ((px - mask.centerX) * width * maskCos[m] + (py - mask.centerY) * height * maskSin[m]) / shortSide
                            (projected / (mask.size * mask.feather).coerceAtLeast(.02f) + .5f).coerceIn(0f, 1f)
                        }
                        "Radial" -> {
                            val dx = (px - mask.centerX) * width / shortSide; val dy = (py - mask.centerY) * height / shortSide
                            // Rotate into the ellipse's own frame, then stretch the cross axis so the test is round again.
                            val rx = dx * maskCos[m] + dy * maskSin[m]
                            val ry = (-dx * maskSin[m] + dy * maskCos[m]) / mask.aspect.coerceIn(.2f, 5f)
                            val distance = sqrt(rx * rx + ry * ry)
                            val outer = mask.size.coerceAtLeast(.05f); val inner = outer * (1f - mask.feather.coerceIn(.02f, 1f))
                            when { distance <= inner -> 1f; distance >= outer -> 0f; else -> 1f - (distance - inner) / (outer - inner).coerceAtLeast(.001f) }
                        }
                        else -> sampler?.weight(m, px, py) ?: 0f
                    }
                    if (mask.invert) weight = 1f - weight
                    if (weight <= .001f) continue
                    val exposure = 2.0.pow((mask.exposure * weight).toDouble()).toFloat()
                    val contrast = 1f + mask.contrast / 100f * weight
                    r = (r * exposure - .5f) * contrast + .5f; g = (g * exposure - .5f) * contrast + .5f; b = (b * exposure - .5f) * contrast + .5f
                    r += (mask.temperature * .12f + mask.tint * .04f) / 100f * weight
                    g -= mask.tint / 100f * .08f * weight
                    b -= (mask.temperature * .12f - mask.tint * .04f) / 100f * weight
                    var localLuma = .2126f * r + .7152f * g + .0722f * b
                    if (mask.highlights != 0f || mask.shadows != 0f) {
                        val lift = (mask.highlights / 100f * .3f * smoothstep(.4f, 1f, localLuma) +
                            mask.shadows / 100f * .3f * (1f - smoothstep(0f, .6f, localLuma))) * weight
                        r += lift; g += lift; b += lift; localLuma += lift
                    }
                    val sat = 1f + mask.saturation / 100f * weight
                    r = localLuma + (r - localLuma) * sat; g = localLuma + (g - localLuma) * sat; b = localLuma + (b - localLuma) * sat
                }
                // 6. Dehaze: contrast and colour depth, lifting the haze floor.
                if (dehaze != 0f) {
                    val d = dehaze * .25f
                    val l = .2126f * r + .7152f * g + .0722f * b
                    r = ((r - l) * (1f + d * .6f) + l - .5f) * (1f + d) + .5f - d * .04f
                    g = ((g - l) * (1f + d * .6f) + l - .5f) * (1f + d) + .5f - d * .04f
                    b = ((b - l) * (1f + d * .6f) + l - .5f) * (1f + d) + .5f - d * .04f
                }
                // 7. Frame effects: post-crop vignette, lens shading, grain.
                if (vignetteAmount != 0f || lensShading != 0f) {
                    val nx = px * 2f - 1f; val ny = py * 2f - 1f
                    val elliptical = sqrt(nx * nx + ny * ny) / 1.4142f
                    val circular = sqrt(nx * aspect * nx * aspect + ny * ny) / sqrt(aspect * aspect + 1f)
                    val d = elliptical + (circular - elliptical) * roundness
                    if (vignetteAmount != 0f) {
                        val w = smoothstep(vignetteStart - vignetteWidth / 2f, vignetteStart + vignetteWidth / 2f, d * 1.25f)
                        if (vignetteAmount < 0f) { val f = 1f + vignetteAmount * w; r *= f; g *= f; b *= f }
                        else { val f = vignetteAmount * w; r += (1f - r) * f; g += (1f - g) * f; b += (1f - b) * f }
                    }
                    if (lensShading != 0f) { val f = 1f + lensShading * .5f * d * d; r *= f; g *= f; b *= f }
                }
                if (grainAmount > 0f) {
                    val gx = x / grainCell; val gy = y / grainCell
                    val ix = gx.toInt(); val iy = gy.toInt(); val fx = gx - ix; val fy = gy - iy
                    val t0 = hash(ix, iy) + (hash(ix + 1, iy) - hash(ix, iy)) * fx
                    val b0 = hash(ix, iy + 1) + (hash(ix + 1, iy + 1) - hash(ix, iy + 1)) * fx
                    val smooth = t0 + (b0 - t0) * fy
                    val n = (smooth + (hash(x * 7 + 3, y * 13 + 5) - smooth) * grainRough) * grainAmount * 2f
                    r += n; g += n; b += n
                }
                val rc = r.coerceIn(0f, 1f); val gc = g.coerceIn(0f, 1f); val bc = b.coerceIn(0f, 1f)
                if (out16 != null) {
                    val gi = (y * width + x) * 3
                    out16.put(gi, (rc * 65535f).toInt().toShort()); out16.put(gi + 1, (gc * 65535f).toInt().toShort()); out16.put(gi + 2, (bc * 65535f).toInt().toShort())
                }
                pixels[index] = (color and -0x1000000) or ((rc * 255f + .5f).toInt() shl 16) or ((gc * 255f + .5f).toInt() shl 8) or (bc * 255f + .5f).toInt()
            }
        }
    }

    /** Saturation, vibrance, HSL or B&W mixer and colour grading on one OKLab colour, in place. */
    private fun adjustColor(lab: FloatArray) {
        var l = lab[0]; var a = lab[1]; var b = lab[2]
        var chroma = sqrt(a * a + b * b)
        var hue = (Math.toDegrees(atan2(b, a).toDouble()).toFloat() + 360f) % 360f
        // Hue and luminance moves fade out on near-greys, whose hue is noise (so a slider never shifts the whole frame).
        val colourfulness = smoothstep(.012f, .06f, chroma)
        if (!monochrome) {
            // Vibrance favours muted colours and protects skin (Lightroom's behaviour); saturation is uniform.
            var factor = saturation
            if (vibrance != 0f) {
                val muted = 1f - smoothstep(0f, .22f, chroma)
                val skin = if (vibrance > 0f) bell(hue, 55f, 22f) * .6f else 0f
                factor *= 1f + vibrance * muted * (1f - skin)
            }
            chroma *= factor.coerceAtLeast(0f)
            if (mixActive && colourfulness > 0f) {
                hslBand(hue)
                val i = bandIdx[0]; val j = bandIdx[1]; val wi = bands[0]; val wj = bands[1]
                // Hue: +100 moves a colour most of the way to the next band (Red -> Orange), -100 to the previous one.
                fun shift(k: Int) = if (mixHue[k] >= 0f) mixHue[k] / 100f * BAND_SPAN_UP[k] else mixHue[k] / 100f * BAND_SPAN_DOWN[k]
                hue = (hue + (shift(i) * wi + shift(j) * wj) * .85f * colourfulness + 360f) % 360f
                chroma *= (1f + (mixSat[i] * wi + mixSat[j] * wj) / 100f).coerceAtLeast(0f)
                l += (mixLum[i] * wi + mixLum[j] * wj) / 100f * .2f * colourfulness * smoothstep(.02f, .14f, chroma)
            }
        } else {
            // B&W: perceptual lightness, with the B&W mixer brightening or darkening each original colour.
            if (bwWeights.any { it != 0f } && colourfulness > 0f) {
                hslBand(hue)
                l += (bwWeights[bandIdx[0]] * bands[0] + bwWeights[bandIdx[1]] * bands[1]) / 100f * .3f * colourfulness * smoothstep(.02f, .16f, chroma)
            }
            chroma = 0f
        }
        val h = Math.toRadians(hue.toDouble())
        a = (chroma * cos(h)).toFloat(); b = (chroma * sin(h)).toFloat()
        if (gradingActive) {
            // Colour grading tints shadows / midtones / highlights along each wheel's hue; balance moves the split.
            val pivot = (.5f + state.gradingBalance / 100f * .25f).coerceIn(.2f, .8f)
            val shadowW = 1f - smoothstep(.05f, pivot, l)
            val highlightW = smoothstep(pivot, .95f, l)
            val midW = (1f - abs(l - pivot) / .45f).coerceIn(0f, 1f)
            a += gradeShadow[0] * shadowW + gradeMid[0] * midW + gradeHighlight[0] * highlightW
            b += gradeShadow[1] * shadowW + gradeMid[1] * midW + gradeHighlight[1] * highlightW
            // Calibration shadow tint: negative greener, positive more magenta.
            a += state.shadowTint / 100f * .035f * shadowW
        }
        lab[0] = l.coerceIn(0f, 1.2f); lab[1] = a; lab[2] = b
    }

    /** Neighbouring band indices for an OKLCh hue, weights blended smoothly so gradients never band. */
    private fun hslBand(hue: Float) {
        var i = BAND_CENTERS.size - 1
        for (k in 0 until BAND_CENTERS.size - 1) if (hue >= BAND_CENTERS[k] && hue < BAND_CENTERS[k + 1]) { i = k; break }
        if (hue < BAND_CENTERS[0]) i = BAND_CENTERS.size - 1
        val j = (i + 1) % BAND_CENTERS.size
        val start = BAND_CENTERS[i]; var end = BAND_CENTERS[j]; var h = hue
        if (end <= start) { end += 360f; if (h < start) h += 360f }
        val t = smoothstep(0f, 1f, (h - start) / (end - start))
        bandIdx[0] = i; bandIdx[1] = j; bands[0] = 1f - t; bands[1] = t
    }

    /** Shrinks chroma toward the same lightness until the colour fits in sRGB, instead of clipping channels apart. */
    private fun gamutClip(rgb: FloatArray, lab: FloatArray) {
        if (rgb.all { it in -.0005f..1.0005f }) return
        val a0 = lab[1]; val b0 = lab[2]
        var lo = 0f; var hi = 1f
        repeat(6) {
            val mid = (lo + hi) / 2f
            lab[1] = a0 * mid; lab[2] = b0 * mid
            oklabToLinear(lab, rgb)
            if (rgb.all { it in -.0005f..1.0005f }) lo = mid else hi = mid
        }
        lab[1] = a0 * lo; lab[2] = b0 * lo
        oklabToLinear(lab, rgb)
        for (k in 0..2) rgb[k] = rgb[k].coerceIn(0f, 1f)
    }

    companion object {
        private const val CURVE_SIZE = 1024
        /** Lightroom's HSL bands placed at the OKLCh hues of their sRGB colours (Red ff0000, Orange ff8000, ...). */
        private val BAND_CENTERS = floatArrayOf(29.2f, 53f, 109.8f, 142.5f, 194.8f, 264.1f, 293.9f, 328.4f)
        private val BAND_SPAN_UP = FloatArray(8) { k -> ((BAND_CENTERS[(k + 1) % 8] - BAND_CENTERS[k] + 360f) % 360f) }
        private val BAND_SPAN_DOWN = FloatArray(8) { k -> ((BAND_CENTERS[k] - BAND_CENTERS[(k + 7) % 8] + 360f) % 360f) }

        private val SRGB_TO_LINEAR = FloatArray(256) { i -> srgbToLinear(i / 255f) }
        private val LINEAR_TO_SRGB = FloatArray(4097) { i -> linearToSrgb(i / 4096f) }
        private val SRGB_TO_LINEAR_FINE = FloatArray(4097) { i -> srgbToLinear(i / 4096f) }

        fun srgbToLinear(c: Float): Float = if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).toDouble().pow(2.4).toFloat()
        fun linearToSrgb(c: Float): Float = if (c <= .0031308f) c * 12.92f else 1.055f * c.toDouble().pow(1 / 2.4).toFloat() - .055f

        /** Linear -> sRGB-encoded, keeping headroom above white for Highlights/Whites to pull back (clipped at the end). */
        fun encode(x: Float): Float = when {
            x <= 0f -> 0f
            x < 1f -> { val f = x * 4096f; val i = f.toInt(); LINEAR_TO_SRGB[i] + (LINEAR_TO_SRGB[i + 1] - LINEAR_TO_SRGB[i]) * (f - i) }
            else -> 1f + (x - 1f) * .45f
        }
        fun decode(g: Float): Float = when {
            g <= 0f -> 0f
            g < 1f -> { val f = g * 4096f; val i = f.toInt(); SRGB_TO_LINEAR_FINE[i] + (SRGB_TO_LINEAR_FINE[i + 1] - SRGB_TO_LINEAR_FINE[i]) * (f - i) }
            else -> 1f + (g - 1f) / .45f
        }

        fun linearToOklab(rgb: FloatArray, lab: FloatArray) {
            val l = cbrt(.4122214708f * rgb[0] + .5363325363f * rgb[1] + .0514459929f * rgb[2])
            val m = cbrt(.2119034982f * rgb[0] + .6806995451f * rgb[1] + .1073969566f * rgb[2])
            val s = cbrt(.0883024619f * rgb[0] + .2817188376f * rgb[1] + .6299787005f * rgb[2])
            lab[0] = .2104542553f * l + .7936177850f * m - .0040720468f * s
            lab[1] = 1.9779984951f * l - 2.4285922050f * m + .4505937099f * s
            lab[2] = .0259040371f * l + .7827717662f * m - .8086757660f * s
        }
        fun oklabToLinear(lab: FloatArray, rgb: FloatArray) {
            val l = lab[0] + .3963377774f * lab[1] + .2158037573f * lab[2]
            val m = lab[0] - .1055613458f * lab[1] - .0638541728f * lab[2]
            val s = lab[0] - .0894841775f * lab[1] - 1.2914855480f * lab[2]
            val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
            rgb[0] = 4.0767416621f * l3 - 3.3077115913f * m3 + .2309699292f * s3
            rgb[1] = -1.2684380046f * l3 + 2.6097574011f * m3 - .3413193965f * s3
            rgb[2] = -.0041960863f * l3 - .7034186147f * m3 + 1.7076147010f * s3
        }
        private fun cbrt(x: Float): Float = Math.cbrt(x.toDouble()).toFloat()

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        /** 1 at [center] degrees, falling to 0 [width] degrees away (wrapping round the hue circle). */
        private fun bell(hue: Float, center: Float, width: Float): Float {
            val d = abs(((hue - center + 540f) % 360f) - 180f)
            return 1f - smoothstep(0f, width, d)
        }
        private fun lookup(lut: FloatArray, value: Float): Float {
            val f = value.coerceIn(0f, 1.5f) * 1024f; val i = min(f.toInt(), lut.size - 2)
            return lut[i] + (lut[i + 1] - lut[i]) * (f - i)
        }
        private fun hash(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFF) / 65535f - .5f
        }

        /** Profile flavours on top of Adobe Color: (contrast, saturation) offsets. */
        private fun profileTweak(profile: String): Pair<Float, Float> = when (profile) {
            "Adobe Vivid" -> 12f to 16f
            "Adobe Portrait" -> -6f to -6f
            "Adobe Neutral" -> -18f to -10f
            else -> 0f to 0f
        }

        /** Shadows / highlights curve, read from the base layer (local) and the pixel; sRGB-encoded 0..1.5. */
        private fun localToneLut(state: DevelopState) = FloatArray(1538) { i ->
            var l = i / 1024f
            l += state.highlights / 100f * .3f * smoothstep(.4f, 1.05f, l)
            l += state.shadows / 100f * .3f * (1f - smoothstep(0f, .6f, l)) * smoothstep(-.05f, .12f, l)
            l
        }

        /**
         * Whites, blacks and an S-curve contrast around mid-grey. When Exposure or Whites push past white, a soft
         * shoulder rolls highlights off instead of clipping; at default settings the curve is exactly the identity.
         */
        private fun globalToneLut(state: DevelopState, contrastOffset: Float): FloatArray {
            val c = ((state.contrast + contrastOffset) / 100f).coerceIn(-1f, 1f)
            val knee = (state.exposure.coerceAtLeast(0f) * .06f + state.whites.coerceAtLeast(0f) / 100f * .05f).coerceIn(0f, .1f)
            return FloatArray(1538) { i ->
                var l = i / 1024f
                l += state.whites / 100f * .2f * smoothstep(.5f, 1f, l)
                l += state.blacks / 100f * .16f * (1f - smoothstep(0f, .45f, l))
                val x = l.coerceIn(0f, 1f)
                val s = if (x < .5f) 2f * x * x else 1f - 2f * (1f - x) * (1f - x)
                l += c * .65f * (s - x)
                if (knee > 0f && l > 1f - knee) l = 1f - knee + knee * kotlin.math.tanh((l - 1f + knee) / knee)
                l.coerceIn(0f, 1f)
            }
        }

        /** Calibration (primary hue/saturation) as a white-preserving 3x3 matrix in linear RGB, or null when neutral. */
        fun calibrationMatrix(state: DevelopState): FloatArray? {
            if (state.redHue == 0f && state.redSaturation == 0f && state.greenHue == 0f && state.greenSaturation == 0f &&
                state.blueHue == 0f && state.blueSaturation == 0f) return null
            fun primary(axis: Int, hue: Float, saturation: Float): FloatArray {
                // Rodrigues rotation about the grey axis (positive: R->Y, G->Aqua, B->Purple), then scale its chroma.
                val theta = Math.toRadians(hue * .3).toFloat()
                val cosT = cos(theta); val sinT = sin(theta); val k = 1f / sqrt(3f)
                val v = FloatArray(3).also { it[axis] = 1f }
                val dot = (v[0] + v[1] + v[2]) * k
                val cross = floatArrayOf(k * (v[2] - v[1]), k * (v[0] - v[2]), k * (v[1] - v[0]))
                val rotated = FloatArray(3) { j -> v[j] * cosT + cross[j] * sinT + k * dot * (1f - cosT) }
                val gray = (rotated[0] + rotated[1] + rotated[2]) / 3f
                return FloatArray(3) { j -> gray + (rotated[j] - gray) * (1f + saturation / 100f) }
            }
            val columns = arrayOf(primary(0, state.redHue, state.redSaturation), primary(1, state.greenHue, state.greenSaturation), primary(2, state.blueHue, state.blueSaturation))
            val m = FloatArray(9) { idx -> columns[idx % 3][idx / 3] }
            for (row in 0 until 3) {
                val sum = m[row * 3] + m[row * 3 + 1] + m[row * 3 + 2]
                if (abs(sum) > 1e-4f) for (col in 0 until 3) m[row * 3 + col] /= sum
            }
            return m
        }

        /** OKLab (a, b) push for a colour-grading wheel: [hue] in Lightroom's wheel degrees (0 red, 60 yellow, 240 blue). */
        private fun gradeVector(hue: Float, saturation: Float): FloatArray {
            if (saturation <= 0f) return floatArrayOf(0f, 0f)
            val h = ((hue % 360f) + 360f) % 360f / 60f
            val x = 1f - abs(h % 2f - 1f)
            val rgb = when (h.toInt()) {
                0 -> floatArrayOf(1f, x, 0f); 1 -> floatArrayOf(x, 1f, 0f); 2 -> floatArrayOf(0f, 1f, x)
                3 -> floatArrayOf(0f, x, 1f); 4 -> floatArrayOf(x, 0f, 1f); else -> floatArrayOf(1f, 0f, x)
            }
            for (k in 0..2) rgb[k] = srgbToLinear(rgb[k])
            val lab = FloatArray(3); linearToOklab(rgb, lab)
            val length = sqrt(lab[1] * lab[1] + lab[2] * lab[2]).coerceAtLeast(1e-4f)
            val strength = saturation / 100f * .09f
            return floatArrayOf(lab[1] / length * strength, lab[2] / length * strength)
        }
    }
}
