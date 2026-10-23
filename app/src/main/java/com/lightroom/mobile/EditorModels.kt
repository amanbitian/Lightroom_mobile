package com.lightroom.mobile

import org.json.JSONArray
import org.json.JSONObject

data class PhotoItem(
    val uri: String,
    val name: String,
    val rating: Int = 0,
    val flagged: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
)

data class ColorMix(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
)

data class MaskPoint(
    val x: Float,
    val y: Float,
)

data class HealingSpot(
    val id: String,
    val targetX: Float = .58f,
    val targetY: Float = .5f,
    val sourceX: Float = .38f,
    val sourceY: Float = .5f,
    val radius: Float = .08f,
    val feather: Float = .55f,
    val opacity: Float = 1f,
)

data class LocalMask(
    val id: String,
    val type: String = "Radial",
    val enabled: Boolean = true,
    val centerX: Float = .5f,
    val centerY: Float = .5f,
    val size: Float = .42f,
    val feather: Float = .55f,
    val angle: Float = 0f,
    val invert: Boolean = false,
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val temperature: Float = 0f,
    val saturation: Float = 0f,
    val flow: Float = 1f,
    val points: List<MaskPoint> = emptyList(),
)

val colorMixChannels = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta")

data class DevelopState(
    val exposure: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val curveShadows: Float = 0f,
    val curveMidtones: Float = 0f,
    val curveHighlights: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val shadowHue: Float = 220f,
    val shadowGrade: Float = 0f,
    val midtoneHue: Float = 30f,
    val midtoneGrade: Float = 0f,
    val highlightHue: Float = 45f,
    val highlightGrade: Float = 0f,
    val gradingBalance: Float = 0f,
    val colorMix: Map<String, ColorMix> = emptyMap(),
    val texture: Float = 0f,
    val clarity: Float = 0f,
    val dehaze: Float = 0f,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val sharpen: Float = 40f,
    val noiseReduction: Float = 0f,
    val colorNoise: Float = 25f,
    val rotation: Int = 0,
    val straighten: Float = 0f,
    val flipHorizontal: Boolean = false,
    val cropAspect: String = "Original",
    val cropZoom: Float = 1f,
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f,
    val geometryVertical: Float = 0f,
    val geometryHorizontal: Float = 0f,
    val geometryAspect: Float = 0f,
    val opticsVignette: Float = 0f,
    val defringe: Float = 0f,
    val masks: List<LocalMask> = emptyList(),
    val healingSpots: List<HealingSpot> = emptyList(),
    val profile: String = "Adobe Color",
)

data class Preset(
    val id: String,
    val name: String,
    val category: String,
    val state: DevelopState,
    val builtIn: Boolean = true,
)

val builtInPresets = listOf(
    Preset("clean", "Clean", "Recommended", DevelopState()),
    Preset("adaptive-pop", "Adaptive Pop", "Recommended", DevelopState(exposure = .18f, contrast = 12f, highlights = -26f, shadows = 24f, whites = 8f, blacks = -9f, vibrance = 18f, clarity = 8f)),
    Preset("soft-portrait", "Soft Portrait", "Recommended", DevelopState(exposure = .28f, contrast = -10f, highlights = -30f, shadows = 22f, temperature = 8f, tint = 5f, texture = -18f, clarity = -8f, vibrance = 10f)),
    Preset("golden-hour", "Golden Hour", "Premium", DevelopState(exposure = .2f, contrast = 10f, highlights = -28f, shadows = 16f, temperature = 24f, tint = 6f, vibrance = 20f, dehaze = 5f, vignette = -10f)),
    Preset("cinematic-01", "Cinema 01", "Premium", DevelopState(contrast = 18f, highlights = -35f, shadows = 22f, blacks = -16f, temperature = -5f, tint = 8f, saturation = -12f, clarity = 7f, grain = 18f, vignette = -16f)),
    Preset("cinematic-02", "Cinema 02", "Premium", DevelopState(exposure = -.1f, contrast = 22f, highlights = -24f, shadows = 12f, temperature = 13f, saturation = -18f, dehaze = 12f, grain = 24f)),
    Preset("film-warm", "Film Warm", "Film", DevelopState(contrast = -8f, highlights = -30f, shadows = 28f, blacks = 14f, temperature = 17f, tint = 4f, vibrance = 8f, grain = 28f, vignette = -10f)),
    Preset("film-cool", "Film Cool", "Film", DevelopState(contrast = 5f, highlights = -24f, shadows = 18f, blacks = 8f, temperature = -16f, tint = -3f, saturation = -8f, grain = 22f)),
    Preset("vintage-fade", "Vintage Fade", "Vintage", DevelopState(exposure = .08f, contrast = -20f, highlights = -18f, shadows = 28f, blacks = 24f, temperature = 12f, saturation = -22f, grain = 32f, vignette = -12f)),
    Preset("vintage-matte", "Matte", "Vintage", DevelopState(contrast = -15f, highlights = -22f, shadows = 26f, blacks = 22f, saturation = -10f, clarity = -5f, grain = 14f)),
    Preset("bw-punch", "B&W Punch", "B&W", DevelopState(contrast = 28f, highlights = -20f, shadows = 10f, blacks = -20f, saturation = -100f, texture = 15f, clarity = 14f, grain = 18f)),
    Preset("bw-soft", "B&W Soft", "B&W", DevelopState(exposure = .15f, contrast = -5f, highlights = -24f, shadows = 20f, saturation = -100f, texture = -8f, grain = 12f)),
)

fun blendPreset(base: DevelopState, target: DevelopState, strength: Float): DevelopState {
    val t = strength.coerceIn(0f, 2f)
    fun mix(a: Float, b: Float) = a + (b - a) * t
    return base.copy(
        exposure = mix(base.exposure, target.exposure).coerceIn(-4f, 4f),
        contrast = mix(base.contrast, target.contrast).coerceIn(-100f, 100f),
        highlights = mix(base.highlights, target.highlights).coerceIn(-100f, 100f),
        shadows = mix(base.shadows, target.shadows).coerceIn(-100f, 100f),
        whites = mix(base.whites, target.whites).coerceIn(-100f, 100f),
        blacks = mix(base.blacks, target.blacks).coerceIn(-100f, 100f),
        curveShadows = mix(base.curveShadows, target.curveShadows).coerceIn(-100f, 100f),
        curveMidtones = mix(base.curveMidtones, target.curveMidtones).coerceIn(-100f, 100f),
        curveHighlights = mix(base.curveHighlights, target.curveHighlights).coerceIn(-100f, 100f),
        temperature = mix(base.temperature, target.temperature).coerceIn(-100f, 100f),
        tint = mix(base.tint, target.tint).coerceIn(-100f, 100f),
        vibrance = mix(base.vibrance, target.vibrance).coerceIn(-100f, 100f),
        saturation = mix(base.saturation, target.saturation).coerceIn(-100f, 100f),
        shadowHue = mix(base.shadowHue, target.shadowHue).coerceIn(0f, 360f),
        shadowGrade = mix(base.shadowGrade, target.shadowGrade).coerceIn(0f, 100f),
        midtoneHue = mix(base.midtoneHue, target.midtoneHue).coerceIn(0f, 360f),
        midtoneGrade = mix(base.midtoneGrade, target.midtoneGrade).coerceIn(0f, 100f),
        highlightHue = mix(base.highlightHue, target.highlightHue).coerceIn(0f, 360f),
        highlightGrade = mix(base.highlightGrade, target.highlightGrade).coerceIn(0f, 100f),
        gradingBalance = mix(base.gradingBalance, target.gradingBalance).coerceIn(-100f, 100f),
        colorMix = colorMixChannels.associateWith { channel ->
            val a = base.colorMix[channel] ?: ColorMix()
            val b = target.colorMix[channel] ?: ColorMix()
            ColorMix(
                hue = mix(a.hue, b.hue).coerceIn(-100f, 100f),
                saturation = mix(a.saturation, b.saturation).coerceIn(-100f, 100f),
                luminance = mix(a.luminance, b.luminance).coerceIn(-100f, 100f),
            )
        }.filterValues { it != ColorMix() },
        texture = mix(base.texture, target.texture).coerceIn(-100f, 100f),
        clarity = mix(base.clarity, target.clarity).coerceIn(-100f, 100f),
        dehaze = mix(base.dehaze, target.dehaze).coerceIn(-100f, 100f),
        vignette = mix(base.vignette, target.vignette).coerceIn(-100f, 100f),
        grain = mix(base.grain, target.grain).coerceIn(0f, 100f),
        sharpen = mix(base.sharpen, target.sharpen).coerceIn(0f, 100f),
        noiseReduction = mix(base.noiseReduction, target.noiseReduction).coerceIn(0f, 100f),
        colorNoise = mix(base.colorNoise, target.colorNoise).coerceIn(0f, 100f),
        profile = target.profile,
    )
}

fun DevelopState.toJson() = JSONObject().apply {
    put("exposure", exposure); put("contrast", contrast); put("highlights", highlights); put("shadows", shadows)
    put("whites", whites); put("blacks", blacks); put("curveShadows", curveShadows)
    put("curveMidtones", curveMidtones); put("curveHighlights", curveHighlights)
    put("temperature", temperature); put("tint", tint)
    put("vibrance", vibrance); put("saturation", saturation); put("shadowHue", shadowHue); put("shadowGrade", shadowGrade)
    put("midtoneHue", midtoneHue); put("midtoneGrade", midtoneGrade); put("highlightHue", highlightHue)
    put("highlightGrade", highlightGrade); put("gradingBalance", gradingBalance); put("texture", texture); put("clarity", clarity)
    put("dehaze", dehaze); put("vignette", vignette); put("grain", grain); put("sharpen", sharpen)
    put("noiseReduction", noiseReduction); put("colorNoise", colorNoise); put("rotation", rotation); put("straighten", straighten)
    put("flipHorizontal", flipHorizontal); put("cropAspect", cropAspect); put("cropZoom", cropZoom)
    put("cropOffsetX", cropOffsetX); put("cropOffsetY", cropOffsetY); put("geometryVertical", geometryVertical)
    put("geometryHorizontal", geometryHorizontal); put("geometryAspect", geometryAspect); put("opticsVignette", opticsVignette)
    put("defringe", defringe); put("profile", profile)
    put("masks", JSONArray().apply {
        masks.forEach { mask ->
            put(JSONObject().apply {
                put("id", mask.id); put("type", mask.type); put("enabled", mask.enabled)
                put("centerX", mask.centerX); put("centerY", mask.centerY); put("size", mask.size)
                put("feather", mask.feather); put("angle", mask.angle); put("invert", mask.invert)
                put("exposure", mask.exposure); put("contrast", mask.contrast)
                put("temperature", mask.temperature); put("saturation", mask.saturation)
                put("flow", mask.flow)
                put("points", JSONArray().apply {
                    mask.points.forEach { point -> put(JSONObject().apply { put("x", point.x); put("y", point.y) }) }
                })
            })
        }
    })
    put("healingSpots", JSONArray().apply {
        healingSpots.forEach { spot ->
            put(JSONObject().apply {
                put("id", spot.id); put("targetX", spot.targetX); put("targetY", spot.targetY)
                put("sourceX", spot.sourceX); put("sourceY", spot.sourceY); put("radius", spot.radius)
                put("feather", spot.feather); put("opacity", spot.opacity)
            })
        }
    })
    put("colorMix", JSONObject().apply {
        colorMix.forEach { (channel, adjustment) ->
            put(channel, JSONObject().apply {
                put("hue", adjustment.hue); put("saturation", adjustment.saturation); put("luminance", adjustment.luminance)
            })
        }
    })
}

private fun JSONObject.safeFloat(name: String, default: Float = 0f): Float =
    optDouble(name, default.toDouble()).toFloat().takeIf { it.isFinite() } ?: default

private fun colorMixFromJson(json: JSONObject?): Map<String, ColorMix> {
    if (json == null) return emptyMap()
    return colorMixChannels.mapNotNull { channel ->
        val item = json.optJSONObject(channel) ?: return@mapNotNull null
        val value = ColorMix(item.safeFloat("hue"), item.safeFloat("saturation"), item.safeFloat("luminance"))
        if (value == ColorMix()) null else channel to value
    }.toMap()
}

private fun masksFromJson(json: JSONArray?): List<LocalMask> {
    if (json == null) return emptyList()
    return (0 until minOf(json.length(), 8)).mapNotNull { index ->
        val item = json.optJSONObject(index) ?: return@mapNotNull null
        LocalMask(
            id = item.optString("id").ifBlank { "mask-$index" },
            type = item.optString("type", "Radial").takeIf { it == "Radial" || it == "Linear" || it == "Brush" } ?: "Radial",
            enabled = item.optBoolean("enabled", true),
            centerX = item.safeFloat("centerX", .5f).coerceIn(0f, 1f),
            centerY = item.safeFloat("centerY", .5f).coerceIn(0f, 1f),
            size = item.safeFloat("size", .42f).coerceIn(.05f, 1f),
            feather = item.safeFloat("feather", .55f).coerceIn(.02f, 1f),
            angle = item.safeFloat("angle").coerceIn(-180f, 180f),
            invert = item.optBoolean("invert"),
            exposure = item.safeFloat("exposure").coerceIn(-4f, 4f),
            contrast = item.safeFloat("contrast").coerceIn(-100f, 100f),
            temperature = item.safeFloat("temperature").coerceIn(-100f, 100f),
            saturation = item.safeFloat("saturation").coerceIn(-100f, 100f),
            flow = item.safeFloat("flow", 1f).coerceIn(.05f, 1f),
            points = item.optJSONArray("points")?.let { points ->
                (0 until minOf(points.length(), 128)).mapNotNull { pointIndex ->
                    val point = points.optJSONObject(pointIndex) ?: return@mapNotNull null
                    MaskPoint(point.safeFloat("x", .5f).coerceIn(0f, 1f), point.safeFloat("y", .5f).coerceIn(0f, 1f))
                }
            } ?: emptyList(),
        )
    }
}

private fun healingSpotsFromJson(json: JSONArray?): List<HealingSpot> {
    if (json == null) return emptyList()
    return (0 until minOf(json.length(), 24)).mapNotNull { index ->
        val item = json.optJSONObject(index) ?: return@mapNotNull null
        HealingSpot(
            id = item.optString("id").ifBlank { "spot-$index" },
            targetX = item.safeFloat("targetX", .58f).coerceIn(0f, 1f),
            targetY = item.safeFloat("targetY", .5f).coerceIn(0f, 1f),
            sourceX = item.safeFloat("sourceX", .38f).coerceIn(0f, 1f),
            sourceY = item.safeFloat("sourceY", .5f).coerceIn(0f, 1f),
            radius = item.safeFloat("radius", .08f).coerceIn(.01f, .3f),
            feather = item.safeFloat("feather", .55f).coerceIn(.02f, 1f),
            opacity = item.safeFloat("opacity", 1f).coerceIn(.05f, 1f),
        )
    }
}

fun developStateFromJson(json: JSONObject) = DevelopState(
    exposure = json.safeFloat("exposure"), contrast = json.safeFloat("contrast"),
    highlights = json.safeFloat("highlights"), shadows = json.safeFloat("shadows"),
    whites = json.safeFloat("whites"), blacks = json.safeFloat("blacks"),
    curveShadows = json.safeFloat("curveShadows"), curveMidtones = json.safeFloat("curveMidtones"),
    curveHighlights = json.safeFloat("curveHighlights"),
    temperature = json.safeFloat("temperature"), tint = json.safeFloat("tint"),
    vibrance = json.safeFloat("vibrance"), saturation = json.safeFloat("saturation"),
    shadowHue = json.safeFloat("shadowHue", 220f), shadowGrade = json.safeFloat("shadowGrade"),
    midtoneHue = json.safeFloat("midtoneHue", 30f), midtoneGrade = json.safeFloat("midtoneGrade"),
    highlightHue = json.safeFloat("highlightHue", 45f), highlightGrade = json.safeFloat("highlightGrade"),
    gradingBalance = json.safeFloat("gradingBalance"),
    colorMix = colorMixFromJson(json.optJSONObject("colorMix")),
    texture = json.safeFloat("texture"), clarity = json.safeFloat("clarity"),
    dehaze = json.safeFloat("dehaze"), vignette = json.safeFloat("vignette"),
    grain = json.safeFloat("grain"), sharpen = json.safeFloat("sharpen", 40f),
    noiseReduction = json.safeFloat("noiseReduction"), colorNoise = json.safeFloat("colorNoise", 25f),
    rotation = json.optInt("rotation"), straighten = json.safeFloat("straighten"), flipHorizontal = json.optBoolean("flipHorizontal"),
    cropAspect = json.optString("cropAspect", "Original"), cropZoom = json.safeFloat("cropZoom", 1f).coerceIn(1f, 3f),
    cropOffsetX = json.safeFloat("cropOffsetX").coerceIn(-1f, 1f), cropOffsetY = json.safeFloat("cropOffsetY").coerceIn(-1f, 1f),
    geometryVertical = json.safeFloat("geometryVertical").coerceIn(-100f, 100f),
    geometryHorizontal = json.safeFloat("geometryHorizontal").coerceIn(-100f, 100f),
    geometryAspect = json.safeFloat("geometryAspect").coerceIn(-100f, 100f),
    opticsVignette = json.safeFloat("opticsVignette").coerceIn(-100f, 100f),
    defringe = json.safeFloat("defringe").coerceIn(0f, 100f),
    masks = masksFromJson(json.optJSONArray("masks")),
    healingSpots = healingSpotsFromJson(json.optJSONArray("healingSpots")),
    profile = json.optString("profile", "Adobe Color"),
)
