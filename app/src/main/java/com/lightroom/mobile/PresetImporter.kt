package com.lightroom.mobile

import kotlin.math.abs

/**
 * Imports Adobe Lightroom / Camera Raw presets and maps their develop settings onto this app's
 * [DevelopState]. Supports modern `.xmp` presets (crs: attributes or elements) and legacy
 * `.lrtemplate` files (a Lua table). Settings with no equivalent here are ignored. The mapping is a
 * close approximation, not a byte-exact reproduction of Adobe's rendering.
 */
object PresetImporter {

    /**
     * A parsed preset plus the [DevelopState] field names it actually defines (see [blendPreset]).
     * [rawWhiteBalance] is an absolute Kelvin/tint white balance; like Lightroom, it only applies to RAW files.
     */
    data class Parsed(val state: DevelopState, val fields: Set<String>, val rawWhiteBalance: Pair<Float, Float>? = null)

    fun parse(text: String): Parsed? = runCatching {
        val looksXmp = text.contains("crs:") || text.contains("xmpmeta") || text.contains("<rdf:")
        val state = if (looksXmp) {
            buildState(
                getFloat = { key -> xmpFloat(text, key) },
                getBool = { key -> xmpValue(text, key).equals("True", ignoreCase = true) },
                getCurve = { key -> xmpCurve(text, key) },
                getString = { key -> xmpValue(text, key) },
                localMasks = { xmpLocalMasks(text) },
            )
        } else {
            buildState(
                getFloat = { key -> luaFloat(text, key) },
                getBool = { key -> Regex("$key\\s*=\\s*true").containsMatchIn(text) },
                getCurve = { key -> luaCurve(text, key) },
                getString = { key -> Regex("\\b$key\\s*=\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1).orEmpty() },
                localMasks = { emptyList() },
            )
        }
        state.takeIf { it.fields.isNotEmpty() }
    }.getOrNull()

    private fun buildState(
        getFloat: (String) -> Float?,
        getBool: (String) -> Boolean,
        getCurve: (String) -> List<CurvePoint>?,
        getString: (String) -> String,
        localMasks: () -> List<LocalMask>,
    ): Parsed {
        var s = DevelopState()
        val fields = mutableSetOf<String>()
        // Records each field the preset sets, so applying it leaves every other setting alone.
        fun put(vararg names: String, update: (DevelopState) -> DevelopState) { s = update(s); fields += names }
        fun float(key: String, field: String, range: ClosedFloatingPointRange<Float>, update: DevelopState.(Float) -> DevelopState) {
            getFloat(key)?.let { v -> put(field) { it.update(v.coerceIn(range.start, range.endInclusive)) } }
        }
        val full = -100f..100f; val unit = 0f..100f
        float("Exposure2012", "exposure", -4f..4f) { copy(exposure = it) }
        float("Contrast2012", "contrast", full) { copy(contrast = it) }
        float("Highlights2012", "highlights", full) { copy(highlights = it) }
        float("Shadows2012", "shadows", full) { copy(shadows = it) }
        float("Whites2012", "whites", full) { copy(whites = it) }
        float("Blacks2012", "blacks", full) { copy(blacks = it) }

        // White balance: Incremental values are relative and apply to every photo. Absolute Kelvin (WhiteBalance=Custom)
        // is RAW-only in Lightroom, so it is kept aside and applied only to RAW files (see Preset.lookFor).
        getFloat("IncrementalTemperature")?.let { v -> put("temperature") { it.copy(temperature = v.coerceIn(-100f, 100f)) } }
        getFloat("IncrementalTint")?.let { v -> put("tint") { it.copy(tint = v.coerceIn(-100f, 100f)) } }
        val kelvin = getFloat("Temperature")?.takeIf { it > 1000f && getString("WhiteBalance").equals("Custom", ignoreCase = true) }
        val rawWhiteBalance = kelvin?.let { k ->
            ((k - 5500f) / 50f).coerceIn(-100f, 100f) to (getFloat("Tint") ?: 0f).coerceIn(-100f, 100f)
        }

        float("Vibrance", "vibrance", full) { copy(vibrance = it) }
        float("Saturation", "saturation", full) { copy(saturation = it) }
        float("Clarity2012", "clarity", full) { copy(clarity = it) }
        float("Dehaze", "dehaze", full) { copy(dehaze = it) }
        float("Texture", "texture", full) { copy(texture = it) }
        float("Sharpness", "sharpen", unit) { copy(sharpen = it) }
        float("SharpenRadius", "sharpenRadius", 0.5f..3f) { copy(sharpenRadius = it) }
        float("SharpenDetail", "sharpenDetail", unit) { copy(sharpenDetail = it) }
        float("SharpenEdgeMasking", "sharpenMasking", unit) { copy(sharpenMasking = it) }
        float("LuminanceSmoothing", "noiseReduction", unit) { copy(noiseReduction = it) }
        float("LuminanceNoiseReductionDetail", "nrDetail", unit) { copy(nrDetail = it) }
        float("LuminanceNoiseReductionContrast", "nrContrast", unit) { copy(nrContrast = it) }
        float("ColorNoiseReduction", "colorNoise", unit) { copy(colorNoise = it) }
        float("PostCropVignetteAmount", "vignette", full) { copy(vignette = it) }
        float("PostCropVignetteMidpoint", "vignetteMidpoint", unit) { copy(vignetteMidpoint = it) }
        float("PostCropVignetteFeather", "vignetteFeather", unit) { copy(vignetteFeather = it) }
        float("PostCropVignetteRoundness", "vignetteRoundness", full) { copy(vignetteRoundness = it) }
        float("VignetteAmount", "opticsVignette", full) { copy(opticsVignette = it) }
        float("GrainAmount", "grain", unit) { copy(grain = it) }
        float("GrainSize", "grainSize", unit) { copy(grainSize = it) }
        float("GrainFrequency", "grainRoughness", unit) { copy(grainRoughness = it) }
        // Camera calibration primaries: most commercial packs get their colour signature here.
        float("RedHue", "redHue", full) { copy(redHue = it) }
        float("RedSaturation", "redSaturation", full) { copy(redSaturation = it) }
        float("GreenHue", "greenHue", full) { copy(greenHue = it) }
        float("GreenSaturation", "greenSaturation", full) { copy(greenSaturation = it) }
        float("BlueHue", "blueHue", full) { copy(blueHue = it) }
        float("BlueSaturation", "blueSaturation", full) { copy(blueSaturation = it) }
        float("ShadowTint", "shadowTint", full) { copy(shadowTint = it) }

        if (getBool("ConvertToGrayscale")) put("profile") { it.copy(profile = "Monochrome") }
        else lookProfile(getString("Name").ifBlank { getString("Look") })?.let { profile -> put("profile") { it.copy(profile = profile) } }
        val grayMix = colorMixChannels.mapNotNull { ch -> getFloat("GrayMixer$ch")?.let { ch to it.coerceIn(-100f, 100f) } }.toMap()
        if (grayMix.isNotEmpty()) put("bwMix") { it.copy(bwMix = grayMix.filterValues { v -> v != 0f }) }

        // HSL color mixer (Adobe channel names match ours exactly). Any HSL key means the preset owns the mixer.
        var hasMix = false
        val mix = colorMixChannels.mapNotNull { ch ->
            val hue = getFloat("HueAdjustment$ch"); val sat = getFloat("SaturationAdjustment$ch"); val lum = getFloat("LuminanceAdjustment$ch")
            if (hue != null || sat != null || lum != null) hasMix = true
            val cm = ColorMix((hue ?: 0f).coerceIn(-100f, 100f), (sat ?: 0f).coerceIn(-100f, 100f), (lum ?: 0f).coerceIn(-100f, 100f))
            if (cm == ColorMix()) null else ch to cm
        }.toMap()
        if (hasMix) put("colorMix") { it.copy(colorMix = mix) }

        // Color grading (new keys), falling back to legacy Split Toning.
        (getFloat("ColorGradeShadowHue") ?: getFloat("SplitToningShadowHue"))?.let { v -> put("shadowHue") { it.copy(shadowHue = v.coerceIn(0f, 360f)) } }
        (getFloat("ColorGradeShadowSat") ?: getFloat("SplitToningShadowSaturation"))?.let { v -> put("shadowGrade") { it.copy(shadowGrade = v.coerceIn(0f, 100f)) } }
        float("ColorGradeMidtoneHue", "midtoneHue", 0f..360f) { copy(midtoneHue = it) }
        float("ColorGradeMidtoneSat", "midtoneGrade", unit) { copy(midtoneGrade = it) }
        (getFloat("ColorGradeHighlightHue") ?: getFloat("SplitToningHighlightHue"))?.let { v -> put("highlightHue") { it.copy(highlightHue = v.coerceIn(0f, 360f)) } }
        (getFloat("ColorGradeHighlightSat") ?: getFloat("SplitToningHighlightSaturation"))?.let { v -> put("highlightGrade") { it.copy(highlightGrade = v.coerceIn(0f, 100f)) } }
        (getFloat("ColorGradeBlending") ?: getFloat("SplitToningBalance"))?.let { v -> put("gradingBalance") { it.copy(gradingBalance = v.coerceIn(-100f, 100f)) } }

        // Tone curves (point curves, 0..255 → 0..1). Lightroom applies the parametric curve first, then the point
        // curve; both are folded into one sampled RGB curve.
        val curves = mutableMapOf<String, List<CurvePoint>>()
        val point = getCurve("ToneCurvePV2012") ?: emptyList()
        val parametric = parametricCurve(
            getFloat("ParametricShadows") ?: 0f, getFloat("ParametricDarks") ?: 0f,
            getFloat("ParametricLights") ?: 0f, getFloat("ParametricHighlights") ?: 0f,
            getFloat("ParametricShadowSplit") ?: 25f, getFloat("ParametricMidtoneSplit") ?: 50f, getFloat("ParametricHighlightSplit") ?: 75f,
        )
        val master = if (parametric == null) point else List(65) { i ->
            val x = i / 64f
            CurvePoint(x, evaluateCurve(point, parametric(x)))
        }
        if (!master.isIdentityCurve()) curves["RGB"] = master
        getCurve("ToneCurvePV2012Red")?.let { if (!it.isIdentityCurve()) curves["Red"] = it }
        getCurve("ToneCurvePV2012Green")?.let { if (!it.isIdentityCurve()) curves["Green"] = it }
        getCurve("ToneCurvePV2012Blue")?.let { if (!it.isIdentityCurve()) curves["Blue"] = it }
        if (curves.isNotEmpty()) put("curves") { it.copy(curves = curves) }

        localMasks().takeIf { it.isNotEmpty() }?.let { masks -> put("masks") { it.copy(masks = masks) } }

        return Parsed(s, fields, rawWhiteBalance)
    }

    /**
     * Lightroom's parametric curve: four tonal regions split at three percentages. Each region lifts or lowers a
     * smooth bump centred on it; the ends stay pinned. Returns null when every region is zero.
     */
    fun parametricCurve(shadows: Float, darks: Float, lights: Float, highlights: Float, split1: Float, split2: Float, split3: Float): ((Float) -> Float)? {
        if (shadows == 0f && darks == 0f && lights == 0f && highlights == 0f) return null
        val s1 = (split1 / 100f).coerceIn(.05f, .9f); val s2 = (split2 / 100f).coerceIn(s1 + .02f, .95f); val s3 = (split3 / 100f).coerceIn(s2 + .02f, .98f)
        val regions = listOf(Triple(0f, s1, shadows), Triple(s1, s2, darks), Triple(s2, s3, lights), Triple(s3, 1f, highlights))
        return { x ->
            var y = x
            regions.forEach { (a, b, v) ->
                if (v == 0f) return@forEach
                val c = (a + b) / 2f; val w = (b - a).coerceAtLeast(.05f)
                val d = abs(x - c)
                if (d < w) y += v / 100f * .16f * .5f * (1f + kotlin.math.cos(Math.PI.toFloat() * d / w))
            }
            val pin = minOf(1f, x / .04f, (1f - x) / .04f)
            (x + (y - x) * pin).coerceIn(0f, 1f)
        }
    }

    /** Maps a Lightroom profile/Look name onto one of this app's profiles. */
    private fun lookProfile(name: String): String? = when {
        name.contains("Monochrome", ignoreCase = true) -> "Monochrome"
        name.contains("Vivid", ignoreCase = true) -> "Adobe Vivid"
        name.contains("Portrait", ignoreCase = true) -> "Adobe Portrait"
        name.contains("Neutral", ignoreCase = true) -> "Adobe Neutral"
        else -> null
    }

    /** Converts Lightroom's graduated and radial filters into this app's Linear/Radial masks. */
    private fun xmpLocalMasks(text: String): List<LocalMask> {
        val masks = mutableListOf<LocalMask>()
        listOf("GradientBasedCorrections" to "Linear", "CircularGradientBasedCorrections" to "Radial").forEach { (section, type) ->
            val block = Regex("<crs:$section>(.*?)</crs:$section>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return@forEach
            block.split("crs:What=\"Correction\"").drop(1).forEach { chunk ->
                fun f(key: String) = Regex("crs:$key=\"([^\"]*)\"").find(chunk)?.groupValues?.get(1)?.trim()?.toFloatOrNull()
                if (Regex("crs:CorrectionActive=\"false\"").containsMatchIn(chunk)) return@forEach
                val amount = f("CorrectionAmount") ?: 1f
                val exposure = ((f("LocalExposure2012") ?: 0f) + (f("LocalExposure") ?: 0f)) * 4f * amount
                val contrast = ((f("LocalContrast2012") ?: 0f) + (f("LocalClarity2012") ?: 0f) * .4f) * 100f * amount
                val mask = LocalMask(
                    id = "$PRESET_MASK_PREFIX${masks.size}", type = type,
                    exposure = exposure.coerceIn(-4f, 4f), contrast = contrast.coerceIn(-100f, 100f),
                    saturation = ((f("LocalSaturation") ?: 0f) * 100f * amount).coerceIn(-100f, 100f),
                    temperature = ((f("LocalTemperature") ?: 0f) * 100f * amount).coerceIn(-100f, 100f),
                    tint = ((f("LocalTint") ?: 0f) * 100f * amount).coerceIn(-100f, 100f),
                    highlights = ((f("LocalHighlights2012") ?: 0f) * 100f * amount).coerceIn(-100f, 100f),
                    shadows = ((f("LocalShadows2012") ?: 0f) * 100f * amount).coerceIn(-100f, 100f),
                )
                if (mask.exposure == 0f && mask.contrast == 0f && mask.saturation == 0f && mask.temperature == 0f &&
                    mask.tint == 0f && mask.highlights == 0f && mask.shadows == 0f) return@forEach
                masks += if (type == "Radial") {
                    val left = f("Left") ?: return@forEach; val right = f("Right") ?: return@forEach
                    val top = f("Top") ?: return@forEach; val bottom = f("Bottom") ?: return@forEach
                    // Flipped=true applies the effect inside the ellipse (Lightroom's "Invert" ticked on old versions).
                    val inside = Regex("crs:Flipped=\"true\"").containsMatchIn(chunk)
                    mask.copy(
                        centerX = ((left + right) / 2f).coerceIn(0f, 1f), centerY = ((top + bottom) / 2f).coerceIn(0f, 1f),
                        size = ((abs(right - left) + abs(bottom - top)) / 4f).coerceIn(.05f, 1f),
                        feather = ((f("Feather") ?: 50f) / 100f).coerceIn(.02f, 1f), invert = !inside,
                    )
                } else {
                    val x0 = f("ZeroX") ?: return@forEach; val y0 = f("ZeroY") ?: return@forEach
                    val x1 = f("FullX") ?: return@forEach; val y1 = f("FullY") ?: return@forEach
                    // Weight runs 0 at Zero to 1 at Full; our linear mask ramps across size*feather around its centre.
                    mask.copy(
                        centerX = ((x0 + x1) / 2f).coerceIn(0f, 1f), centerY = ((y0 + y1) / 2f).coerceIn(0f, 1f),
                        angle = Math.toDegrees(kotlin.math.atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat(),
                        size = kotlin.math.hypot(x1 - x0, y1 - y0).coerceIn(.05f, 1f), feather = 1f,
                    )
                }
                if (masks.size >= 8) return masks
            }
        }
        return masks
    }

    // ---- XMP helpers ----
    private fun xmpValue(text: String, key: String): String {
        Regex("crs:$key\\s*=\\s*\"([^\"]*)\"").find(text)?.let { return it.groupValues[1] }
        Regex("<crs:$key>([^<]*)</crs:$key>").find(text)?.let { return it.groupValues[1] }
        return ""
    }
    private fun xmpFloat(text: String, key: String): Float? = xmpValue(text, key).trim().toFloatOrNull()
    private fun xmpCurve(text: String, key: String): List<CurvePoint>? {
        val block = Regex("<crs:$key>(.*?)</crs:$key>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
        val pts = Regex("<rdf:li>\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*</rdf:li>").findAll(block).mapNotNull {
            val x = it.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
            val y = it.groupValues[2].toFloatOrNull() ?: return@mapNotNull null
            CurvePoint((x / 255f).coerceIn(0f, 1f), (y / 255f).coerceIn(0f, 1f))
        }.toList()
        return if (pts.size >= 2) pts else null
    }

    // ---- lrtemplate (Lua) helpers ----
    private fun luaFloat(text: String, key: String): Float? =
        Regex("\\b$key\\s*=\\s*(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toFloatOrNull()
    private fun luaCurve(text: String, key: String): List<CurvePoint>? {
        val block = Regex("\\b$key\\s*=\\s*\\{([^}]*)\\}").find(text)?.groupValues?.get(1) ?: return null
        val nums = Regex("-?[0-9.]+").findAll(block).mapNotNull { it.value.toFloatOrNull() }.toList()
        if (nums.size < 4) return null
        val pts = nums.chunked(2).filter { it.size == 2 }.map { CurvePoint((it[0] / 255f).coerceIn(0f, 1f), (it[1] / 255f).coerceIn(0f, 1f)) }
        return if (pts.size >= 2) pts else null
    }
}
