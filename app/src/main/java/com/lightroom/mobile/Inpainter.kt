package com.lightroom.mobile

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Generative Remove with LaMa-Dilated (Qualcomm AI Hub's LiteRT build, Apache-2.0). The 174 MB model is
 * downloaded on first use into app storage and verified; until then callers fall back to the classic fill.
 * Inference runs on a 512x512 window around each spot and the result is blended back with a feathered edge.
 */
object Inpainter {
    sealed interface Status {
        data object NotInstalled : Status
        data class Downloading(val progress: Float) : Status
        data object Ready : Status
        data class Failed(val message: String) : Status
    }

    private const val MODEL_URL = "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/lama_dilated/releases/v0.65.0/lama_dilated-tflite-float.zip"
    private const val MODEL_ENTRY = "lama_dilated.tflite"
    private const val MODEL_SHA256 = "0d453a9a6fafdd52db8b11834799adc069b99465d1a65fc3df5b954773fa84d3"
    const val DOWNLOAD_MB = 170
    private const val SIZE = 512

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<Status>(Status.NotInstalled)
    val status: StateFlow<Status> = _status
    private var interpreter: Interpreter? = null
    private var releaseJob: Job? = null
    // LaMa's working memory is large (~600 MB native at 512x512), so the interpreter is closed shortly after use.
    private const val IDLE_RELEASE_MS = 20_000L

    private fun modelFile() = File(LiteRtModels.appContext.filesDir, "models/$MODEL_ENTRY")

    /** Call once at startup so the UI knows whether the model is already on the device. */
    fun refresh() { if (_status.value !is Status.Downloading) _status.value = if (modelFile().exists()) Status.Ready else Status.NotInstalled }

    val isReady: Boolean get() = _status.value == Status.Ready

    fun download() {
        if (_status.value is Status.Downloading || isReady) return
        _status.value = Status.Downloading(0f)
        scope.launch {
            val target = modelFile()
            val partial = File(target.parentFile, "$MODEL_ENTRY.part")
            runCatching {
                target.parentFile?.mkdirs()
                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: (DOWNLOAD_MB * 1_000_000L)
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                // Stream the zip and unpack the model entry on the fly: nothing but the model is written to disk.
                val counting = object : java.io.FilterInputStream(connection.inputStream) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                        if (n > 0) { read += n; _status.value = Status.Downloading((read.toFloat() / total).coerceIn(0f, 1f)) }
                    }
                }
                ZipInputStream(counting.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && !entry.name.endsWith("/$MODEL_ENTRY") && entry.name != MODEL_ENTRY) entry = zip.nextEntry
                    checkNotNull(entry) { "Model not found in download" }
                    partial.outputStream().buffered().use { out ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) { val n = zip.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); out.write(buffer, 0, n) }
                    }
                }
                connection.disconnect()
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                check(hash == MODEL_SHA256) { "Download was corrupted" }
                check(partial.renameTo(target)) { "Couldn't save the model" }
                _status.value = Status.Ready
            }.onFailure { error ->
                partial.delete()
                _status.value = Status.Failed(error.message ?: "Download failed")
            }
        }
    }

    fun delete() {
        synchronized(this) { interpreter?.close(); interpreter = null }
        modelFile().delete()
        _status.value = Status.NotInstalled
    }

    /**
     * Regenerates [region] of [image] in place. Returns false (leaving the image untouched) when the model isn't
     * installed or inference fails, so the caller can use its fallback.
     */
    @Synchronized
    fun inpaint(image: Bitmap, region: RemovalRegion, opacity: Float): Boolean = runCatching {
        if (!isReady) return false
        val model = interpreter ?: LiteRtModels.interpreter(LiteRtModels.mapFile(modelFile())).also { interpreter = it }
        // Context window: about 2.2x the painted area so LaMa sees enough surroundings, clamped to the image.
        // Long thin strokes get a non-square window; it's stretched to 512x512 and back.
        val side = (max(region.width, region.height) * 2.2f).coerceAtLeast(160f)
        val winW = side.coerceAtMost(image.width.toFloat()).toInt().coerceAtLeast(region.width)
        val winH = side.coerceAtMost(image.height.toFloat()).toInt().coerceAtLeast(region.height)
        val cx = region.left + region.width / 2f; val cy = region.top + region.height / 2f
        val left = (cx - winW / 2f).toInt().coerceIn(0, image.width - winW)
        val top = (cy - winH / 2f).toInt().coerceIn(0, image.height - winH)
        val window = Bitmap.createBitmap(image, left, top, winW, winH)
        val scaled = Bitmap.createScaledBitmap(window, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE); scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== window) scaled.recycle()
        window.recycle()
        val sx = winW.toFloat() / SIZE; val sy = winH.toFloat() / SIZE
        val imageInput = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val maskInput = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val c = pixels[y * SIZE + x]
            imageInput.putFloat((c shr 16 and 255) / 255f); imageInput.putFloat((c shr 8 and 255) / 255f); imageInput.putFloat((c and 255) / 255f)
            // A model pixel is a hole if any image pixel it covers is (max over its footprint keeps thin strokes).
            var hole = 0f
            val ix0 = (left + x * sx).toInt() - region.left; val iy0 = (top + y * sy).toInt() - region.top
            val ix1 = (left + (x + 1) * sx).toInt() - region.left; val iy1 = (top + (y + 1) * sy).toInt() - region.top
            for (iy in max(iy0, 0)..min(iy1, region.height - 1)) {
                for (ix in max(ix0, 0)..min(ix1, region.width - 1)) if (region.hole[iy * region.width + ix] > .5f) { hole = 1f; break }
                if (hole > 0f) break
            }
            maskInput.putFloat(hole)
        }
        imageInput.rewind(); maskInput.rewind()
        val output = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val inputs = arrayOfNulls<Any>(2)
        // Bind by tensor name rather than position, in case a future export reorders them.
        for (i in 0 until model.inputTensorCount) inputs[i] = if (model.getInputTensor(i).name().contains("mask")) maskInput else imageInput
        model.runForMultipleInputsOutputs(inputs, mapOf(0 to output))
        output.rewind()
        val floats = output.asFloatBuffer()
        val painted = IntArray(SIZE * SIZE) { i ->
            fun ch(k: Int) = (floats.get(i * 3 + k).coerceIn(0f, 1f) * 255f + .5f).toInt()
            -0x1000000 or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
        }
        matchSeams(painted, pixels, maskInput)
        val patch = Bitmap.createBitmap(painted, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val restored = Bitmap.createScaledBitmap(patch, winW, winH, true)
        if (restored !== patch) patch.recycle()
        region.blend(image, restored, left, top, opacity)
        restored.recycle()
        true
    }.getOrDefault(false).also { scheduleRelease() }

    /**
     * LaMa returns known pixels untouched, but its fill can sit slightly off-tone right at the hole's edge, which
     * shows as a seam. Measure that step at every edge pixel (fill vs. the real neighbours just outside), spread
     * the correction smoothly over the hole, and add it: each part of the edge (sky, ground...) is matched on its own.
     */
    private fun matchSeams(painted: IntArray, original: IntArray, mask: ByteBuffer) {
        val hole = BooleanArray(SIZE * SIZE) { mask.getFloat(it * 4) > .5f }
        val r = FloatArray(SIZE * SIZE); val g = FloatArray(SIZE * SIZE); val b = FloatArray(SIZE * SIZE); val w = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val i = y * SIZE + x
            if (!hole[i]) continue
            var sr = 0f; var sg = 0f; var sb = 0f; var n = 0
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until SIZE || ny !in 0 until SIZE) continue
                val j = ny * SIZE + nx
                if (hole[j]) continue
                val o = original[j]; sr += o shr 16 and 255; sg += o shr 8 and 255; sb += o and 255; n++
            }
            if (n == 0) continue
            val p = painted[i]
            r[i] = sr / n - (p shr 16 and 255); g[i] = sg / n - (p shr 8 and 255); b[i] = sb / n - (p and 255); w[i] = 1f
        }
        if (w.none { it > 0f }) return
        val offset = PushPullFill.interpolate(r, g, b, w, SIZE, SIZE)
        for (i in painted.indices) {
            if (!hole[i]) continue
            val p = painted[i]
            painted[i] = -0x1000000 or (((p shr 16 and 255) + offset[0][i]).toInt().coerceIn(0, 255) shl 16) or
                (((p shr 8 and 255) + offset[1][i]).toInt().coerceIn(0, 255) shl 8) or ((p and 255) + offset[2][i]).toInt().coerceIn(0, 255)
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IDLE_RELEASE_MS)
            synchronized(this@Inpainter) { interpreter?.close(); interpreter = null }
        }
    }
}
