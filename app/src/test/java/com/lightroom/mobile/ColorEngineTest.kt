package com.lightroom.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Behaviour of the colour pipeline, checked against how Lightroom's controls act. */
class ColorEngineTest {
    private val red = rgb(220, 40, 40); private val blue = rgb(40, 70, 210); private val green = rgb(60, 170, 70)
    private val gray = rgb(128, 128, 128); private val skin = rgb(224, 172, 140); private val dark = rgb(40, 40, 40); private val light = rgb(215, 215, 215)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s; val x = c * (1 - abs((h / 60f) % 2 - 1)); val m = v - c
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return rgb(((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }
    private fun render(state: DevelopState, vararg colors: Int): IntArray {
        val pixels = colors.copyOf()
        ColorEngine(state, pixels.size, 1, frameEffects = false).process(pixels, 0, 1)
        return pixels
    }
    private fun one(state: DevelopState, color: Int) = render(state, color)[0]
    private fun r(c: Int) = c shr 16 and 255; private fun g(c: Int) = c shr 8 and 255; private fun b(c: Int) = c and 255
    private fun lab(c: Int): FloatArray {
        val rgb = floatArrayOf(ColorEngine.srgbToLinear(r(c) / 255f), ColorEngine.srgbToLinear(g(c) / 255f), ColorEngine.srgbToLinear(b(c) / 255f))
        return FloatArray(3).also { ColorEngine.linearToOklab(rgb, it) }
    }
    private fun chroma(c: Int) = lab(c).let { hypot(it[1], it[2]) }
    private fun hue(c: Int) = lab(c).let { (Math.toDegrees(atan2(it[2], it[1]).toDouble()).toFloat() + 360f) % 360f }
    private fun lightness(c: Int) = lab(c)[0]
    private fun close(a: Int, b: Int, tolerance: Int = 2) = abs(r(a) - r(b)) <= tolerance && abs(g(a) - g(b)) <= tolerance && abs(b(a) - b(b)) <= tolerance
    private fun mix(channel: String, hue: Float = 0f, saturation: Float = 0f, luminance: Float = 0f) =
        DevelopState(colorMix = mapOf(channel to ColorMix(hue, saturation, luminance)))

    @Test
    fun defaultSettingsLeaveColoursUnchanged() {
        val samples = (0..255 step 15).flatMap { v -> listOf(rgb(v, v, v), rgb(v, 255 - v, (v * 7) % 256)) } + listOf(red, blue, green, skin)
        val out = render(DevelopState(), *samples.toIntArray())
        samples.indices.forEach { assertTrue("sample $it", close(samples[it], out[it])) }
    }

    @Test
    fun redSaturationMinus100GreysOnlyReds() {
        val (outRed, outBlue, outGray, outGreen) = render(mix("Red", saturation = -100f), red, blue, gray, green).toList()
        assertTrue(chroma(outRed) < .02f)
        assertTrue(close(outBlue, blue)); assertTrue(close(outGray, gray)); assertTrue(close(outGreen, green))
    }

    @Test
    fun redHuePlus100MovesRedTowardOrangeAndLeavesBlue() {
        val (outRed, outBlue) = render(mix("Red", hue = 100f), red, blue).toList()
        assertTrue(hue(outRed) - hue(red) > 15f)
        assertTrue(g(outRed) > g(red) + 20)
        assertTrue(close(outBlue, blue))
        val (towardMagenta) = render(mix("Red", hue = -100f), red).toList()
        assertTrue(b(towardMagenta) > b(red) + 20)
    }

    @Test
    fun luminanceMovesOnlyItsColourAndNeverGreys() {
        val (outBlue, outGray, outRed, outLight) = render(mix("Blue", luminance = -100f), blue, gray, red, light).toList()
        assertTrue(lightness(outBlue) < lightness(blue) - .08f)
        assertTrue(close(outGray, gray, 0)); assertTrue(close(outLight, light, 0)); assertTrue(close(outRed, red))
        // Hue stays put when only luminance changes.
        assertTrue(abs(hue(outBlue) - hue(blue)) < 4f)
    }

    @Test
    fun everyBandOnlyAffectsColoursNearIt() {
        val all = DevelopState(colorMix = colorMixChannels.associateWith { ColorMix(luminance = 100f) })
        assertTrue(close(one(all, gray), gray, 0))
        val orangeOnly = mix("Orange", saturation = 100f)
        assertTrue(chroma(one(orangeOnly, skin)) > chroma(skin) * 1.4f)
        assertTrue(close(one(orangeOnly, blue), blue))
    }

    @Test
    fun hueShiftsAreContinuousAcrossBandEdges() {
        // Sweep a full hue circle; a band edge must never cause a jump in the output (that is what showed as banding).
        val state = DevelopState(colorMix = mapOf("Orange" to ColorMix(hue = 100f, saturation = 60f), "Yellow" to ColorMix(hue = -60f)))
        val sweep = (0 until 360).map { h -> hsv(h.toFloat(), .7f, .85f) }
        val out = render(state, *sweep.toIntArray())
        for (i in 1 until out.size) {
            val step = abs(r(out[i]) - r(out[i - 1])) + abs(g(out[i]) - g(out[i - 1])) + abs(b(out[i]) - b(out[i - 1]))
            assertTrue("jump at hue $i: $step", step < 30)
        }
    }

    @Test
    fun exposureIsInStops() {
        val mid = rgb(100, 100, 100)
        val linearBefore = ColorEngine.srgbToLinear(100 / 255f)
        val after = one(DevelopState(exposure = 1f), mid)
        assertEquals(linearBefore * 2f, ColorEngine.srgbToLinear(r(after) / 255f), .01f)
    }

    @Test
    fun temperatureAndTintPushTheRightWay() {
        val warm = one(DevelopState(temperature = 50f), gray)
        assertTrue(r(warm) > r(gray) && b(warm) < b(gray))
        val magenta = one(DevelopState(tint = 50f), gray)
        assertTrue(g(magenta) < g(gray) && r(magenta) > g(magenta))
    }

    @Test
    fun saturationAndMonochrome() {
        assertTrue(chroma(one(DevelopState(saturation = -100f), red)) < .01f)
        assertTrue(chroma(one(DevelopState(saturation = 60f), skin)) > chroma(skin) * 1.4f)
        val mono = one(DevelopState(profile = "Monochrome", vibrance = 40f), red)
        assertTrue(r(mono) == g(mono) && g(mono) == b(mono))
        val darkerBlue = one(DevelopState(profile = "Monochrome", bwMix = mapOf("Blue" to -100f)), blue)
        assertTrue(r(darkerBlue) < r(one(DevelopState(profile = "Monochrome"), blue)) - 10)
    }

    @Test
    fun vibranceFavoursMutedColoursAndProtectsSkin() {
        val muted = rgb(120, 140, 160)
        val boost = DevelopState(vibrance = 80f)
        val mutedGain = chroma(one(boost, muted)) / chroma(muted)
        val vividGain = chroma(one(boost, blue)) / chroma(blue)
        assertTrue(mutedGain > vividGain)
        val skinGain = chroma(one(boost, skin)) / chroma(skin)
        assertTrue(skinGain < chroma(one(DevelopState(saturation = 80f), skin)) / chroma(skin))
    }

    @Test
    fun toneControlsActOnTheirRanges() {
        val contrast = render(DevelopState(contrast = 60f), dark, light)
        assertTrue(r(contrast[0]) < r(dark) && r(contrast[1]) > r(light))
        val highlights = render(DevelopState(highlights = -100f), dark, light)
        assertTrue(r(light) - r(highlights[1]) > 3 * abs(r(dark) - r(highlights[0])))
        val shadows = render(DevelopState(shadows = 100f), dark, light)
        assertTrue(r(shadows[0]) - r(dark) > 3 * abs(r(light) - r(shadows[1])))
        val whites = one(DevelopState(whites = 100f), light); assertTrue(r(whites) > r(light))
        val blacks = one(DevelopState(blacks = -100f), dark); assertTrue(r(blacks) < r(dark))
    }

    @Test
    fun colourGradingTintsItsTonalRange() {
        val state = DevelopState(shadowHue = 220f, shadowGrade = 80f)
        val (outDark, outLight) = render(state, dark, light).toList()
        assertTrue(b(outDark) > r(outDark) + 4)
        assertTrue(close(outLight, light, 3))
    }

    @Test
    fun calibrationRedHueMovesReds() {
        val out = one(DevelopState(redHue = 100f), red)
        assertTrue(g(out) > g(red))
        assertTrue(close(one(DevelopState(redHue = 100f), gray), gray))
    }

    @Test
    fun toneCurvesAreSmoothAndMonotone() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(.25f, .15f), CurvePoint(.5f, .55f), CurvePoint(.75f, .85f), CurvePoint(1f, 1f))
        var previous = -1f
        for (i in 0..200) {
            val y = evaluateCurve(points, i / 200f)
            assertTrue(y >= previous - 1e-6f); previous = y
        }
        points.forEach { assertEquals(it.y, evaluateCurve(points, it.x), 1e-4f) }
        // Smooth: no kink at a control point (left and right slopes nearly equal).
        val e = .002f
        val left = (evaluateCurve(points, .5f) - evaluateCurve(points, .5f - e)) / e
        val right = (evaluateCurve(points, .5f + e) - evaluateCurve(points, .5f)) / e
        assertEquals(left, right, .15f)
    }
}
