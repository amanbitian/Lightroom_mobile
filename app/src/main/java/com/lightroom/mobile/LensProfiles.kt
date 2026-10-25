package com.lightroom.mobile

import android.content.Context
import android.graphics.Bitmap
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import kotlin.math.*

data class LensCalibration(val model: String, val focal: Double, val aperture: Double, val coefficients: Map<String, Double>) {
    fun value(name: String, default: Double = 0.0) = coefficients[name] ?: default
    fun distortion(r: Double): Double = when (model) {
        "poly3" -> 1 - value("k1") + value("k1") * r * r
        "poly5" -> 1 + value("k1") * r * r + value("k2") * r.pow(4)
        "ptlens" -> value("a") * r.pow(3) + value("b") * r * r + value("c") * r + 1 - value("a") - value("b") - value("c")
        else -> 1.0
    }
    fun tca(r: Double, red: Boolean): Double = when (model) {
        "linear" -> value(if (red) "kr" else "kb", 1.0)
        "poly3" -> if (red) value("br") * r * r + value("cr") * r + value("vr", 1.0) else value("bb") * r * r + value("cb") * r + value("vb", 1.0)
        else -> 1.0
    }
}
data class LensProfile(val maker: String, val name: String, val crop: Double, val distortion: List<LensCalibration>, val tca: List<LensCalibration>, val vignetting: List<LensCalibration>) {
    val id get() = "$maker|$name"
    // Focal interpolation avoids jumps between the calibration points of a zoom lens.
    fun at(rows: List<LensCalibration>, focal: Double, aperture: Double? = null): LensCalibration? {
        if (rows.isEmpty()) return null
        val candidates = if (aperture != null) {
            val nearestAperture = rows.minBy { abs(it.aperture - aperture) }.aperture
            rows.filter { it.aperture == nearestAperture }
        } else rows
        val lower = candidates.filter { it.focal <= focal }.maxByOrNull { it.focal } ?: candidates.minBy { it.focal }
        val upper = candidates.filter { it.focal >= focal }.minByOrNull { it.focal } ?: candidates.maxBy { it.focal }
        if (lower.focal == upper.focal || lower.model != upper.model) return listOf(lower, upper).minBy { abs(it.focal - focal) }
        val t = ((focal - lower.focal) / (upper.focal - lower.focal)).coerceIn(0.0, 1.0)
        return lower.copy(focal = focal, coefficients = (lower.coefficients.keys + upper.coefficients.keys).associateWith { key -> lower.value(key) * (1-t) + upper.value(key) * t })
    }
}

/** Lensfun calibration data, implemented for rectilinear poly3/poly5/PTLens, linear/poly3 TCA and PA shading.
 * ACM and fisheye projections are excluded explicitly; no generic correction is substituted. */
object LensProfiles {
    @Volatile private var cached: List<LensProfile>? = null
    private val cameraCrops = mutableMapOf<String, Double>()
    private fun normalized(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
    @Synchronized fun load(context: Context): List<LensProfile> {
        cached?.let { return it }
        val result = mutableListOf<LensProfile>()
        context.assets.list("lensfun").orEmpty().filter { it.endsWith(".xml") }.sorted().forEach { file ->
            context.assets.open("lensfun/$file").use { input ->
                val p = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                var container = ""; var maker = ""; var name = ""; var crop = 1.0; var type = "rectilinear"
                val distortion = mutableListOf<LensCalibration>(); val tca = mutableListOf<LensCalibration>(); val vignetting = mutableListOf<LensCalibration>()
                while (p.eventType != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG) when {
                        p.name == "lens" || p.name == "camera" -> { container = p.name; maker = ""; name = ""; crop = 1.0; type = "rectilinear"; distortion.clear(); tca.clear(); vignetting.clear() }
                        container.isNotEmpty() && p.name in setOf("maker", "model", "cropfactor", "type") && p.getAttributeValue(null, "lang") == null -> {
                            when (p.name) { "maker" -> maker = p.nextText(); "model" -> name = p.nextText(); "cropfactor" -> crop = p.nextText().toDoubleOrNull() ?: 1.0; "type" -> type = p.nextText() }
                        }
                        container == "lens" && p.name in setOf("distortion", "tca", "vignetting") -> {
                            val row = LensCalibration(p.getAttributeValue(null, "model").orEmpty(), p.getAttributeValue(null, "focal")?.toDoubleOrNull() ?: 0.0,
                                p.getAttributeValue(null, "aperture")?.toDoubleOrNull() ?: 0.0,
                                (0 until p.attributeCount).mapNotNull { i -> p.getAttributeValue(i).toDoubleOrNull()?.let { p.getAttributeName(i) to it } }.toMap())
                            when (p.name) { "distortion" -> if (row.model in setOf("poly3", "poly5", "ptlens")) distortion += row
                                "tca" -> if (row.model in setOf("linear", "poly3")) tca += row
                                "vignetting" -> if (row.model == "pa") vignetting += row }
                        }
                    }
                    if (p.eventType == XmlPullParser.END_TAG && p.name == container) {
                        if (container == "camera") cameraCrops[normalized(name)] = crop
                        else if (type == "rectilinear" && (distortion.isNotEmpty() || tca.isNotEmpty() || vignetting.isNotEmpty())) result += LensProfile(maker, name, crop, distortion.toList(), tca.toList(), vignetting.toList())
                        container = ""
                    }
                    p.next()
                }
            }
        }
        return result.distinctBy { it.id }.sortedBy { it.name }.also { cached = it }
    }
    fun match(profiles: List<LensProfile>, metadata: PhotoMetadata): LensProfile? {
        if (metadata.lens.isBlank()) return null
        val exact = profiles.filter { normalized(it.name) == normalized(metadata.lens) }
        return exact.singleOrNull() // Ambiguous matches require explicit selection.
    }
    fun correct(source: Bitmap, profile: LensProfile, metadata: PhotoMetadata): Bitmap = correctFrame(source,profile,metadata,null).bitmap
    fun correctFrame(source: Bitmap, profile: LensProfile, metadata: PhotoMetadata, precision: java.nio.ByteBuffer?): RawFrame {
        val focal = metadata.focalLength ?: error("Focal length is required for lens correction")
        val distortion = profile.at(profile.distortion, focal); val tca = profile.at(profile.tca, focal)
        val shading = metadata.aperture?.let { profile.at(profile.vignetting, focal, it) }
        val w = source.width; val h = source.height; val cx = (w-1) / 2.0; val cy = (h-1) / 2.0
        val crop = cameraCrops[normalized(metadata.cameraModel)] ?: profile.crop
        val sensorRatio = profile.crop / crop
        val norm = min(w, h) / 2.0 / sensorRatio
        val diagonal = hypot(w.toDouble(), h.toDouble()) / 2 / sensorRatio
        fun mapped(x: Double, y: Double, c: Int, zoom: Double): Pair<Double, Double> {
            val dx = (x-cx) / zoom; val dy = (y-cy) / zoom; val r = hypot(dx,dy) / norm
            val d = distortion?.distortion(r) ?: 1.0
            val factor = d * if (c == 1) 1.0 else tca?.tca(r * d, c == 0) ?: 1.0
            return cx + dx * factor to cy + dy * factor
        }
        fun fits(zoom: Double): Boolean = (0..20).all { i ->
            val x = i / 20.0 * (w-1); val y = i / 20.0 * (h-1)
            listOf(x to 0.0, x to h-1.0, 0.0 to y, w-1.0 to y).all { (px,py) -> (0..2).all { c -> val (sx,sy)=mapped(px,py,c,zoom); sx in 0.0..w-1.0 && sy in 0.0..h-1.0 } }
        }
        var low = 1.0; var high = 4.0
        if (!fits(low)) repeat(18) { val mid = (low+high)/2; if (fits(mid)) high=mid else low=mid } else high=1.0
        val input16=precision?.duplicate()?.apply { clear(); order(java.nio.ByteOrder.nativeOrder()) }?.asShortBuffer()
        require(input16==null || input16.capacity()>=w*h*3) { "Incomplete 16-bit lens input" }
        val output16=if(input16==null)null else java.nio.ByteBuffer.allocateDirect(w*h*6).order(java.nio.ByteOrder.nativeOrder())
        val samples16=output16?.asShortBuffer()
        val pixels = if(input16==null) IntArray(w*h).also{source.getPixels(it,0,w,0,0,w,h)} else IntArray(0)
        val output = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); val row = IntArray(w)
        fun sample(x: Double, y: Double, c: Int): Double {
            val sx=x.coerceIn(0.0,w-1.0); val sy=y.coerceIn(0.0,h-1.0); val ix=sx.toInt(); val iy=sy.toInt(); val fx=sx-ix; val fy=sy-iy
            fun at(xx: Int, yy: Int) = if(input16==null) (pixels[yy*w+xx] shr (16-c*8) and 255).toDouble() else (input16.get((yy*w+xx)*3+c).toInt() and 65535)/257.0
            return (at(ix,iy)*(1-fx)+at(min(ix+1,w-1),iy)*fx)*(1-fy)+(at(ix,min(iy+1,h-1))*(1-fx)+at(min(ix+1,w-1),min(iy+1,h-1))*fx)*fy
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = hypot((x-cx)/high,(y-cy)/high)/diagonal
                val gain = shading?.let { 1 / (1+it.value("k1")*r*r+it.value("k2")*r.pow(4)+it.value("k3")*r.pow(6)).coerceAtLeast(.2) } ?: 1.0
                var color = -0x1000000
                for (c in 0..2) { val (sx,sy)=mapped(x.toDouble(),y.toDouble(),c,high); val v=sample(sx,sy,c)/255
                    val linear=if(v<=.04045) v/12.92 else ((v+.055)/1.055).pow(2.4)
                    val corrected=(linear*gain).coerceIn(0.0,1.0); val encoded=if(corrected<=.0031308)corrected*12.92 else 1.055*corrected.pow(1/2.4)-.055
                    color=color or ((encoded*255+.5).toInt().coerceIn(0,255) shl (16-c*8)) }
                if(samples16!=null) for(c in 0..2) {
                    val (sx,sy)=mapped(x.toDouble(),y.toDouble(),c,high); val v=sample(sx,sy,c)/255
                    val linear=if(v<=.04045)v/12.92 else ((v+.055)/1.055).pow(2.4)
                    val corrected=(linear*gain).coerceIn(0.0,1.0); val encoded=if(corrected<=.0031308)corrected*12.92 else 1.055*corrected.pow(1/2.4)-.055
                    samples16.put((y*w+x)*3+c,(encoded*65535+.5).toInt().coerceIn(0,65535).toShort())
                }
                row[x]=color
            }
            output.setPixels(row,0,w,0,y,w,1)
        }
        source.recycle(); return RawFrame(output,output16)
    }
}
