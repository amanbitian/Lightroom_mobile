package com.lightroom.mobile

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32

object PhotoMetadataIO {
    fun read(context: Context, uri: Uri): PhotoMetadata = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            val gps = exif.latLong
            PhotoMetadata(caption = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty(),
                copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT).orEmpty(),
                latitude = gps?.get(0), longitude = gps?.get(1),
                capturedAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).orEmpty(),
                cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty(), cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty(),
                lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL).orEmpty(),
                focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN).takeIf { it.isFinite() },
                aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN).takeIf { it.isFinite() },
                exposureTime=exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME).orEmpty(),iso=exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,0).takeIf{it>0})
        } ?: PhotoMetadata()
    }.getOrDefault(PhotoMetadata())

    fun xmp(metadata: PhotoMetadata, location: Boolean): String {
        val m = metadata.copy(latitude = metadata.latitude.takeIf { location }, longitude = metadata.longitude.takeIf { location })
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
            .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
        fun alt(tag: String, value: String) = "<$tag><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(value)}</rdf:li></rdf:Alt></$tag>"
        return """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:aux="http://ns.adobe.com/exif/1.0/aux/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/">""" +
            alt("dc:description", m.caption) + alt("dc:rights", m.copyright) +
            "<dc:subject><rdf:Bag>" + m.keywords.joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" } + "</rdf:Bag></dc:subject>" +
            "<exif:DateTimeOriginal>${esc(m.capturedAt.replaceFirst(":", "-").replaceFirst(":", "-").replace(' ', 'T'))}</exif:DateTimeOriginal>" +
            "<tiff:Make>${esc(m.cameraMake)}</tiff:Make><tiff:Model>${esc(m.cameraModel)}</tiff:Model>" +
            "<aux:Lens>${esc(m.lens)}</aux:Lens>" +
            (m.focalLength?.let{"<exif:FocalLength>${(it*10000).toLong()}/10000</exif:FocalLength>"}?:"") +
            (m.aperture?.let{"<exif:FNumber>${(it*10000).toLong()}/10000</exif:FNumber>"}?:"") +
            (m.iso?.let{"<exif:ISOSpeedRatings><rdf:Seq><rdf:li>$it</rdf:li></rdf:Seq></exif:ISOSpeedRatings>"}?:"") +
            (m.exposureTime.takeIf{it.isNotBlank()}?.let{"<exif:ExposureTime>${esc(it)}</exif:ExposureTime>"}?:"") +
            (if (m.latitude != null && m.longitude != null) {
                fun coordinate(value: Double, positive: String, negative: String): String { val a = kotlin.math.abs(value); return "${a.toInt()},${(a - a.toInt()) * 60}${if (value >= 0) positive else negative}" }
                "<exif:GPSLatitude>${coordinate(m.latitude, "N", "S")}</exif:GPSLatitude><exif:GPSLongitude>${coordinate(m.longitude, "E", "W")}</exif:GPSLongitude>"
            } else "") + "</rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    fun writeJpeg(file: File, metadata: PhotoMetadata, location: Boolean) {
        val e = ExifInterface(file)
        e.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, metadata.caption)
        e.setAttribute(ExifInterface.TAG_COPYRIGHT, metadata.copyright)
        e.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, metadata.capturedAt.takeIf { it.isNotBlank() })
        e.setAttribute(ExifInterface.TAG_MAKE, metadata.cameraMake)
        e.setAttribute(ExifInterface.TAG_MODEL, metadata.cameraModel)
        e.setAttribute(ExifInterface.TAG_LENS_MODEL, metadata.lens)
        e.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, metadata.focalLength?.toString())
        e.setAttribute(ExifInterface.TAG_F_NUMBER, metadata.aperture?.toString())
        e.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,metadata.iso?.toString())
        e.setAttribute(ExifInterface.TAG_EXPOSURE_TIME,metadata.exposureTime.takeIf{it.isNotBlank()})
        e.setAttribute(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL.toString())
        e.setAttribute(ExifInterface.TAG_XMP, xmp(metadata, location))
        if (location && metadata.latitude != null && metadata.longitude != null) e.setLatLong(metadata.latitude, metadata.longitude)
        e.saveAttributes()
    }

    /** PNG iTXt embeds standard XMP in both 8-bit and 16-bit exports, with no companion files. */
    fun pngXmp(out: OutputStream, xmp: String) {
        val data = "XML:com.adobe.xmp\u0000\u0000\u0000\u0000\u0000$xmp".toByteArray(Charsets.UTF_8)
        val type = "iTXt".toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(type); update(data) }.value.toInt()
        val stream = java.io.DataOutputStream(out)
        stream.writeInt(data.size); stream.write(type); stream.write(data); stream.writeInt(crc)
    }
    fun copyPngWithMetadata(file: File, out: OutputStream, xmp: String) {
        file.inputStream().use { input -> val header = ByteArray(33); java.io.DataInputStream(input).readFully(header); out.write(header); pngXmp(out, xmp); input.copyTo(out) }
    }
}
