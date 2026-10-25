package com.lightroom.mobile

import org.json.JSONArray
import org.json.JSONObject

/** Catalog metadata overrides never modify the imported original. Empty fields intentionally clear values. */
data class PhotoMetadata(
    val caption: String = "", val copyright: String = "", val keywords: List<String> = emptyList(),
    val latitude: Double? = null, val longitude: Double? = null,
    val capturedAt: String = "", val cameraMake: String = "", val cameraModel: String = "",
    val lens: String = "", val focalLength: Double? = null, val aperture: Double? = null,
    val lensProfile: String = "", val cameraProfile: String = "",
    val exposureTime: String = "", val iso: Int? = null,
) {
    fun validated(): PhotoMetadata {
        require((latitude == null) == (longitude == null)) { "Enter both latitude and longitude, or leave both blank" }
        require(latitude == null || latitude.isFinite() && latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude == null || longitude.isFinite() && longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
        require(focalLength == null || focalLength.isFinite() && focalLength > 0 && focalLength <= 10000) { "Enter a valid focal length" }
        require(aperture == null || aperture.isFinite() && aperture > 0 && aperture <= 1000) { "Enter a valid aperture" }
        if (capturedAt.isNotBlank()) {
            require(Regex("\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(capturedAt)) { "Use YYYY:MM:DD HH:MM:SS for capture time" }
            java.time.LocalDateTime.parse(capturedAt, java.time.format.DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(java.time.format.ResolverStyle.STRICT))
        }
        return copy(caption = caption.trim().take(4000), copyright = copyright.trim().take(1000),
            keywords = keywords.map { it.trim().take(100) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(100))
    }
    fun matches(query: String): Boolean = listOf(caption, copyright, keywords.joinToString(" "), capturedAt, cameraMake, cameraModel, lens,
        latitude?.toString().orEmpty(), longitude?.toString().orEmpty(), focalLength?.toString().orEmpty(), aperture?.toString().orEmpty(), exposureTime,iso?.toString().orEmpty()).any { it.contains(query, true) }
    fun toJson(): JSONObject = JSONObject().apply {
        put("caption", caption); put("copyright", copyright); put("keywords", JSONArray(keywords))
        put("latitude", latitude); put("longitude", longitude); put("capturedAt", capturedAt)
        put("cameraMake", cameraMake); put("cameraModel", cameraModel); put("lens", lens)
        put("focalLength", focalLength); put("aperture", aperture)
        put("lensProfile", lensProfile); put("cameraProfile", cameraProfile)
        put("exposureTime", exposureTime);put("iso",iso)
    }
    companion object {
        fun fromJson(json: JSONObject) = PhotoMetadata(
            caption = json.optString("caption"), copyright = json.optString("copyright"),
            keywords = json.optJSONArray("keywords")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            latitude = json.numberOrNull("latitude"), longitude = json.numberOrNull("longitude"),
            capturedAt = json.optString("capturedAt"), cameraMake = json.optString("cameraMake"), cameraModel = json.optString("cameraModel"),
            lens = json.optString("lens"), focalLength = json.numberOrNull("focalLength"), aperture = json.numberOrNull("aperture"),
            lensProfile = json.optString("lensProfile"), cameraProfile = json.optString("cameraProfile"),
            exposureTime=json.optString("exposureTime"),iso=if(json.has("iso")&&!json.isNull("iso"))json.optInt("iso")else null,
        )
    }
}
private fun JSONObject.numberOrNull(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

data class EditVersion(val id: Long, val label: String, val createdAt: Long, val state: DevelopState, val named: Boolean, val metadata: PhotoMetadata? = null)

fun editDescription(before: DevelopState, after: DevelopState): String {
    val a = before.toJson(); val b = after.toJson()
    val fields = b.keys().asSequence().filter { it !in setOf("presetBase", "presetFields") && a.opt(it)?.toString() != b.opt(it)?.toString() }.toList()
    if (fields.isEmpty()) return "Edit"
    return fields.take(3).joinToString(", ") { it.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { c -> c.uppercase() } } + if (fields.size > 3) " +${fields.size - 3}" else ""
}

/** Batches deliberately exclude per-photo geometry and local regions unless the user explicitly opts in. */
fun batchRecipe(target: DevelopState, source: DevelopState, includeLocal: Boolean): DevelopState {
    val fields = allPresetFields - setOf("masks", "healingSpots")
    val look = blendPreset(target, source, 1f, fields).copy(presetId = null, presetBase = null, presetFields = null, defringe = source.defringe)
    return if (includeLocal) look.copy(masks = source.masks, healingSpots = source.healingSpots,
        lensBlur = source.lensBlur, lensBlurFocusX = source.lensBlurFocusX, lensBlurFocusY = source.lensBlurFocusY,
        lensBlurFocusRadius = source.lensBlurFocusRadius, lensBlurAspect = source.lensBlurAspect, lensBlurAngle = source.lensBlurAngle,
        lensBlurSubject = source.lensBlurSubject, lensBlurInvert = source.lensBlurInvert, background = source.background)
    else look.copy(masks = target.masks, healingSpots = target.healingSpots, lensBlur = target.lensBlur,
        lensBlurFocusX = target.lensBlurFocusX, lensBlurFocusY = target.lensBlurFocusY, lensBlurFocusRadius = target.lensBlurFocusRadius,
        lensBlurAspect = target.lensBlurAspect, lensBlurAngle = target.lensBlurAngle,
        lensBlurSubject = target.lensBlurSubject, lensBlurInvert = target.lensBlurInvert, background = target.background)
}
