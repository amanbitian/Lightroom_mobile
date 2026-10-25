package com.lightroom.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Decodes photos including camera RAW. Strategy:
 *  1. Standard BitmapFactory (JPEG/PNG/WebP/HEIC/GIF/BMP).
 *  2. Platform ImageDecoder (handles DNG on devices with a RAW codec).
 *  3. Embedded-JPEG extraction — CR2/NEF/ARW/RAF/DNG all embed a full-size JPEG preview; we scan
 *     the file for the largest JPEG stream and decode that. This gives RAW import without a native
 *     demosaic library (the preview is 8-bit, like the rest of the pipeline's input).
 *
 * Every path returns an upright bitmap (EXIF orientation applied) whose long side is at most `maxSide`.
 */
object RawDecoder {
    val rawExtensions = setOf("dng", "cr2", "cr3", "nef", "arw", "raf", "rw2", "orf", "srw", "pef", "dcr", "kdc", "3fr")

    fun decode(context: Context, uri: Uri, maxSide: Int, metadataOverride: PhotoMetadata? = null): Bitmap? {
        val metadata = metadataOverride ?: LibraryStore(context).let { store -> try { store.loadMetadata(uri.toString()) } finally { store.close() } }
        val bitmap = decodeInput(context, uri, maxSide, metadata?.cameraProfile.orEmpty()) ?: return null
        if (metadata == null || metadata.lensProfile.isBlank()) return bitmap
        return try {
            val profile = LensProfiles.load(context).firstOrNull { it.id == metadata.lensProfile } ?: error("Lens profile unavailable")
            LensProfiles.correct(bitmap, profile, metadata)
        } catch (_: Exception) { bitmap.recycle(); null }
    }

    private fun decodeInput(context: Context, uri: Uri, maxSide: Int, cameraProfile: String): Bitmap? {
        if (NativeRaw.isRaw(context, uri)) return runCatching { NativeRaw.decode(context, uri, maxSide, cameraProfile) }.getOrNull()
        decodeStandard(context, uri, maxSide)?.let { return it }
        // ImageDecoder already applies EXIF orientation itself.
        decodeWithImageDecoder(context, uri, maxSide)?.let { return fitWithin(it, maxSide) }
        return decodeEmbeddedJpeg(context, uri, maxSide)
    }

    /** Largest power-of-two subsample that still leaves the long side >= maxSide (exact scaling happens after). */
    private fun sampleFor(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** Scales down so the long side is at most [maxSide]; recycles the input when it replaces it. */
    private fun fitWithin(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longSide = max(bitmap.width, bitmap.height)
        if (longSide <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longSide
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun readOrientation(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** Rotates/flips per the EXIF orientation tag (BitmapFactory ignores it, so portrait shots arrive sideways). */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun decodeStandard(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth, bounds.outHeight, maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        applyOrientation(fitWithin(decoded, maxSide), readOrientation(context, uri))
    }.getOrNull()

    private fun decodeWithImageDecoder(context: Context, uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.isMutableRequired = false
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = sampleFor(info.size.width, info.size.height, maxSide)
                if (sample > 1) decoder.setTargetSampleSize(sample)
            }.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false).also { _ -> it.recycle() } }
        }.getOrNull()
    }

    private fun decodeEmbeddedJpeg(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        // Memory-map the file instead of reading it onto the heap: RAW files are often 30-80 MB.
        val buffer = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { channel -> channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()) }
        } ?: return null
        // Find the largest JPEG stream; the first is usually a small thumbnail.
        var bestStart = -1; var bestLen = 0
        var i = 0
        val limit = buffer.limit()
        while (i < limit - 3) {
            if (buffer.u(i) == 0xFF && buffer.u(i + 1) == 0xD8 && buffer.u(i + 2) == 0xFF) {
                val end = jpegEnd(buffer, i)
                if (end > 0) {
                    if (end - i > bestLen) { bestLen = end - i; bestStart = i }
                    i = end
                    continue
                }
            }
            i++
        }
        if (bestStart < 0 || bestLen < 2048) return null
        // Only the chosen preview is copied onto the heap.
        val bytes = ByteArray(bestLen)
        buffer.position(bestStart); buffer.get(bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bestLen, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(bounds.outWidth.coerceAtLeast(1), bounds.outHeight.coerceAtLeast(1), maxSide)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bestLen, options) ?: return null
        // The preview carries no orientation of its own; the RAW container's EXIF tag applies to it.
        applyOrientation(fitWithin(decoded, maxSide), readOrientation(context, uri))
    }.getOrNull()

    private fun ByteBuffer.u(index: Int) = get(index).toInt() and 0xFF

    /**
     * Returns the index just past the EOI marker of the JPEG starting at [start], or -1. Walks the
     * segment structure instead of stopping at the first FFD9, so an EXIF thumbnail nested inside the
     * preview's APP1 segment doesn't truncate the preview.
     */
    private fun jpegEnd(buffer: ByteBuffer, start: Int): Int {
        val limit = buffer.limit()
        var p = start + 2
        while (p < limit - 3) {
            if (buffer.u(p) != 0xFF) return -1
            val marker = buffer.u(p + 1)
            when {
                marker == 0xFF -> { p++; continue } // fill byte
                marker == 0xD9 -> return p + 2
                marker == 0x01 || marker in 0xD0..0xD7 -> { p += 2; continue }
            }
            val length = (buffer.u(p + 2) shl 8) or buffer.u(p + 3)
            if (length < 2) return -1
            p += 2 + length
            if (marker == 0xDA) {
                // Entropy-coded data: skip to the next real marker (not a stuffed 0x00 or a restart marker).
                while (p < limit - 1) {
                    if (buffer.u(p) == 0xFF) {
                        val next = buffer.u(p + 1)
                        if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) break
                    }
                    p++
                }
            }
        }
        return -1
    }
}
