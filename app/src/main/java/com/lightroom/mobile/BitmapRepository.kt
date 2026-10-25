package com.lightroom.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Memory-bounded, sampled bitmap cache shared by the library and editor. */
object BitmapRepository {
    private val cacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKb) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    suspend fun load(context: Context, uri: Uri, requestedSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val bucket = when {
            requestedSide <= 384 -> 384
            requestedSide <= 768 -> 768
            else -> 1600
        }
        val key = "$uri@$bucket"
        cache.get(key)?.takeUnless { it.isRecycled } ?: decode(context, uri, bucket)?.also { cache.put(key, it) }
    }

    private fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? =
        RawDecoder.decode(context, uri, maxSide)

    fun clear() = cache.evictAll()
}
