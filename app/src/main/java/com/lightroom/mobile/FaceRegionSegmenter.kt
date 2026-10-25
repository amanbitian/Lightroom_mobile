package com.lightroom.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

/** Facial regions from the bundled ML Kit contour detector. Contours describe geometry, not semantic teeth. */
object FaceRegionSegmenter {
    private const val DETECT_LONG_SIDE = 2048
    private const val MASK_LONG_SIDE = 768
    private var lastSource = WeakReference<Bitmap>(null)
    private var lastMasks: Map<String, SubjectSegmenter.Mask?> = emptyMap()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage

    @Synchronized
    fun mask(source: Bitmap, type: String): SubjectSegmenter.Mask? {
        if (lastSource.get() !== source) {
            lastMasks = runCatching { detect(source) }.onFailure {
                mutableMessage.value = "Face detection failed: ${it.message ?: it.javaClass.simpleName}"
            }.getOrDefault(emptyMap())
            lastSource = WeakReference(source)
        }
        return lastMasks[type]
    }

    private fun detect(source: Bitmap): Map<String, SubjectSegmenter.Mask?> {
        val detectorScale = minOf(1f, DETECT_LONG_SIDE.toFloat() / max(source.width, source.height))
        val dw = max(1, (source.width * detectorScale).toInt())
        val dh = max(1, (source.height * detectorScale).toInt())
        val detection = if (dw == source.width && dh == source.height) source else Bitmap.createScaledBitmap(source, dw, dh, true)
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        val detector = FaceDetection.getClient(options)
        val faces = try { Tasks.await(detector.process(InputImage.fromBitmap(detection, 0))) }
        catch (error: Exception) { if (detection !== source) detection.recycle(); throw error }
        finally { detector.close() }
        if (faces.isEmpty()) {
            if (detection !== source) detection.recycle()
            mutableMessage.value = "No face found. Use Brush for small, turned or obscured faces."
            return emptyMap()
        }
        mutableMessage.value = null

        val scale = minOf(1f, MASK_LONG_SIDE.toFloat() / max(dw, dh))
        val w = max(1, (dw * scale).toInt()); val h = max(1, (dh * scale).toInt())
        val kinds = listOf("Face", "Eyes", "Eyebrows", "Lips", "Teeth")
        val layers = kinds.associateWith { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
        val canvases = layers.mapValues { Canvas(it.value) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; style = Paint.Style.FILL }
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

        fun path(vararg contours: List<android.graphics.PointF>, reverseLast: Boolean = false): Path? {
            val points = contours.flatMapIndexed { i, pts -> if (reverseLast && i == contours.lastIndex) pts.reversed() else pts }
            if (points.size < 3) return null
            return Path().apply {
                moveTo(points[0].x * scale, points[0].y * scale)
                points.drop(1).forEach { lineTo(it.x * scale, it.y * scale) }
                close()
            }
        }
        // ML Kit reports contours for the most prominent face. If it returns more, use every face with contours.
        for (face in faces) {
            fun contour(which: Int) = face.getContour(which)?.points.orEmpty()
            path(contour(FaceContour.FACE))?.let { canvases.getValue("Face").drawPath(it, fill) }
            for (which in listOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE)) {
                path(contour(which))?.let { canvases.getValue("Eyes").drawPath(it, fill) }
            }
            for ((top, bottom) in listOf(
                FaceContour.LEFT_EYEBROW_TOP to FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP to FaceContour.RIGHT_EYEBROW_BOTTOM,
            )) {
                path(contour(top), contour(bottom), reverseLast = true)?.let { canvases.getValue("Eyebrows").drawPath(it, fill) }
            }
            val outer = path(contour(FaceContour.UPPER_LIP_TOP), contour(FaceContour.LOWER_LIP_BOTTOM), reverseLast = true)
            val inner = path(contour(FaceContour.UPPER_LIP_BOTTOM), contour(FaceContour.LOWER_LIP_TOP), reverseLast = true)
            outer?.let { canvases.getValue("Lips").drawPath(it, fill) }
            inner?.let {
                canvases.getValue("Lips").drawPath(it, clear)
                canvases.getValue("Teeth").drawPath(it, fill)
            }
        }

        // A contour detector does not identify teeth. Inside the inner-mouth contour only, retain bright,
        // near-neutral pixels. This avoids painting lips/tongue but may miss shadowed or strongly colored teeth.
        val small = if (detection.width == w && detection.height == h) detection else Bitmap.createScaledBitmap(detection, w, h, true)
        val colors = IntArray(w * h); small.getPixels(colors, 0, w, 0, 0, w, h)
        if (small !== detection) small.recycle()
        if (detection !== source) detection.recycle()
        val result = buildMap<String, SubjectSegmenter.Mask?> {
            for (kind in kinds) {
                val pixels = IntArray(w * h)
                layers.getValue(kind).getPixels(pixels, 0, w, 0, 0, w, h)
                val confidence = FloatArray(w * h) { i ->
                    val coverage = (pixels[i] ushr 24) / 255f
                    if (kind != "Teeth" || coverage == 0f) coverage else {
                        val c = colors[i]; val r = c shr 16 and 255; val g = c shr 8 and 255; val b = c and 255
                        val hi = maxOf(r, g, b); val lo = minOf(r, g, b)
                        val brightness = ((hi - 85f) / 70f).coerceIn(0f, 1f)
                        val neutral = ((100f - (hi - lo)) / 70f).coerceIn(0f, 1f)
                        coverage * brightness * neutral
                    }
                }
                put(kind, SubjectSegmenter.Mask(w, h, confidence))
                layers.getValue(kind).recycle()
            }
        }
        return result
    }
}
