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
    }

    private const val MODEL = "models/selfie_multiclass_256x256.tflite"
    private const val SIZE = 256
    private const val CLASSES = 6

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
        Mask(SIZE, SIZE, confidence)
    }.getOrNull()

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
