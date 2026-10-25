package com.lightroom.mobile

import java.nio.ShortBuffer

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writers for 16-bit-per-channel RGB images. Input is a ShortBuffer of length w*h*3 (R,G,B),
 * values 0..65535 (stored in the low 16 bits of each Short). Android's Bitmap.compress cannot
 * emit 16-bit files, so these encoders are written by hand.
 */
object TiffPngWriter {

    /** Baseline uncompressed little-endian RGB 16-bit TIFF. */
    fun writeTiff16(pixels: ShortBuffer, width: Int, height: Int, out: OutputStream) {
        val entryCount = 11
        val ifdOffset = 8
        val ifdSize = 2 + entryCount * 12 + 4
        val bitsPerSampleOffset = ifdOffset + ifdSize
        val sampleFormatOffset = bitsPerSampleOffset + 6
        val pixelOffset = sampleFormatOffset + 6
        val pixelBytes = width.toLong() * height.toLong() * 6L

        val header = ByteArray(pixelOffset)
        fun putShort(o: Int, v: Int) { header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v shr 8) and 0xFF).toByte() }
        fun putLong(o: Int, v: Long) {
            header[o] = (v and 0xFF).toByte(); header[o + 1] = ((v shr 8) and 0xFF).toByte()
            header[o + 2] = ((v shr 16) and 0xFF).toByte(); header[o + 3] = ((v shr 24) and 0xFF).toByte()
        }
        // TIFF header (little-endian).
        header[0] = 'I'.code.toByte(); header[1] = 'I'.code.toByte()
        putShort(2, 42); putLong(4, ifdOffset.toLong())
        putShort(ifdOffset, entryCount)
        var e = ifdOffset + 2
        fun entry(tag: Int, type: Int, count: Long, value: Long) {
            putShort(e, tag); putShort(e + 2, type); putLong(e + 4, count); putLong(e + 8, value); e += 12
        }
        // type: 3 = SHORT, 4 = LONG. For SHORT count 1 the value sits in the low 16 bits.
        entry(256, 4, 1, width.toLong())                 // ImageWidth
        entry(257, 4, 1, height.toLong())                // ImageLength
        entry(258, 3, 3, bitsPerSampleOffset.toLong())   // BitsPerSample -> 16,16,16
        entry(259, 3, 1, 1)                              // Compression = none
        entry(262, 3, 1, 2)                              // Photometric = RGB
        entry(273, 4, 1, pixelOffset.toLong())           // StripOffsets
        entry(277, 3, 1, 3)                              // SamplesPerPixel
        entry(278, 4, 1, height.toLong())                // RowsPerStrip
        entry(279, 4, 1, pixelBytes)                     // StripByteCounts
        entry(284, 3, 1, 1)                              // PlanarConfig = chunky
        entry(339, 3, 3, sampleFormatOffset.toLong())    // SampleFormat -> 1,1,1 (uint)
        putLong(e, 0)                                    // next IFD = 0
        putShort(bitsPerSampleOffset, 16); putShort(bitsPerSampleOffset + 2, 16); putShort(bitsPerSampleOffset + 4, 16)
        putShort(sampleFormatOffset, 1); putShort(sampleFormatOffset + 2, 1); putShort(sampleFormatOffset + 4, 1)
        out.write(header)

        // Pixel data, little-endian 16-bit samples, in strips/rows.
        val rowBytes = ByteArray(width * 6)
        for (y in 0 until height) {
            var bi = 0
            val base = y * width * 3
            for (x in 0 until width) {
                val p = base + x * 3
                for (c in 0..2) {
                    val v = pixels.get(p + c).toInt() and 0xFFFF
                    rowBytes[bi++] = (v and 0xFF).toByte(); rowBytes[bi++] = ((v shr 8) and 0xFF).toByte()
                }
            }
            out.write(rowBytes)
        }
        out.flush()
    }

    /** 16-bit RGB PNG (bit depth 16, color type 2), filter None, zlib-deflated. */
    fun writePng16(pixels: ShortBuffer, width: Int, height: Int, out: OutputStream) {
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        fun putInt(a: ByteArray, o: Int, v: Int) {
            a[o] = ((v shr 24) and 0xFF).toByte(); a[o + 1] = ((v shr 16) and 0xFF).toByte()
            a[o + 2] = ((v shr 8) and 0xFF).toByte(); a[o + 3] = (v and 0xFF).toByte()
        }
        putInt(ihdr, 0, width); putInt(ihdr, 4, height)
        ihdr[8] = 16; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0
        writeChunk(out, "IHDR", ihdr)

        // Stream scanlines (1 filter byte + big-endian 16-bit RGB) through the deflater one row at a time and emit
        // IDAT chunks as compressed data accumulates: memory stays O(row) instead of the whole image (144 MB at 24 MP).
        val deflater = Deflater(Deflater.BEST_SPEED)
        val row = ByteArray(1 + width * 6)
        val buffer = ByteArray(256 * 1024)
        fun drain(flush: Boolean) {
            while (true) {
                val n = if (flush) deflater.deflate(buffer) else deflater.deflate(buffer, 0, buffer.size, Deflater.NO_FLUSH)
                if (n > 0) writeChunk(out, "IDAT", buffer.copyOf(n))
                if (n < buffer.size && (if (flush) deflater.finished() else deflater.needsInput())) break
            }
        }
        for (y in 0 until height) {
            var ri = 0
            row[ri++] = 0
            val base = y * width * 3
            for (x in 0 until width) {
                val p = base + x * 3
                for (c in 0..2) {
                    val v = pixels.get(p + c).toInt() and 0xFFFF
                    row[ri++] = ((v shr 8) and 0xFF).toByte(); row[ri++] = (v and 0xFF).toByte()
                }
            }
            deflater.setInput(row)
            drain(flush = false)
        }
        deflater.finish()
        drain(flush = true)
        deflater.end()
        writeChunk(out, "IEND", ByteArray(0))
        out.flush()
    }

    private fun writeChunk(out: OutputStream, type: String, data: ByteArray) {
        val len = data.size
        out.write(byteArrayOf(((len shr 24) and 0xFF).toByte(), ((len shr 16) and 0xFF).toByte(), ((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()))
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes); out.write(data)
        val crc = CRC32(); crc.update(typeBytes); crc.update(data)
        val c = crc.value
        out.write(byteArrayOf(((c shr 24) and 0xFF).toByte(), ((c shr 16) and 0xFF).toByte(), ((c shr 8) and 0xFF).toByte(), (c and 0xFF).toByte()))
    }
}
