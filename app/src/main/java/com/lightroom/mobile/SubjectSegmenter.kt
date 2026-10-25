package com.lightroom.mobile

import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * On-device people segmentation with MediaPipe's multiclass selfie model (assets/models), run through
 * LiteRT. One pass yields every person part, so Person, Hair, Skin and Clothes masks share the same
 * inference. Every failure path returns null so callers can skip the effect instead of breaking an export.
 */
object SubjectSegmenter {
    data class Mask(val width: Int, val height: Int, val confidence: FloatArray)

    /** Model classes: 0 background, 1 hair, 2 body skin, 3 face skin, 4 clothes, 5 other (accessories). */
    enum class Part(val classes: IntArray) {
        PERSON(intArrayOf(1, 2, 3, 4)), HAIR(intArrayOf(1)), SKIN(intArrayOf(2, 3)), CLOTHES(intArrayOf(4)),
        // Face = face-skin plus hair, so a "keep the face sharp" lens-blur focus covers the whole head.
        FACE(intArrayOf(1, 3)),
    }

    private const val MODEL = "models/selfie_multiclass_256x256.tflite"
    private const val SIZE = 256
    private const val CLASSES = 6
    // The model only sees 256x256; we refine its mask against the photo at this resolution so edges follow the subject.
    private const val REFINE = 384

    private var interpreter: Interpreter? = null
    // The last inference, keyed by bitmap identity: the editor asks for several parts of the same photo.
    private var lastSource: Bitmap? = null
    private var lastProbabilities: FloatArray? = null

    fun foregroundMask(bitmap: Bitmap): Mask? = mask(bitmap, Part.PERSON)

    @Synchronized
    fun mask(bitmap: Bitmap, part: Part): Mask? = runCatching {
        val probabilities = probabilities(bitmap) ?: return null
        val confidence = FloatArray(SIZE * SIZE) { i ->
            val core = part.classes.sumOf { c -> probabilities[i * CLASSES + c].toDouble() }.toFloat()
            if (part != Part.PERSON) core else {
                // "Other" (accessories) only counts where it sits on a person: on its own the class fires on flat areas.
                val t = ((core - .15f) / .35f).coerceIn(0f, 1f)
                core + probabilities[i * CLASSES + 5] * t * t * (3f - 2f * t)
            }
        }
        refine(bitmap, confidence)
    }.getOrNull()

    /**
     * Edge-aware refinement: the model mask is only 256x256 and its edge wanders off the subject. A guided filter
     * (He et al.) pulls the mask onto the photo's own edges using image luminance as the guide, so hair and outlines
     * come out crisp instead of a soft 256-grid blob. Runs at [REFINE] in the same stretched square space as the model.
     */
    private fun refine(source: Bitmap, coarse: FloatArray): Mask {
        val scaled = Bitmap.createScaledBitmap(source, REFINE, REFINE, true)
        val px = IntArray(REFINE * REFINE); scaled.getPixels(px, 0, REFINE, 0, 0, REFINE, REFINE)
        if (scaled !== source) scaled.recycle()
        val guide = FloatArray(REFINE * REFINE) { i ->
            val c = px[i]; (0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)) / 255f
        }
        // Upsample the coarse 256x256 mask to the guide resolution (bilinear).
        val p = FloatArray(REFINE * REFINE) { i ->
            val fx = (i % REFINE).toFloat() * (SIZE - 1) / (REFINE - 1); val fy = (i / REFINE).toFloat() * (SIZE - 1) / (REFINE - 1)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = minOf(x0 + 1, SIZE - 1); val y1 = minOf(y0 + 1, SIZE - 1)
            val tx = fx - x0; val ty = fy - y0
            val top = coarse[y0 * SIZE + x0] * (1 - tx) + coarse[y0 * SIZE + x1] * tx
            val bottom = coarse[y1 * SIZE + x0] * (1 - tx) + coarse[y1 * SIZE + x1] * tx
            top * (1 - ty) + bottom * ty
        }
        // Smaller eps = the mask hugs the photo's edges more tightly (crisper hair/outline); a tad of texture pickup is the trade.
        return Mask(REFINE, REFINE, guidedFilter(guide, p, REFINE, REFINE, radius = (REFINE / 48).coerceAtLeast(2), eps = 1e-4f))
    }

    /** Guided filter: output q = a·guide + b, with (a,b) fit in local windows so q follows the guide's edges. */
    private fun guidedFilter(guide: FloatArray, p: FloatArray, w: Int, h: Int, radius: Int, eps: Float): FloatArray {
        val n = guide.size
        val meanI = boxFilter(guide, w, h, radius); val meanP = boxFilter(p, w, h, radius)
        val corrI = boxFilter(FloatArray(n) { guide[it] * guide[it] }, w, h, radius)
        val corrIp = boxFilter(FloatArray(n) { guide[it] * p[it] }, w, h, radius)
        val a = FloatArray(n) { (corrIp[it] - meanI[it] * meanP[it]) / (corrI[it] - meanI[it] * meanI[it] + eps) }
        val b = FloatArray(n) { meanP[it] - a[it] * meanI[it] }
        val meanA = boxFilter(a, w, h, radius); val meanB = boxFilter(b, w, h, radius)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** Separable box blur (replicate border), the mean used by [guidedFilter]. */
    private fun boxFilter(src: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        fun pass(input: FloatArray, horizontal: Boolean): FloatArray {
            val out = FloatArray(input.size)
            val len = if (horizontal) w else h; val lines = if (horizontal) h else w; val window = radius * 2 + 1
            for (line in 0 until lines) {
                fun idx(pos: Int) = if (horizontal) line * w + pos else pos * w + line
                var sum = 0f
                for (k in -radius..radius) sum += input[idx(k.coerceIn(0, len - 1))]
                for (pos in 0 until len) {
                    out[idx(pos)] = sum / window
                    sum += input[idx((pos + radius + 1).coerceIn(0, len - 1))] - input[idx((pos - radius).coerceIn(0, len - 1))]
                }
            }
            return out
        }
        return pass(pass(src, true), false)
    }

    /** Per-pixel softmax over the six classes for a 256x256 view of [bitmap] (stretched, as MediaPipe does). */
    private fun probabilities(bitmap: Bitmap): FloatArray? {
        if (lastSource === bitmap && !bitmap.isRecycled) lastProbabilities?.let { return it }
        val model = interpreter ?: LiteRtModels.interpreter(LiteRtModels.mapAsset(MODEL)).also { interpreter = it }
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE); scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== bitmap) scaled.recycle()
        // The model's metadata normalizes with mean = std = 127.5, i.e. inputs in -1..1.
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        pixels.forEach { c ->
            input.putFloat(((c shr 16 and 255) - 127.5f) / 127.5f)
            input.putFloat(((c shr 8 and 255) - 127.5f) / 127.5f)
            input.putFloat(((c and 255) - 127.5f) / 127.5f)
        }
        input.rewind()
        val output = ByteBuffer.allocateDirect(SIZE * SIZE * CLASSES * 4).order(ByteOrder.nativeOrder())
        model.run(input, output)
        output.rewind()
        val logits = FloatArray(SIZE * SIZE * CLASSES); output.asFloatBuffer().get(logits)
        for (i in 0 until SIZE * SIZE) {
            val base = i * CLASSES
            var max = logits[base]; for (c in 1 until CLASSES) max = maxOf(max, logits[base + c])
            var sum = 0f
            for (c in 0 until CLASSES) { val e = exp(logits[base + c] - max); logits[base + c] = e; sum += e }
            for (c in 0 until CLASSES) logits[base + c] /= sum
        }
        lastSource = bitmap; lastProbabilities = logits
        return logits
    }
}
