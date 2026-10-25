package com.lightroom.mobile

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

data class RawFrame(val bitmap: Bitmap, val rgb16: java.nio.ByteBuffer?)

object NativeRaw {
    val available = runCatching { System.loadLibrary("lumaroom_raw"); true }.getOrDefault(false)
    private external fun decodeFile(path: String, maxSide: Int, icc: String?, precision: Boolean): RawFrame?
    external fun validateProfile(path: String): Boolean
    fun isRaw(context: Context, uri: Uri): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty().lowercase()
        val name = if (uri.scheme == "file") uri.lastPathSegment.orEmpty() else runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "" }
        }.getOrNull().orEmpty()
        return name.substringAfterLast('.').lowercase() in RawDecoder.rawExtensions || mime.contains("adobe-dng") || mime.contains("image/x-") && mime.contains("raw")
    }
    fun decode(context: Context, uri: Uri, maxSide: Int, profile: String = ""): Bitmap? = decodeFrame(context, uri, maxSide, profile, false)?.bitmap
    @Synchronized fun decodeFrame(context: Context, uri: Uri, maxSide: Int, profile: String = "", precision: Boolean = true): RawFrame? {
        check(available) { "Native RAW engine unavailable on this architecture" }
        val icc = profile.takeIf { it.matches(Regex("[a-f0-9]{64}\\.icc")) }?.let { File(context.filesDir, "camera-profiles/$it").path }
        if (uri.scheme == "file") {
            val path=requireNotNull(uri.path)
            require(File(path).length()<=512L*1024*1024) { "RAW exceeds 512 MB input limit" }
            return decodeFile(path, maxSide, icc, precision)
        }
        // Providers need not expose seekable FDs. A private temporary copy supports every provider.
        val temporary = File.createTempFile("raw-", ".bin", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(65536); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; check(total <= 512L * 1024 * 1024) { "RAW exceeds 512 MB input limit" }; output.write(buffer, 0, n) }
            } } ?: return null
            decodeFile(temporary.path, maxSide, icc, precision)
        } finally { temporary.delete() }
    }
}
