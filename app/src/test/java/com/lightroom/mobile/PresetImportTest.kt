package com.lightroom.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Parses the real bundled Lightroom presets (unit tests run from the module directory). */
class PresetImportTest {
    private fun bundled(path: String) = PresetImporter.parse(File("src/main/assets/presets/$path").readText())!!

    @Test
    fun everyBundledPresetParses() {
        val files = File("src/main/assets/presets").walk().filter { it.extension == "xmp" }.toList()
        assertTrue(files.size > 400)
        files.forEach { assertNotNull(it.path, PresetImporter.parse(it.readText())) }
    }

    @Test
    fun customKelvinWhiteBalanceIsRawOnly() {
        val parsed = bundled("Cinematic/001_Cinematic 1.xmp")
        assertNotNull(parsed.rawWhiteBalance)
        assertFalse("temperature" in parsed.fields)
        val preset = Preset("p", "p", "c", parsed.state, fields = parsed.fields, rawWhiteBalance = parsed.rawWhiteBalance)
        assertEquals(0f, preset.lookFor(isRaw = false).first.temperature, 0f)
        assertTrue("temperature" in preset.lookFor(isRaw = true).second!!)
    }

    @Test
    fun calibrationAndParametricCurveAreImported() {
        val parsed = bundled("Cinematic/005_Cinematic 13.xmp")
        assertTrue("redHue" in parsed.fields)
        assertTrue(parsed.state.redHue != 0f || parsed.state.redSaturation != 0f || parsed.state.blueSaturation != 0f)
    }

    @Test
    fun localGradientsBecomeMasks() {
        val parsed = bundled("Food/017_Food 24.xmp")
        assertTrue("masks" in parsed.fields)
        assertTrue(parsed.state.masks.isNotEmpty())
        assertTrue(parsed.state.masks.all { it.id.startsWith(PRESET_MASK_PREFIX) && it.type in setOf("Linear", "Radial") })
    }

    @Test
    fun parametricCurveLiftsItsRegionAndKeepsEndsPinned() {
        val curve = PresetImporter.parametricCurve(0f, 0f, 0f, 0f, 25f, 50f, 75f)
        assertEquals(null, curve)
        val lifted = PresetImporter.parametricCurve(0f, 40f, 0f, 0f, 25f, 50f, 75f)!!
        assertTrue(lifted(.375f) > .375f)
        assertEquals(0f, lifted(0f), 1e-4f)
        assertEquals(1f, lifted(1f), 1e-4f)
    }

    @Test
    fun swappingPresetsReplacesTheirMasks() {
        val userMask = LocalMask("mine")
        val first = DevelopState(masks = listOf(LocalMask("${PRESET_MASK_PREFIX}0")))
        val base = DevelopState(masks = listOf(userMask))
        val applied = blendPreset(base, first, 1f, setOf("masks"))
        assertEquals(listOf("mine", "${PRESET_MASK_PREFIX}0"), applied.masks.map { it.id })
        // Taking the preset off (blending back to the base) drops its masks and keeps the photo's own.
        assertEquals(listOf("mine"), blendPreset(applied, base, 1f, setOf("masks")).masks.map { it.id })
    }
}
