package com.lightroom.mobile

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object ImageExporter {
    private data class BrushMaskMap(val width: Int, val height: Int, val alpha: IntArray)

    suspend fun export(context: Context, photo: PhotoItem, state: DevelopState, options: ExportOptions = ExportOptions()): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val isPng = options.format.equals("PNG", ignoreCase = true)
            val compressFormat = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = options.quality.coerceIn(50, 100)
            val source = decodeScaled(context, Uri.parse(photo.uri), options.maxSide.coerceIn(512, 6000)) ?: return@withContext null
            val transformed = transform(source, state)
            val healed = applyHealing(transformed, state)
            val adjusted = adjustPixels(healed, state)
            val extension = if (isPng) "png" else "jpg"
            val mime = if (isPng) "image/png" else "image/jpeg"
            val fileName = "${photo.name.substringBeforeLast('.').ifBlank { "edit" }}_${System.currentTimeMillis()}.$extension"
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, mime)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LumaRoom")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching null
                context.contentResolver.openOutputStream(uri)?.use { adjusted.compress(compressFormat, quality, it) }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } else {
                val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "LumaRoom").apply { mkdirs() }
                val file = File(directory, fileName)
                FileOutputStream(file).use { adjusted.compress(compressFormat, quality, it) }
                Uri.fromFile(file)
            }
            if (source !== transformed) source.recycle()
            if (transformed !== healed) transformed.recycle()
            if (healed !== adjusted) healed.recycle()
            result
        }.getOrNull()
    }

    private fun decodeScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun transform(source: Bitmap, state: DevelopState): Bitmap {
        var bitmap = source
        val ratio = when (state.cropAspect) {
            "1:1" -> 1f
            "4:5" -> .8f
            "3:2" -> 1.5f
            "16:9" -> 16f / 9f
            else -> 0f
        }
        var baseWidth = bitmap.width
        var baseHeight = bitmap.height
        if (ratio > 0f) {
            val current = bitmap.width.toFloat() / bitmap.height
            if (current > ratio) { baseHeight = bitmap.height; baseWidth = (baseHeight * ratio).toInt() }
            else { baseWidth = bitmap.width; baseHeight = (baseWidth / ratio).toInt() }
        }
        val zoom = state.cropZoom.coerceIn(1f, 3f)
        val cropWidth = (baseWidth / zoom).toInt().coerceIn(1, bitmap.width)
        val cropHeight = (baseHeight / zoom).toInt().coerceIn(1, bitmap.height)
        val availableX = bitmap.width - cropWidth
        val availableY = bitmap.height - cropHeight
        val cropX = (availableX / 2f + state.cropOffsetX.coerceIn(-1f, 1f) * availableX / 2f).toInt().coerceIn(0, availableX)
        val cropY = (availableY / 2f + state.cropOffsetY.coerceIn(-1f, 1f) * availableY / 2f).toInt().coerceIn(0, availableY)
        if (cropWidth != bitmap.width || cropHeight != bitmap.height || cropX != 0 || cropY != 0) {
            bitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight)
        }
        if (state.geometryVertical != 0f || state.geometryHorizontal != 0f || state.geometryAspect != 0f) {
            val projected = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(projected)
            canvas.drawColor(Color.BLACK)
            val width = bitmap.width.toFloat(); val height = bitmap.height.toFloat()
            val vertical = state.geometryVertical / 100f * width * .22f
            val horizontal = state.geometryHorizontal / 100f * height * .22f
            val sourcePoints = floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height)
            val destinationPoints = floatArrayOf(
                if (vertical > 0f) vertical else 0f, if (horizontal > 0f) horizontal else 0f,
                if (vertical > 0f) width - vertical else width, if (horizontal < 0f) -horizontal else 0f,
                if (vertical < 0f) width + vertical else width, if (horizontal < 0f) height + horizontal else height,
                if (vertical < 0f) -vertical else 0f, if (horizontal > 0f) height - horizontal else height,
            )
            val projective = Matrix().apply {
                setPolyToPoly(sourcePoints, 0, destinationPoints, 0, 4)
                val aspectScale = (1f + state.geometryAspect / 200f).coerceIn(.5f, 1.5f)
                postScale(aspectScale, 1f, width / 2f, height / 2f)
            }
            canvas.drawBitmap(bitmap, projective, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            if (bitmap !== source) bitmap.recycle()
            bitmap = projected
        }
        val totalRotation = state.rotation + state.straighten
        if (totalRotation != 0f || state.flipHorizontal) {
            val matrix = Matrix().apply {
                postScale(if (state.flipHorizontal) -1f else 1f, 1f)
                postRotate(totalRotation)
            }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
        return bitmap
    }

    private fun adjustPixels(source: Bitmap, state: DevelopState): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = bitmap.width
        val height = bitmap.height
        val exposure = 2.0.pow(state.exposure.toDouble()).toFloat()
        val contrast = 1f + state.contrast / 100f
        val saturation = (1f + state.saturation / 100f + state.vibrance / 180f).coerceIn(0f, 2.5f)
        val grain = state.grain / 100f * 18f
        val random = Random(31)
        val shadowColor = hueColor(state.shadowHue)
        val midtoneColor = hueColor(state.midtoneHue)
        val highlightColor = hueColor(state.highlightHue)
        val balance = state.gradingBalance / 100f
        val mixHue = FloatArray(8)
        val mixSaturation = FloatArray(8)
        val mixLuminance = FloatArray(8)
        colorMixChannels.forEachIndexed { index, channel ->
            val adjustment = state.colorMix[channel] ?: ColorMix()
            mixHue[index] = adjustment.hue
            mixSaturation[index] = adjustment.saturation
            mixLuminance[index] = adjustment.luminance
        }
        val mixEnabled = mixHue.any { it != 0f } || mixSaturation.any { it != 0f } || mixLuminance.any { it != 0f }
        // Point tone curve lookup tables (master RGB composed with per-channel red/green/blue).
        val curveRgb = state.curves["RGB"] ?: emptyList()
        val curveRed = state.curves["Red"] ?: emptyList()
        val curveGreen = state.curves["Green"] ?: emptyList()
        val curveBlue = state.curves["Blue"] ?: emptyList()
        val curvesActive = !curveRgb.isIdentityCurve() || !curveRed.isIdentityCurve() ||
            !curveGreen.isIdentityCurve() || !curveBlue.isIdentityCurve()
        val lutR = FloatArray(256); val lutG = FloatArray(256); val lutB = FloatArray(256)
        if (curvesActive) {
            for (i in 0..255) {
                val master = evaluateCurve(curveRgb, i / 255f)
                lutR[i] = evaluateCurve(curveRed, master)
                lutG[i] = evaluateCurve(curveGreen, master)
                lutB[i] = evaluateCurve(curveBlue, master)
            }
        }
        // Black & White channel mix only applies when the Monochrome profile is selected.
        val bwActive = state.profile == "Monochrome" && state.bwMix.values.any { it != 0f }
        val bwWeights = FloatArray(8) { index -> state.bwMix[colorMixChannels[index]] ?: 0f }
        val localMasks = state.masks.filter { mask ->
            mask.enabled && (mask.exposure != 0f || mask.contrast != 0f || mask.temperature != 0f || mask.saturation != 0f)
        }
        val maskCosines = FloatArray(localMasks.size) { index ->
            cos(Math.toRadians(localMasks[index].angle.toDouble())).toFloat()
        }
        val maskSines = FloatArray(localMasks.size) { index ->
            sin(Math.toRadians(localMasks[index].angle.toDouble())).toFloat()
        }
        val brushMaps = arrayOfNulls<BrushMaskMap>(localMasks.size)
        localMasks.forEachIndexed { index, mask ->
            if (mask.type == "Brush" && mask.points.isNotEmpty()) brushMaps[index] = renderBrushMask(mask, width, height)
        }
        val tileHeight = 64
        val pixels = IntArray(width * min(tileHeight, height))
        for (top in 0 until height step tileHeight) {
            val rows = min(tileHeight, height - top)
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
            for (localY in 0 until rows) {
                val y = top + localY
                val py = y.toFloat() / max(1, height - 1)
                val ny = py * 2f - 1f
                for (x in 0 until width) {
                    val index = localY * width + x
                val color = pixels[index]
                var r = ((color shr 16) and 255) / 255f
                var g = ((color shr 8) and 255) / 255f
                var b = (color and 255) / 255f
                var luma = .2126f * r + .7152f * g + .0722f * b
                val shadowMask = (1f - luma).pow(2)
                val highlightMask = luma.pow(2)
                val tone = state.shadows / 100f * shadowMask * .45f + state.highlights / 100f * highlightMask * .35f
                val whiteBlack = state.whites / 100f * highlightMask * .25f + state.blacks / 100f * shadowMask * .25f
                val midMask = (1f - abs(luma - .5f) * 2f).coerceIn(0f, 1f)
                val curveTone = state.curveShadows / 100f * shadowMask * .32f +
                    state.curveMidtones / 100f * midMask * .28f +
                    state.curveHighlights / 100f * highlightMask * .32f
                r = (r * exposure + tone + whiteBlack + curveTone - .5f) * contrast + .5f
                g = (g * exposure + tone + whiteBlack + curveTone - .5f) * contrast + .5f
                b = (b * exposure + tone + whiteBlack + curveTone - .5f) * contrast + .5f
                r += state.temperature / 100f * .12f + state.tint / 100f * .04f
                g -= state.tint / 100f * .08f
                b -= state.temperature / 100f * .12f - state.tint / 100f * .04f
                var grayDelta = 0f
                if (bwActive) {
                    val maxC = maxOf(r, g, b); val minC = minOf(r, g, b); val d = maxC - minC
                    if (d > .0001f && maxC > .0001f) {
                        var h = when (maxC) {
                            r -> 60f * (((g - b) / d) % 6f)
                            g -> 60f * ((b - r) / d + 2f)
                            else -> 60f * ((r - g) / d + 4f)
                        }
                        if (h < 0f) h += 360f
                        val ch = when {
                            h < 15f || h >= 340f -> 0; h < 45f -> 1; h < 90f -> 2; h < 150f -> 3
                            h < 210f -> 4; h < 257f -> 5; h < 300f -> 6; else -> 7
                        }
                        grayDelta = bwWeights[ch] / 100f * .35f * (d / maxC)
                    }
                }
                luma = .2126f * r + .7152f * g + .0722f * b
                r = luma + (r - luma) * saturation + grayDelta
                g = luma + (g - luma) * saturation + grayDelta
                b = luma + (b - luma) * saturation + grayDelta
                val shadowWeight = (1f - luma).coerceIn(0f, 1f).pow((2f - balance * .7f).coerceAtLeast(.8f)) * state.shadowGrade / 100f * .34f
                val highlightWeight = luma.coerceIn(0f, 1f).pow((2f + balance * .7f).coerceAtLeast(.8f)) * state.highlightGrade / 100f * .34f
                val middleWeight = (1f - abs(luma - .5f) * 2f).coerceIn(0f, 1f) * state.midtoneGrade / 100f * .28f
                r += (shadowColor[0] - .5f) * shadowWeight + (midtoneColor[0] - .5f) * middleWeight + (highlightColor[0] - .5f) * highlightWeight
                g += (shadowColor[1] - .5f) * shadowWeight + (midtoneColor[1] - .5f) * middleWeight + (highlightColor[1] - .5f) * highlightWeight
                b += (shadowColor[2] - .5f) * shadowWeight + (midtoneColor[2] - .5f) * middleWeight + (highlightColor[2] - .5f) * highlightWeight
                if (mixEnabled) {
                    val maximum = maxOf(r, g, b)
                    val minimum = minOf(r, g, b)
                    val delta = maximum - minimum
                    if (delta > .0001f && maximum > .0001f) {
                        var hue = when (maximum) {
                            r -> 60f * (((g - b) / delta) % 6f)
                            g -> 60f * ((b - r) / delta + 2f)
                            else -> 60f * ((r - g) / delta + 4f)
                        }
                        if (hue < 0f) hue += 360f
                        val channel = when {
                            hue < 15f || hue >= 340f -> 0
                            hue < 45f -> 1
                            hue < 90f -> 2
                            hue < 150f -> 3
                            hue < 210f -> 4
                            hue < 257f -> 5
                            hue < 300f -> 6
                            else -> 7
                        }
                        hue = (hue + mixHue[channel] * .45f + 360f) % 360f
                        val mixedSaturation = (delta / maximum * (1f + mixSaturation[channel] / 100f)).coerceIn(0f, 1f)
                        val mixedValue = (maximum + mixLuminance[channel] / 100f * .35f).coerceIn(0f, 1f)
                        val chroma = mixedValue * mixedSaturation
                        val hueSection = hue / 60f
                        val second = chroma * (1f - abs(hueSection % 2f - 1f))
                        val match = mixedValue - chroma
                        when (floor(hueSection).toInt()) {
                            0 -> { r = chroma + match; g = second + match; b = match }
                            1 -> { r = second + match; g = chroma + match; b = match }
                            2 -> { r = match; g = chroma + match; b = second + match }
                            3 -> { r = match; g = second + match; b = chroma + match }
                            4 -> { r = second + match; g = match; b = chroma + match }
                            else -> { r = chroma + match; g = match; b = second + match }
                        }
                    }
                }
                if (state.defringe > 0f) {
                    val amount = state.defringe / 100f
                    val magentaExcess = (((r + b) * .5f) - g - .08f).coerceAtLeast(0f)
                    val greenExcess = (g - (r + b) * .5f - .08f).coerceAtLeast(0f)
                    g += magentaExcess * amount * .9f
                    r += greenExcess * amount * .45f
                    b += greenExcess * amount * .45f
                }
                if (localMasks.isNotEmpty()) {
                    val px = x.toFloat() / max(1, width - 1)
                    for (maskIndex in localMasks.indices) {
                        val mask = localMasks[maskIndex]
                        val dx = px - mask.centerX
                        val dy = py - mask.centerY
                        var weight = if (mask.type == "Brush") {
                            val brush = brushMaps[maskIndex]
                            if (brush == null) 0f else {
                                val brushX = (px * (brush.width - 1)).toInt().coerceIn(0, brush.width - 1)
                                val brushY = (py * (brush.height - 1)).toInt().coerceIn(0, brush.height - 1)
                                ((brush.alpha[brushY * brush.width + brushX] ushr 24) and 255) / 255f * mask.flow
                            }
                        } else if (mask.type == "Linear") {
                            val projected = dx * maskCosines[maskIndex] + dy * maskSines[maskIndex]
                            val transition = (mask.size * mask.feather).coerceAtLeast(.02f)
                            (projected / transition + .5f).coerceIn(0f, 1f)
                        } else {
                            val distance = sqrt(dx * dx + dy * dy)
                            val outer = mask.size.coerceAtLeast(.05f)
                            val inner = outer * (1f - mask.feather.coerceIn(.02f, 1f))
                            when {
                                distance <= inner -> 1f
                                distance >= outer -> 0f
                                else -> 1f - (distance - inner) / (outer - inner).coerceAtLeast(.001f)
                            }
                        }
                        if (mask.invert) weight = 1f - weight
                        if (weight <= .001f) continue
                        val localExposure = 2.0.pow((mask.exposure * weight).toDouble()).toFloat()
                        val localContrast = 1f + mask.contrast / 100f * weight
                        r = (r * localExposure - .5f) * localContrast + .5f
                        g = (g * localExposure - .5f) * localContrast + .5f
                        b = (b * localExposure - .5f) * localContrast + .5f
                        r += mask.temperature / 100f * .12f * weight
                        b -= mask.temperature / 100f * .12f * weight
                        val localLuma = .2126f * r + .7152f * g + .0722f * b
                        val localSaturation = 1f + mask.saturation / 100f * weight
                        r = localLuma + (r - localLuma) * localSaturation
                        g = localLuma + (g - localLuma) * localSaturation
                        b = localLuma + (b - localLuma) * localSaturation
                    }
                }
                if (curvesActive) {
                    r = lutR[(r.coerceIn(0f, 1f) * 255f).toInt()]
                    g = lutG[(g.coerceIn(0f, 1f) * 255f).toInt()]
                    b = lutB[(b.coerceIn(0f, 1f) * 255f).toInt()]
                }
                val dehaze = state.dehaze / 100f * .25f
                r = (r - .5f) * (1f + dehaze) + .5f
                g = (g - .5f) * (1f + dehaze) + .5f
                b = (b - .5f) * (1f + dehaze) + .5f
                val nx = x.toFloat() / max(1, width - 1) * 2f - 1f
                val distance = min(1f, (nx * nx + ny * ny) / 1.35f)
                val vignette = (state.vignette + state.opticsVignette).coerceIn(-100f, 100f) / 100f * distance * .72f
                val noise = if (grain > 0f) (random.nextFloat() - .5f) * grain / 255f else 0f
                r = r * (1f + vignette) + noise
                g = g * (1f + vignette) + noise
                b = b * (1f + vignette) + noise
                pixels[index] = (color and -0x1000000) or
                    ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or
                    ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or
                    (b.coerceIn(0f, 1f) * 255).toInt()
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, top, width, rows)
        }
        // Sharpening amount is modulated by Detail (fine vs. coarse) and Radius (halo width),
        // mirroring Lightroom's Detail panel. Texture and Clarity add broad local contrast.
        val sharpenBase = (state.sharpen / 100f) * (0.4f + state.sharpenDetail / 100f) * (0.7f + state.sharpenRadius * 0.3f)
        val detailAmount = (sharpenBase + (state.texture * .35f + state.clarity * .25f) / 100f).coerceIn(-.6f, 1.8f)
        val smoothing = (state.noiseReduction / 100f * .65f).coerceIn(0f, .65f)
        val maskThreshold = state.sharpenMasking / 100f * .18f
        val nrEdgePreserve = state.nrDetail / 100f
        val nrLocalContrast = state.nrContrast / 100f * .4f
        if ((abs(detailAmount) > .01f || smoothing > .01f) && width > 2 && height > 2) {
            val detailed = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            var above = IntArray(width); var center = IntArray(width); var below = IntArray(width)
            val output = IntArray(width)
            bitmap.getPixels(above, 0, width, 0, 0, width, 1)
            bitmap.getPixels(center, 0, width, 0, 1, width, 1)
            fun channel(color: Int, shift: Int) = (color shr shift) and 255
            for (y in 1 until height - 1) {
                bitmap.getPixels(below, 0, width, 0, y + 1, width, 1)
                output[0] = center[0]; output[width - 1] = center[width - 1]
                for (x in 1 until width - 1) {
                    val color = center[x]
                    fun refined(shift: Int): Int {
                        val c = channel(color, shift).toFloat()
                        val average = (channel(center[x - 1], shift) + channel(center[x + 1], shift) + channel(above[x], shift) + channel(below[x], shift)) / 4f
                        val edge = abs(c - average) / 255f
                        val maskFactor = if (maskThreshold <= 0f) 1f else ((edge - maskThreshold) / .12f).coerceIn(0f, 1f)
                        val localSmoothing = smoothing * (1f - nrEdgePreserve * (edge * 6f).coerceIn(0f, 1f))
                        var softened = c + (average - c) * localSmoothing
                        if (nrLocalContrast > 0f && localSmoothing > 0f) softened = average + (softened - average) * (1f + nrLocalContrast)
                        return (softened + (softened - average) * detailAmount * maskFactor).toInt().coerceIn(0, 255)
                    }
                    output[x] = (color and -0x1000000) or (refined(16) shl 16) or (refined(8) shl 8) or refined(0)
                }
                detailed.setPixels(output, 0, width, 0, y, width, 1)
                val oldAbove = above; above = center; center = below; below = oldAbove
            }
            bitmap.recycle()
            return detailed
        }
        return bitmap
    }

    private fun applyHealing(source: Bitmap, state: DevelopState): Bitmap {
        if (state.healingSpots.isEmpty()) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val width = source.width; val height = source.height
        val sourceRow = IntArray(width); val targetRow = IntArray(width)
        val shortSide = min(width, height).toFloat()
        state.healingSpots.forEach { spot ->
            val targetCenterX = (spot.targetX * (width - 1)).toInt()
            val targetCenterY = (spot.targetY * (height - 1)).toInt()
            val sourceCenterX = (spot.sourceX * (width - 1)).toInt()
            val sourceCenterY = (spot.sourceY * (height - 1)).toInt()
            val radius = (spot.radius * shortSide).coerceAtLeast(2f)
            val inner = radius * (1f - spot.feather.coerceIn(.02f, 1f))
            val top = max(0, (targetCenterY - radius).toInt())
            val bottom = min(height - 1, (targetCenterY + radius).toInt())
            val left = max(0, (targetCenterX - radius).toInt())
            val right = min(width - 1, (targetCenterX + radius).toInt())
            for (y in top..bottom) {
                val sourceY = (sourceCenterY + y - targetCenterY).coerceIn(0, height - 1)
                source.getPixels(sourceRow, 0, width, 0, sourceY, width, 1)
                output.getPixels(targetRow, 0, width, 0, y, width, 1)
                for (x in left..right) {
                    val dx = (x - targetCenterX).toFloat(); val dy = (y - targetCenterY).toFloat()
                    val distance = sqrt(dx * dx + dy * dy)
                    if (distance >= radius) continue
                    val featherWeight = if (distance <= inner) 1f else 1f - (distance - inner) / (radius - inner).coerceAtLeast(1f)
                    val alpha = (spot.opacity * featherWeight).coerceIn(0f, 1f)
                    if (alpha <= .001f) continue
                    val sourceX = (sourceCenterX + x - targetCenterX).coerceIn(0, width - 1)
                    val from = sourceRow[sourceX]; val to = targetRow[x]
                    fun blend(shift: Int): Int {
                        val sourceChannel = (from shr shift) and 255
                        val targetChannel = (to shr shift) and 255
                        return (targetChannel + (sourceChannel - targetChannel) * alpha).toInt().coerceIn(0, 255)
                    }
                    targetRow[x] = (to and -0x1000000) or (blend(16) shl 16) or (blend(8) shl 8) or blend(0)
                }
                output.setPixels(targetRow, 0, width, 0, y, width, 1)
            }
        }
        return output
    }

    private fun renderBrushMask(mask: LocalMask, sourceWidth: Int, sourceHeight: Int): BrushMaskMap {
        val scale = min(1f, 512f / max(sourceWidth, sourceHeight).coerceAtLeast(1))
        val width = max(1, (sourceWidth * scale).toInt())
        val height = max(1, (sourceHeight * scale).toInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path()
        mask.points.forEachIndexed { index, point ->
            val x = point.x * width; val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val radius = (mask.size * min(width, height)).coerceAtLeast(1f)
        fun paint(alpha: Int, widthScale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = radius * 2f * widthScale
        }
        val layers = listOf(
            paint(72, 1f),
            paint(150, (1f - mask.feather * .55f).coerceAtLeast(.25f)),
            paint(255, (1f - mask.feather).coerceAtLeast(.08f)),
        )
        layers.forEach { layer ->
            if (mask.points.size == 1) {
                val point = mask.points.first()
                canvas.drawCircle(point.x * width, point.y * height, layer.strokeWidth / 2f, Paint(layer).apply { style = Paint.Style.FILL })
            } else canvas.drawPath(path, layer)
        }
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        return BrushMaskMap(width, height, pixels)
    }

    private fun hueColor(hue: Float): FloatArray {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val x = 1f - abs(h % 2f - 1f)
        val sector = floor(h).toInt()
        return when (sector) {
            0 -> floatArrayOf(1f, x, 0f)
            1 -> floatArrayOf(x, 1f, 0f)
            2 -> floatArrayOf(0f, 1f, x)
            3 -> floatArrayOf(0f, x, 1f)
            4 -> floatArrayOf(x, 0f, 1f)
            else -> floatArrayOf(1f, 0f, x)
        }
    }
}
