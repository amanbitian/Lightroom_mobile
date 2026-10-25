package com.lightroom.mobile

import org.json.JSONArray
import org.json.JSONObject

data class PhotoItem(
    val uri: String,
    val name: String,
    val rating: Int = 0,
    val flagged: Boolean = false,
    val rejected: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
)

data class ColorMix(
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
)

/** A single control point on a parametric point curve. x = input, y = output, both 0..1. */
data class CurvePoint(val x: Float, val y: Float)

val toneCurveChannels = listOf("RGB", "Red", "Green", "Blue")

/**
 * Evaluates a point curve at [t] (0..1). Like Lightroom's tone curve it is smooth through its points: a monotone
 * cubic (Fritsch-Carlson), so there is no overshoot or kinking between points. Empty means identity.
 */
fun evaluateCurve(points: List<CurvePoint>, t: Float): Float {
    if (points.isEmpty()) return t.coerceIn(0f, 1f)
    val sorted = if (points.zipWithNext().all { (a, b) -> a.x <= b.x }) points else points.sortedBy { it.x }
    val input = t.coerceIn(0f, 1f)
    if (input <= sorted.first().x) return sorted.first().y.coerceIn(0f, 1f)
    if (input >= sorted.last().x) return sorted.last().y.coerceIn(0f, 1f)
    val n = sorted.size
    if (n == 2) {
        val (a, b) = sorted
        return (a.y + (b.y - a.y) * (input - a.x) / (b.x - a.x).coerceAtLeast(1e-5f)).coerceIn(0f, 1f)
    }
    var k = 0
    while (k < n - 2 && input > sorted[k + 1].x) k++
    fun slope(i: Int) = (sorted[i + 1].y - sorted[i].y) / (sorted[i + 1].x - sorted[i].x).coerceAtLeast(1e-5f)
    fun tangent(i: Int): Float = when (i) {
        0 -> slope(0)
        n - 1 -> slope(n - 2)
        else -> {
            val d0 = slope(i - 1); val d1 = slope(i)
            if (d0 * d1 <= 0f) 0f else {
                // Harmonic mean of the neighbouring slopes keeps the curve monotone wherever the points are.
                val w0 = 2 * (sorted[i + 1].x - sorted[i].x) + (sorted[i].x - sorted[i - 1].x)
                val w1 = (sorted[i + 1].x - sorted[i].x) + 2 * (sorted[i].x - sorted[i - 1].x)
                (w0 + w1) / (w0 / d0 + w1 / d1)
            }
        }
    }
    val a = sorted[k]; val b = sorted[k + 1]
    val h = (b.x - a.x).coerceAtLeast(1e-5f)
    val s = (input - a.x) / h
    val s2 = s * s; val s3 = s2 * s
    val y = (2 * s3 - 3 * s2 + 1) * a.y + (s3 - 2 * s2 + s) * h * tangent(k) + (-2 * s3 + 3 * s2) * b.y + (s3 - s2) * h * tangent(k + 1)
    return y.coerceIn(0f, 1f)
}

/** A curve channel is "active" when it meaningfully departs from the identity line. */
fun List<CurvePoint>.isIdentityCurve(): Boolean =
    isEmpty() || all { kotlin.math.abs(it.y - it.x) < 0.004f }

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
    val contentAware: Boolean = false,
    /**
     * Generative Remove brush: painted strokes (normalized points), each stroke [radius] wide on either side.
     * Empty means the legacy circle at target. Used only when [contentAware].
     */
    val strokes: List<List<MaskPoint>> = emptyList(),
    /** Painted spot (new Generative Remove); false for heal/clone and for older circle-only removals. */
    val brush: Boolean = false,
) {
    val isPainted: Boolean get() = contentAware && brush
}

/** Painted points per Generative Remove spot are capped so recipes stay small. */
const val MAX_REMOVE_POINTS = 600

/** Masks computed by on-device AI rather than drawn: they're segmented on whatever frame is being rendered. */
val aiMaskTypes = setOf("Subject", "Hair", "Skin", "Clothes", "Sky")

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
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val tint: Float = 0f,
    val flow: Float = 1f,
    /** Legacy single brush stroke; new brush masks use [strokes]. */
    val points: List<MaskPoint> = emptyList(),
    /** Brush strokes, each a separate polyline (so separate dabs are never joined). */
    val strokes: List<List<MaskPoint>> = emptyList(),
) {
    /** Every brush stroke, including a legacy [points] stroke. */
    val brushStrokes: List<List<MaskPoint>> get() = strokes.ifEmpty { if (points.isEmpty()) emptyList() else listOf(points) }
}

/** Brush points per mask and per Generative Remove spot are capped so recipes stay small. */
const val MAX_BRUSH_POINTS = 600

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
    val vignetteMidpoint: Float = 50f,
    val vignetteFeather: Float = 50f,
    val vignetteRoundness: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = 25f,
    val grainRoughness: Float = 50f,
    // Camera calibration (Lightroom's primaries): shifts how the red/green/blue primaries render.
    val redHue: Float = 0f,
    val redSaturation: Float = 0f,
    val greenHue: Float = 0f,
    val greenSaturation: Float = 0f,
    val blueHue: Float = 0f,
    val blueSaturation: Float = 0f,
    val shadowTint: Float = 0f,
    // 0 like Lightroom's default for rendered (JPEG/PNG) files; recipes keep whatever value they saved.
    val sharpen: Float = 0f,
    val sharpenRadius: Float = 1f,
    val sharpenDetail: Float = 25f,
    val sharpenMasking: Float = 0f,
    val noiseReduction: Float = 0f,
    val nrDetail: Float = 50f,
    val nrContrast: Float = 0f,
    val colorNoise: Float = 0f,
    val curves: Map<String, List<CurvePoint>> = emptyMap(),
    val bwMix: Map<String, Float> = emptyMap(),
    val rotation: Int = 0,
    val straighten: Float = 0f,
    val flipHorizontal: Boolean = false,
    val cropAspect: String = "Original",
    // Crop rectangle, normalized to the rotated + straightened frame. cropZoom/offset are legacy and migrated on load.
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val cropZoom: Float = 1f,
    val cropOffsetX: Float = 0f,
    val cropOffsetY: Float = 0f,
    val geometryVertical: Float = 0f,
    val geometryHorizontal: Float = 0f,
    val geometryAspect: Float = 0f,
    val opticsVignette: Float = 0f,
    val defringe: Float = 0f,
    val lensDistortion: Float = 0f,
    val lensBlur: Float = 0f,
    val lensBlurFocusX: Float = .5f,
    val lensBlurFocusY: Float = .5f,
    val lensBlurFocusRadius: Float = .3f,
    val background: String = "None",
    val masks: List<LocalMask> = emptyList(),
    val healingSpots: List<HealingSpot> = emptyList(),
    val profile: String = "Adobe Color",
    // The applied preset and the look it was applied on, so tapping it again can take it back off.
    val presetId: String? = null,
    val presetBase: DevelopState? = null,
    /** Fields the applied preset set (kept so it can be removed even if the preset is later deleted). */
    val presetFields: Set<String>? = null,
)

data class ExportOptions(
    val quality: Int = 94,
    val maxSide: Int = 3200,
    val format: String = "JPEG",
)

data class Preset(
    val id: String,
    val name: String,
    val category: String,
    val state: DevelopState,
    val builtIn: Boolean = true,
    /** DevelopState field names this preset defines; null means a complete look that sets every field. */
    val fields: Set<String>? = null,
    /** Absolute white balance (temperature, tint) that Lightroom only applies to RAW files. */
    val rawWhiteBalance: Pair<Float, Float>? = null,
) {
    /** The look and defined fields to apply to a photo; RAW-only white balance joins in for RAW files. */
    fun lookFor(isRaw: Boolean): Pair<DevelopState, Set<String>?> {
        val wb = rawWhiteBalance
        if (!isRaw || wb == null || state.temperature != 0f || fields?.contains("temperature") == true) return state to fields
        return state.copy(temperature = wb.first, tint = wb.second) to fields?.plus(setOf("temperature", "tint"))
    }
}

fun PhotoItem.isRaw(): Boolean = name.substringAfterLast('.', "").lowercase() in RawDecoder.rawExtensions

/** One-tap looks in the Quick panel: they behave like presets but only touch the settings they change. */
// Lazy: field detection goes through org.json, which plain JVM unit tests can't initialise eagerly.
val quickLooks: List<Preset> by lazy { listOf(
    "Enhance" to DevelopState(exposure = .1f, contrast = 12f, highlights = -24f, shadows = 22f, whites = 8f, blacks = -8f, vibrance = 16f, clarity = 8f),
    "Vivid" to DevelopState(contrast = 16f, vibrance = 28f, saturation = 8f, clarity = 10f, dehaze = 8f),
    "Portrait" to DevelopState(exposure = .18f, contrast = -6f, highlights = -26f, shadows = 20f, temperature = 6f, texture = -16f, clarity = -6f, vibrance = 10f),
    "Warm" to DevelopState(temperature = 22f, tint = 4f, vibrance = 12f, highlights = -18f, vignette = -8f),
    "Cool" to DevelopState(temperature = -20f, tint = -4f, contrast = 8f, clarity = 6f),
    "Matte" to DevelopState(contrast = -16f, highlights = -20f, shadows = 24f, blacks = 22f, saturation = -10f, grain = 14f),
    "HDR" to DevelopState(contrast = 10f, highlights = -45f, shadows = 42f, whites = 12f, blacks = -10f, clarity = 16f, dehaze = 14f, vibrance = 14f),
    "B&W" to DevelopState(contrast = 20f, highlights = -18f, blacks = -16f, texture = 12f, clarity = 10f, profile = "Monochrome"),
).map { (name, look) -> Preset("quick-${name.lowercase()}", name, "Quick", look, builtIn = true, fields = look.changedFields() intersect allPresetFields) } }

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

/** Setting groups a saved preset can include, as [blendPreset] field names (mirrors Lightroom's New Preset dialog). */
val presetGroups: List<Pair<String, Set<String>>> = listOf(
    "Light" to setOf("exposure", "contrast", "highlights", "shadows", "whites", "blacks"),
    "Color" to setOf("temperature", "tint", "vibrance", "saturation", "profile"),
    "Tone curve" to setOf("curves", "curveShadows", "curveMidtones", "curveHighlights"),
    "Color mixer" to setOf("colorMix", "bwMix"),
    "Color grading" to setOf("shadowHue", "shadowGrade", "midtoneHue", "midtoneGrade", "highlightHue", "highlightGrade", "gradingBalance"),
    "Effects" to setOf("texture", "clarity", "dehaze", "vignette", "vignetteMidpoint", "vignetteFeather", "vignetteRoundness", "grain", "grainSize", "grainRoughness"),
    "Detail" to setOf("sharpen", "sharpenRadius", "sharpenDetail", "sharpenMasking", "noiseReduction", "nrDetail", "nrContrast", "colorNoise"),
    "Calibration" to setOf("redHue", "redSaturation", "greenHue", "greenSaturation", "blueHue", "blueSaturation", "shadowTint"),
    "Optics" to setOf("opticsVignette"),
    "Masks" to setOf("masks"),
)

/** Every field a preset can touch: what removing a complete look restores. */
val allPresetFields: Set<String> = presetGroups.flatMap { it.second }.toSet()

/** Masks a preset brings with it carry this id prefix, so swapping presets replaces them rather than piling up. */
const val PRESET_MASK_PREFIX = "preset-"

fun blendPreset(base: DevelopState, target: DevelopState, strength: Float, fields: Set<String>? = null): DevelopState {
    val t = strength.coerceIn(0f, 2f)
    // Fields the preset doesn't define keep the photo's current value (a white-balance preset must not reset exposure).
    fun has(name: String) = fields == null || name in fields
    fun mix(name: String, a: Float, b: Float, range: ClosedFloatingPointRange<Float>) =
        if (has(name)) (a + (b - a) * t).coerceIn(range.start, range.endInclusive) else a
    // Hues blend along the shorter arc, so 220 -> 30 passes through magenta rather than green.
    fun mixHue(name: String, a: Float, b: Float): Float {
        if (!has(name)) return a
        val delta = ((b - a) % 360f + 540f) % 360f - 180f
        return ((a + delta * t) % 360f + 360f) % 360f
    }
    val full = -100f..100f; val unit = 0f..100f
    return base.copy(
        exposure = mix("exposure", base.exposure, target.exposure, -4f..4f),
        contrast = mix("contrast", base.contrast, target.contrast, full),
        highlights = mix("highlights", base.highlights, target.highlights, full),
        shadows = mix("shadows", base.shadows, target.shadows, full),
        whites = mix("whites", base.whites, target.whites, full),
        blacks = mix("blacks", base.blacks, target.blacks, full),
        curveShadows = mix("curveShadows", base.curveShadows, target.curveShadows, full),
        curveMidtones = mix("curveMidtones", base.curveMidtones, target.curveMidtones, full),
        curveHighlights = mix("curveHighlights", base.curveHighlights, target.curveHighlights, full),
        temperature = mix("temperature", base.temperature, target.temperature, full),
        tint = mix("tint", base.tint, target.tint, full),
        vibrance = mix("vibrance", base.vibrance, target.vibrance, full),
        saturation = mix("saturation", base.saturation, target.saturation, full),
        shadowHue = mixHue("shadowHue", base.shadowHue, target.shadowHue),
        shadowGrade = mix("shadowGrade", base.shadowGrade, target.shadowGrade, unit),
        midtoneHue = mixHue("midtoneHue", base.midtoneHue, target.midtoneHue),
        midtoneGrade = mix("midtoneGrade", base.midtoneGrade, target.midtoneGrade, unit),
        highlightHue = mixHue("highlightHue", base.highlightHue, target.highlightHue),
        highlightGrade = mix("highlightGrade", base.highlightGrade, target.highlightGrade, unit),
        gradingBalance = mix("gradingBalance", base.gradingBalance, target.gradingBalance, full),
        colorMix = if (!has("colorMix")) base.colorMix else colorMixChannels.associateWith { channel ->
            val a = base.colorMix[channel] ?: ColorMix()
            val b = target.colorMix[channel] ?: ColorMix()
            ColorMix(
                hue = (a.hue + (b.hue - a.hue) * t).coerceIn(-100f, 100f),
                saturation = (a.saturation + (b.saturation - a.saturation) * t).coerceIn(-100f, 100f),
                luminance = (a.luminance + (b.luminance - a.luminance) * t).coerceIn(-100f, 100f),
            )
        }.filterValues { it != ColorMix() },
        texture = mix("texture", base.texture, target.texture, full),
        clarity = mix("clarity", base.clarity, target.clarity, full),
        dehaze = mix("dehaze", base.dehaze, target.dehaze, full),
        vignette = mix("vignette", base.vignette, target.vignette, full),
        grain = mix("grain", base.grain, target.grain, unit),
        vignetteMidpoint = mix("vignetteMidpoint", base.vignetteMidpoint, target.vignetteMidpoint, unit),
        vignetteFeather = mix("vignetteFeather", base.vignetteFeather, target.vignetteFeather, unit),
        vignetteRoundness = mix("vignetteRoundness", base.vignetteRoundness, target.vignetteRoundness, full),
        grainSize = mix("grainSize", base.grainSize, target.grainSize, unit),
        grainRoughness = mix("grainRoughness", base.grainRoughness, target.grainRoughness, unit),
        redHue = mix("redHue", base.redHue, target.redHue, full),
        redSaturation = mix("redSaturation", base.redSaturation, target.redSaturation, full),
        greenHue = mix("greenHue", base.greenHue, target.greenHue, full),
        greenSaturation = mix("greenSaturation", base.greenSaturation, target.greenSaturation, full),
        blueHue = mix("blueHue", base.blueHue, target.blueHue, full),
        blueSaturation = mix("blueSaturation", base.blueSaturation, target.blueSaturation, full),
        shadowTint = mix("shadowTint", base.shadowTint, target.shadowTint, full),
        opticsVignette = mix("opticsVignette", base.opticsVignette, target.opticsVignette, full),
        // The photo's own masks stay; masks a preset brought are swapped for the target's preset masks.
        // At most 8 masks in total (the editor's limit; recipes keep 8), the photo's own first.
        masks = if (has("masks") && t > 0f) (base.masks.filterNot { it.id.startsWith(PRESET_MASK_PREFIX) } + target.masks.filter { it.id.startsWith(PRESET_MASK_PREFIX) }).take(8) else base.masks,
        sharpen = mix("sharpen", base.sharpen, target.sharpen, unit),
        sharpenRadius = mix("sharpenRadius", base.sharpenRadius, target.sharpenRadius, 0.5f..3f),
        sharpenDetail = mix("sharpenDetail", base.sharpenDetail, target.sharpenDetail, unit),
        sharpenMasking = mix("sharpenMasking", base.sharpenMasking, target.sharpenMasking, unit),
        noiseReduction = mix("noiseReduction", base.noiseReduction, target.noiseReduction, unit),
        nrDetail = mix("nrDetail", base.nrDetail, target.nrDetail, unit),
        nrContrast = mix("nrContrast", base.nrContrast, target.nrContrast, unit),
        colorNoise = mix("colorNoise", base.colorNoise, target.colorNoise, unit),
        // Point curves and the B&W mix carry over fully when the preset defines them (strength does not scale them).
        // Defined means owned: an empty target curve/mix clears it (which is how removing a preset restores "none").
        curves = if (has("curves") && t > 0f) target.curves else base.curves,
        bwMix = if (has("bwMix") && t > 0f) target.bwMix else base.bwMix,
        // A complete look only switches profile when it asks for a non-default one; never at 0% strength.
        profile = when {
            t <= 0f -> base.profile
            fields != null -> if ("profile" in fields) target.profile else base.profile
            target.profile != DevelopState().profile -> target.profile
            else -> base.profile
        },
    )
}

/** Just the framing (rotation, flip, straighten, crop, perspective): what "Reset adjustments" keeps. */
fun DevelopState.geometryOnly(): DevelopState = DevelopState(
    rotation = rotation, straighten = straighten, flipHorizontal = flipHorizontal, cropAspect = cropAspect,
    cropLeft = cropLeft, cropTop = cropTop, cropRight = cropRight, cropBottom = cropBottom,
    geometryVertical = geometryVertical, geometryHorizontal = geometryHorizontal, geometryAspect = geometryAspect,
)

/** Field names (as used by [blendPreset]) where this state differs from the defaults. */
fun DevelopState.changedFields(): Set<String> {
    val mine = toJson(); val defaults = DevelopState().toJson()
    return mine.keys().asSequence().filter { key -> mine.opt(key)?.toString() != defaults.opt(key)?.toString() }.toSet()
}

fun DevelopState.toJson(): JSONObject = JSONObject().apply {
    put("exposure", exposure); put("contrast", contrast); put("highlights", highlights); put("shadows", shadows)
    put("whites", whites); put("blacks", blacks); put("curveShadows", curveShadows)
    put("curveMidtones", curveMidtones); put("curveHighlights", curveHighlights)
    put("temperature", temperature); put("tint", tint)
    put("vibrance", vibrance); put("saturation", saturation); put("shadowHue", shadowHue); put("shadowGrade", shadowGrade)
    put("midtoneHue", midtoneHue); put("midtoneGrade", midtoneGrade); put("highlightHue", highlightHue)
    put("highlightGrade", highlightGrade); put("gradingBalance", gradingBalance); put("texture", texture); put("clarity", clarity)
    put("dehaze", dehaze); put("vignette", vignette); put("grain", grain); put("sharpen", sharpen)
    put("vignetteMidpoint", vignetteMidpoint); put("vignetteFeather", vignetteFeather); put("vignetteRoundness", vignetteRoundness)
    put("grainSize", grainSize); put("grainRoughness", grainRoughness)
    put("redHue", redHue); put("redSaturation", redSaturation); put("greenHue", greenHue); put("greenSaturation", greenSaturation)
    put("blueHue", blueHue); put("blueSaturation", blueSaturation); put("shadowTint", shadowTint)
    put("cropLeft", cropLeft); put("cropTop", cropTop); put("cropRight", cropRight); put("cropBottom", cropBottom)
    presetId?.let { put("presetId", it) }; presetBase?.let { put("presetBase", it.toJson()) }
    presetFields?.let { put("presetFields", JSONArray(it.toList())) }
    put("sharpenRadius", sharpenRadius); put("sharpenDetail", sharpenDetail); put("sharpenMasking", sharpenMasking)
    put("noiseReduction", noiseReduction); put("nrDetail", nrDetail); put("nrContrast", nrContrast)
    put("colorNoise", colorNoise); put("rotation", rotation); put("straighten", straighten); put("v", 3)
    put("curves", JSONObject().apply {
        curves.forEach { (channel, points) ->
            if (points.isNotEmpty()) put(channel, JSONArray().apply {
                points.forEach { point -> put(JSONObject().apply { put("x", point.x); put("y", point.y) }) }
            })
        }
    })
    put("bwMix", JSONObject().apply { bwMix.forEach { (channel, value) -> put(channel, value) } })
    put("flipHorizontal", flipHorizontal); put("cropAspect", cropAspect); put("cropZoom", cropZoom)
    put("cropOffsetX", cropOffsetX); put("cropOffsetY", cropOffsetY); put("geometryVertical", geometryVertical)
    put("geometryHorizontal", geometryHorizontal); put("geometryAspect", geometryAspect); put("opticsVignette", opticsVignette)
    put("defringe", defringe); put("lensDistortion", lensDistortion)
    put("lensBlur", lensBlur); put("lensBlurFocusX", lensBlurFocusX); put("lensBlurFocusY", lensBlurFocusY)
    put("lensBlurFocusRadius", lensBlurFocusRadius); put("background", background); put("profile", profile)
    put("masks", JSONArray().apply {
        masks.forEach { mask ->
            put(JSONObject().apply {
                put("id", mask.id); put("type", mask.type); put("enabled", mask.enabled)
                put("centerX", mask.centerX); put("centerY", mask.centerY); put("size", mask.size)
                put("feather", mask.feather); put("angle", mask.angle); put("invert", mask.invert)
                put("exposure", mask.exposure); put("contrast", mask.contrast)
                put("temperature", mask.temperature); put("saturation", mask.saturation)
                put("highlights", mask.highlights); put("shadows", mask.shadows); put("tint", mask.tint)
                put("flow", mask.flow)
                if (mask.strokes.isNotEmpty()) put("strokes", JSONArray().apply {
                    mask.strokes.forEach { stroke -> put(JSONArray().apply { stroke.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) } }) }
                })
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
                put("feather", spot.feather); put("opacity", spot.opacity); put("contentAware", spot.contentAware)
                if (spot.brush) put("brush", true)
                if (spot.strokes.isNotEmpty()) put("strokes", JSONArray().apply {
                    spot.strokes.forEach { stroke -> put(JSONArray().apply { stroke.forEach { p -> put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) } }) }
                })
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
            type = item.optString("type", "Radial").takeIf { it in setOf("Radial", "Linear", "Brush") || it in aiMaskTypes } ?: "Radial",
            enabled = item.optBoolean("enabled", true),
            centerX = item.safeFloat("centerX", .5f).coerceIn(0f, 1f),
            centerY = item.safeFloat("centerY", .5f).coerceIn(0f, 1f),
            size = item.safeFloat("size", .42f).coerceIn(.005f, 1f),
            feather = item.safeFloat("feather", .55f).coerceIn(.02f, 1f),
            angle = item.safeFloat("angle").coerceIn(-180f, 180f),
            invert = item.optBoolean("invert"),
            exposure = item.safeFloat("exposure").coerceIn(-4f, 4f),
            contrast = item.safeFloat("contrast").coerceIn(-100f, 100f),
            temperature = item.safeFloat("temperature").coerceIn(-100f, 100f),
            saturation = item.safeFloat("saturation").coerceIn(-100f, 100f),
            highlights = item.safeFloat("highlights").coerceIn(-100f, 100f),
            shadows = item.safeFloat("shadows").coerceIn(-100f, 100f),
            tint = item.safeFloat("tint").coerceIn(-100f, 100f),
            flow = item.safeFloat("flow", 1f).coerceIn(.05f, 1f),
            points = item.optJSONArray("points")?.let { points ->
                (0 until minOf(points.length(), MAX_BRUSH_POINTS)).mapNotNull { pointIndex ->
                    val point = points.optJSONObject(pointIndex) ?: return@mapNotNull null
                    MaskPoint(point.safeFloat("x", .5f).coerceIn(0f, 1f), point.safeFloat("y", .5f).coerceIn(0f, 1f))
                }
            } ?: emptyList(),
            strokes = item.optJSONArray("strokes")?.let { strokes ->
                (0 until strokes.length()).mapNotNull { s -> strokes.optJSONArray(s) }.map { stroke ->
                    (0 until stroke.length()).mapNotNull { i ->
                        stroke.optJSONArray(i)?.let { MaskPoint(it.optDouble(0).toFloat().coerceIn(0f, 1f), it.optDouble(1).toFloat().coerceIn(0f, 1f)) }
                    }
                }.filter { it.isNotEmpty() }
            } ?: emptyList(),
        )
    }
}

private fun curvesFromJson(json: JSONObject?): Map<String, List<CurvePoint>> {
    if (json == null) return emptyMap()
    return toneCurveChannels.mapNotNull { channel ->
        val array = json.optJSONArray(channel) ?: return@mapNotNull null
        val points = (0 until minOf(array.length(), 128)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            CurvePoint(item.safeFloat("x").coerceIn(0f, 1f), item.safeFloat("y").coerceIn(0f, 1f))
        }
        if (points.isEmpty()) null else channel to points
    }.toMap()
}

private fun bwMixFromJson(json: JSONObject?): Map<String, Float> {
    if (json == null) return emptyMap()
    return colorMixChannels.mapNotNull { channel ->
        if (!json.has(channel)) return@mapNotNull null
        val value = json.safeFloat(channel).coerceIn(-100f, 100f)
        if (value == 0f) null else channel to value
    }.toMap()
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
            radius = item.safeFloat("radius", .08f).coerceIn(.003f, .3f),
            feather = item.safeFloat("feather", .55f).coerceIn(.02f, 1f),
            opacity = item.safeFloat("opacity", 1f).coerceIn(.05f, 1f),
            contentAware = item.optBoolean("contentAware"),
            brush = item.optBoolean("brush"),
            strokes = item.optJSONArray("strokes")?.let { strokes ->
                (0 until strokes.length()).mapNotNull { s -> strokes.optJSONArray(s) }.map { stroke ->
                    (0 until stroke.length()).mapNotNull { i ->
                        stroke.optJSONArray(i)?.let { MaskPoint(it.optDouble(0).toFloat().coerceIn(0f, 1f), it.optDouble(1).toFloat().coerceIn(0f, 1f)) }
                    }
                }.filter { it.isNotEmpty() }
            } ?: emptyList(),
        )
    }
}

fun developStateFromJson(json: JSONObject): DevelopState = DevelopState(
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
    grain = json.safeFloat("grain"), sharpen = json.safeFloat("sharpen", if (json.optInt("v") >= 3) 0f else 40f),
    vignetteMidpoint = json.safeFloat("vignetteMidpoint", 50f).coerceIn(0f, 100f),
    vignetteFeather = json.safeFloat("vignetteFeather", 50f).coerceIn(0f, 100f),
    vignetteRoundness = json.safeFloat("vignetteRoundness").coerceIn(-100f, 100f),
    grainSize = json.safeFloat("grainSize", 25f).coerceIn(0f, 100f),
    grainRoughness = json.safeFloat("grainRoughness", 50f).coerceIn(0f, 100f),
    redHue = json.safeFloat("redHue"), redSaturation = json.safeFloat("redSaturation"),
    greenHue = json.safeFloat("greenHue"), greenSaturation = json.safeFloat("greenSaturation"),
    blueHue = json.safeFloat("blueHue"), blueSaturation = json.safeFloat("blueSaturation"),
    shadowTint = json.safeFloat("shadowTint"),
    cropLeft = json.safeFloat("cropLeft", 0f).coerceIn(0f, 1f), cropTop = json.safeFloat("cropTop", 0f).coerceIn(0f, 1f),
    cropRight = json.safeFloat("cropRight", 1f).coerceIn(0f, 1f), cropBottom = json.safeFloat("cropBottom", 1f).coerceIn(0f, 1f),
    sharpenRadius = json.safeFloat("sharpenRadius", 1f).coerceIn(0.5f, 3f),
    sharpenDetail = json.safeFloat("sharpenDetail", 25f).coerceIn(0f, 100f),
    sharpenMasking = json.safeFloat("sharpenMasking").coerceIn(0f, 100f),
    noiseReduction = json.safeFloat("noiseReduction"),
    nrDetail = json.safeFloat("nrDetail", 50f).coerceIn(0f, 100f),
    nrContrast = json.safeFloat("nrContrast").coerceIn(0f, 100f),
    // Colour NR did nothing before recipe v3, when the default was 25; read those old values as off.
    colorNoise = if (json.optInt("v") >= 3) json.safeFloat("colorNoise") else json.safeFloat("colorNoise").takeIf { it != 25f } ?: 0f,
    curves = curvesFromJson(json.optJSONObject("curves")),
    bwMix = bwMixFromJson(json.optJSONObject("bwMix")),
    rotation = json.optInt("rotation"), straighten = json.safeFloat("straighten"), flipHorizontal = json.optBoolean("flipHorizontal"),
    cropAspect = json.optString("cropAspect", "Original"), cropZoom = json.safeFloat("cropZoom", 1f).coerceIn(1f, 3f),
    cropOffsetX = json.safeFloat("cropOffsetX").coerceIn(-1f, 1f), cropOffsetY = json.safeFloat("cropOffsetY").coerceIn(-1f, 1f),
    geometryVertical = json.safeFloat("geometryVertical").coerceIn(-100f, 100f),
    geometryHorizontal = json.safeFloat("geometryHorizontal").coerceIn(-100f, 100f),
    geometryAspect = json.safeFloat("geometryAspect").coerceIn(-100f, 100f),
    opticsVignette = json.safeFloat("opticsVignette").coerceIn(-100f, 100f),
    defringe = json.safeFloat("defringe").coerceIn(0f, 100f),
    lensDistortion = json.safeFloat("lensDistortion").coerceIn(-100f, 100f),
    lensBlur = json.safeFloat("lensBlur").coerceIn(0f, 100f),
    lensBlurFocusX = json.safeFloat("lensBlurFocusX", .5f).coerceIn(0f, 1f),
    lensBlurFocusY = json.safeFloat("lensBlurFocusY", .5f).coerceIn(0f, 1f),
    lensBlurFocusRadius = json.safeFloat("lensBlurFocusRadius", .3f).coerceIn(.05f, 1f),
    background = json.optString("background", "None"),
    masks = masksFromJson(json.optJSONArray("masks")),
    healingSpots = healingSpotsFromJson(json.optJSONArray("healingSpots")),
    profile = json.optString("profile", "Adobe Color"),
    presetId = json.optString("presetId").ifBlank { null },
    presetBase = json.optJSONObject("presetBase")?.let { developStateFromJson(it) },
    presetFields = json.optJSONArray("presetFields")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
).let { state -> if (json.has("cropLeft")) state else state.withLegacyCrop() }
    // Before v3, flip was applied before the 90° turns; flipping after them shows the same picture with the turn reversed.
    .let { state -> if (json.optInt("v") < 3 && state.flipHorizontal && state.rotation % 180 != 0) state.copy(rotation = (360 - state.rotation) % 360) else state }

/**
 * Zoom needed after straightening by [degrees] so the rotated image still covers a frame of [aspect] (w/h),
 * i.e. no empty corners. Shared by the editor preview and the exporter.
 */
fun straightenScale(degrees: Float, aspect: Float): Float {
    val a = Math.toRadians(kotlin.math.abs(degrees).toDouble())
    return (kotlin.math.cos(a) + kotlin.math.sin(a) * maxOf(aspect, 1f / aspect)).toFloat()
}

/** Pixel aspect (width / height) a crop ratio name locks to, or null for Free. "Original" uses the frame's own aspect. */
fun cropRatio(name: String, frameAspect: Float): Float? = when (name) {
    "Free" -> null
    "1:1" -> 1f
    "4:5" -> .8f
    "5:4" -> 1.25f
    "3:2" -> 1.5f
    "2:3" -> 2f / 3f
    "16:9" -> 16f / 9f
    "9:16" -> 9f / 16f
    else -> frameAspect
}

/** The crop rectangle actually used, fitted to the locked ratio (legacy recipes only stored a ratio name). */
fun DevelopState.effectiveCrop(frameAspect: Float): FloatArray {
    var l = cropLeft; var t = cropTop; var r = cropRight.coerceAtLeast(l + .02f); var b = cropBottom.coerceAtLeast(t + .02f)
    val ratio = cropRatio(cropAspect, frameAspect) ?: return floatArrayOf(l, t, r, b)
    // Normalized width/height that gives the ratio in pixels.
    val target = ratio / frameAspect
    val w = r - l; val h = b - t
    if (kotlin.math.abs(w / h - target) > .01f) {
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val nw: Float; val nh: Float
        if (w / h > target) { nh = h; nw = h * target } else { nw = w; nh = w / target }
        l = (cx - nw / 2f).coerceIn(0f, 1f - nw); t = (cy - nh / 2f).coerceIn(0f, 1f - nh); r = l + nw; b = t + nh
    }
    return floatArrayOf(l, t, r, b)
}

/** Older recipes stored a centred zoom + pan instead of a rectangle; convert them once on load. */
private fun DevelopState.withLegacyCrop(): DevelopState {
    // Old recipes cropped in the original orientation and rotated afterwards; the crop now lives in the rotated
    // frame, so the rectangle (and a fixed ratio's orientation) is turned to match.
    val quarter = ((rotation % 360) + 360) % 360
    val turned = quarter == 90 || quarter == 270
    val aspect = if (!turned) cropAspect else when (cropAspect) {
        "4:5" -> "5:4"; "5:4" -> "4:5"; "3:2" -> "2:3"; "2:3" -> "3:2"; "16:9" -> "9:16"; "9:16" -> "16:9"; else -> cropAspect
    }
    if (cropZoom <= 1.001f && cropOffsetX == 0f && cropOffsetY == 0f) return copy(cropAspect = aspect)
    val zoom = cropZoom.coerceIn(1f, 3f)
    val w = 1f / zoom; val h = 1f / zoom
    val left = (1f - w) / 2f * (1f + cropOffsetX.coerceIn(-1f, 1f))
    val top = (1f - h) / 2f * (1f + cropOffsetY.coerceIn(-1f, 1f))
    fun orient(x: Float, y: Float): Pair<Float, Float> {
        val fx = if (flipHorizontal) 1f - x else x
        return when (quarter) { 90 -> (1f - y) to fx; 180 -> (1f - fx) to (1f - y); 270 -> y to (1f - fx); else -> fx to y }
    }
    val corners = listOf(orient(left, top), orient(left + w, top + h))
    return copy(
        cropAspect = aspect,
        cropLeft = corners.minOf { it.first }, cropTop = corners.minOf { it.second },
        cropRight = corners.maxOf { it.first }, cropBottom = corners.maxOf { it.second },
        cropZoom = 1f, cropOffsetX = 0f, cropOffsetY = 0f,
    )
}
