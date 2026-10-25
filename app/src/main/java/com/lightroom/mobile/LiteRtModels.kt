package com.lightroom.mobile

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Loads LiteRT models. Models are memory-mapped (never copied onto the heap) and run on the CPU with
 * LiteRT's default XNNPACK acceleration, which works on every device without a GPU delegate.
 */
object LiteRtModels {
    /** Application context, set once from MainActivity so model helpers can be called from anywhere. */
    lateinit var appContext: Context
        private set

    fun init(context: Context) { appContext = context.applicationContext }

    fun mapAsset(name: String): MappedByteBuffer {
        val fd = appContext.assets.openFd(name)
        return FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
    }

    fun mapFile(file: File): MappedByteBuffer =
        FileInputStream(file).channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

    fun interpreter(model: MappedByteBuffer): Interpreter =
        Interpreter(model, Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)))
}
