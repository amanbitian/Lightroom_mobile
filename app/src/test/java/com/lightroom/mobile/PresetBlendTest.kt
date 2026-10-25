package com.lightroom.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PresetBlendTest {
    private val userEdit = DevelopState(
        exposure = .7f, contrast = 25f, saturation = 12f, profile = "Monochrome",
        colorMix = mapOf("Blue" to ColorMix(saturation = 30f)),
    )

    @Test
    fun importedPresetRecordsOnlyTheFieldsItDefines() {
        val xmp = """<x:xmpmeta><rdf:Description crs:IncrementalTemperature="20" crs:IncrementalTint="-5"/></x:xmpmeta>"""
        val parsed = PresetImporter.parse(xmp)
        assertNotNull(parsed); parsed!!
        assertEquals(setOf("temperature", "tint"), parsed.fields)
        assertEquals(20f, parsed.state.temperature, 0f)
    }

    @Test
    fun whiteBalancePresetLeavesOtherSettingsAlone() {
        val parsed = PresetImporter.parse("""<x:xmpmeta><rdf:Description crs:IncrementalTemperature="20"/></x:xmpmeta>""")!!
        val result = blendPreset(userEdit, parsed.state, 1f, parsed.fields)
        assertEquals(20f, result.temperature, 0f)
        assertEquals(.7f, result.exposure, 0f)
        assertEquals(25f, result.contrast, 0f)
        assertEquals(userEdit.sharpen, result.sharpen, 0f)
        assertEquals("Monochrome", result.profile)
        assertEquals(userEdit.colorMix, result.colorMix)
    }

    @Test
    fun explicitZeroInPresetIsStillApplied() {
        val parsed = PresetImporter.parse("""<x:xmpmeta><rdf:Description crs:Contrast2012="0"/></x:xmpmeta>""")!!
        assertEquals(setOf("contrast"), parsed.fields)
        assertEquals(0f, blendPreset(userEdit, parsed.state, 1f, parsed.fields).contrast, 0f)
    }

    @Test
    fun zeroStrengthChangesNothing() {
        val look = DevelopState(contrast = 40f, profile = "Adobe Vivid")
        assertEquals(userEdit, blendPreset(userEdit, look, 0f))
    }

    @Test
    fun completeLookKeepsMonochromeUnlessItAsksForAProfile() {
        val colorLook = DevelopState(contrast = 10f)
        assertEquals("Monochrome", blendPreset(userEdit, colorLook, 1f).profile)
    }

    @Test
    fun hueBlendsAlongTheShorterArc() {
        val base = DevelopState(shadowHue = 350f)
        val target = DevelopState(shadowHue = 30f)
        assertEquals(10f, blendPreset(base, target, .5f, setOf("shadowHue")).shadowHue, .01f)
    }
}
