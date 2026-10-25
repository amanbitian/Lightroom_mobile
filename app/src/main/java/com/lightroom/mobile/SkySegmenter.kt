package com.lightroom.mobile

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Produces a sky probability mask. If a TFLite sky model is added at assets/models/sky_segmentation.tflite
 * (and the TFLite runtime is linked) it would be used here; until then a deterministic heuristic runs:
 * sky is biased to the upper region and scored by blueness or bright/low-saturation (overcast).
 */
object SkySegmenter {
    fun skyMask(bitmap: Bitmap): SubjectSegmenter.Mask? = runCatching {
        val targetWide = 192
        val scale = (targetWide.toFloat() / bitmap.width.coerceAtLeast(1)).coerceAtMost(1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(2)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(2)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h); scaled.getPixels(px, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()
        val raw = FloatArray(w * h)
        for (y in 0 until h) {
            val yNorm = y.toFloat() / (h - 1)
            val vertical = (1f - yNorm * 1.25f).coerceIn(0f, 1f)
            if (vertical <= 0f) continue
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = ((c shr 16) and 255) / 255f; val g = ((c shr 8) and 255) / 255f; val b = (c and 255) / 255f
                val maxc = max(r, max(g, b)); val minc = min(r, min(g, b))
                val sat = if (maxc > 0f) (maxc - minc) / maxc else 0f
                val blue = ((b - max(r, g)) * 3f).coerceIn(0f, 1f)
                val overcast = if (maxc > 0.6f) (maxc * (1f - sat)).coerceIn(0f, 1f) * 0.85f else 0f
                raw[y * w + x] = vertical * max(blue, overcast)
            }
        }
        // Feather with a small separable blur so the sky edge is soft.
        val blurred = featherFloat(raw, w, h, max(1, w / 48))
        SubjectSegmenter.Mask(w, h, blurred)
    }.getOrNull()

    private fun featherFloat(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        if (radius < 1) return src
        fun pass(input: FloatArray, horizontal: Boolean): FloatArray {
            val out = FloatArray(input.size)
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val window = radius * 2 + 1
            for (line in 0 until lines) {
                fun idx(p: Int) = if (horizontal) line * w + p else p * w + line
                var sum = 0f
                for (k in -radius..radius) sum += input[idx(k.coerceIn(0, len - 1))]
                for (p in 0 until len) {
                    out[idx(p)] = sum / window
                    sum += input[idx((p + radius + 1).coerceIn(0, len - 1))] - input[idx((p - radius).coerceIn(0, len - 1))]
                }
            }
            return out
        }
        return pass(pass(src, true), false)
    }
}
