package com.lightroom.mobile

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.sqrt

/** Deterministic, on-device helpers for Auto edit and Auto straighten (no network, no ML models). */
object ImageAnalysis {

    private fun sample(bitmap: Bitmap, targetWide: Int = 256): IntArray {
        val scale = (targetWide.toFloat() / bitmap.width.coerceAtLeast(1)).coerceAtMost(1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        return pixels
    }

    /**
     * Produces an auto-enhanced recipe from the image histogram and a gray-world white balance,
     * preserving the crop/geometry/local edits already present in [base].
     */
    fun autoEnhance(bitmap: Bitmap, base: DevelopState): DevelopState {
        val pixels = sample(bitmap)
        if (pixels.isEmpty()) return base
        val n = pixels.size
        val hist = IntArray(256)
        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            sumR += r; sumG += g; sumB += b
            val luma = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)
            hist[luma]++
        }
        fun percentile(fraction: Double): Int {
            val target = (n * fraction).toInt().coerceIn(0, n - 1)
            var acc = 0
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        val black = percentile(0.005)
        val white = percentile(0.995)
        val median = percentile(0.5)
        val meanLuma = (0.2126 * sumR + 0.7152 * sumG + 0.0722 * sumB) / n / 255.0

        // Exposure nudges the median toward a pleasing ~0.46; clamped to a gentle range.
        val exposure = (ln(0.46 / (median / 255.0).coerceIn(0.03, 0.97)) / ln(2.0)).toFloat().coerceIn(-1.2f, 1.2f)
        // Contrast from tonal spread: a narrow histogram gets more contrast.
        val spread = (white - black) / 255f
        val contrast = ((0.62f - spread) * 120f).coerceIn(-10f, 42f)
        // Recover highlights and open shadows proportionally to how clipped each end is.
        val highlights = (-(white - 236).coerceAtLeast(0) * 1.7f).coerceIn(-55f, 0f)
        val shadows = ((28 - black).coerceAtLeast(0) * 1.6f).coerceIn(0f, 55f)
        val whites = ((245 - white).coerceAtLeast(0) * 0.9f).coerceIn(0f, 30f)
        val blacks = (-(black - 8).coerceAtLeast(0) * 0.9f).coerceIn(-30f, 0f)

        // Gray-world white balance: push temperature/tint to neutralize an average color cast.
        val avgR = sumR / n; val avgG = sumG / n; val avgB = sumB / n
        // A black frame has no colour cast to measure (and would divide by zero into NaN, which can't be saved).
        val gray = ((avgR + avgG + avgB) / 3.0).coerceAtLeast(1.0)
        val temperature = (((gray - avgB) - (gray - avgR)) / gray * 140.0).toFloat().coerceIn(-40f, 40f)
        val tint = ((gray - avgG) / gray * 90.0).toFloat().coerceIn(-30f, 30f)
        val vibrance = (14f + (0.5f - meanLuma.toFloat()) * 10f).coerceIn(6f, 24f)

        return base.copy(
            exposure = exposure, contrast = contrast, highlights = highlights, shadows = shadows,
            whites = whites, blacks = blacks, temperature = temperature, tint = tint, vibrance = vibrance,
        )
    }

    /**
     * Estimates a small straightening angle (degrees) from dominant near-horizontal edges using
     * a Sobel gradient orientation vote. Returns 0 when no confident tilt is found.
     */
    fun autoStraighten(bitmap: Bitmap): Float {
        val targetWide = 256
        val scale = (targetWide.toFloat() / bitmap.width.coerceAtLeast(1)).coerceAtMost(1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(3)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(3)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        fun luma(i: Int) = ((px[i] shr 16 and 255) * 0.2126f + (px[i] shr 8 and 255) * 0.7152f + (px[i] and 255) * 0.0722f)
        var weightedAngle = 0.0
        var totalWeight = 0.0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = luma(i - 1 - w) + 2 * luma(i - 1) + luma(i - 1 + w) - luma(i + 1 - w) - 2 * luma(i + 1) - luma(i + 1 + w)
                val gy = luma(i - 1 - w) + 2 * luma(i - w) + luma(i + 1 - w) - luma(i - 1 + w) - 2 * luma(i + w) - luma(i + 1 + w)
                val mag = sqrt(gx * gx + gy * gy)
                if (mag < 90f) continue
                // Edge direction is perpendicular to the gradient.
                var edgeAngle = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())) + 90.0
                // Fold onto a horizontal reference in [-45, 45]; ignore near-vertical edges.
                edgeAngle = ((edgeAngle % 180.0) + 180.0) % 180.0
                if (edgeAngle > 45.0 && edgeAngle < 135.0) continue
                val dev = if (edgeAngle <= 45.0) edgeAngle else edgeAngle - 180.0
                if (abs(dev) <= 20.0) { weightedAngle += dev * mag; totalWeight += mag }
            }
        }
        if (totalWeight < 1.0) return 0f
        val tilt = (weightedAngle / totalWeight).toFloat()
        // The photo is rotated by -tilt to level the dominant edge.
        return (-tilt).coerceIn(-12f, 12f)
    }
}
