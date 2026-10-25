package com.lightroom.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Serialization coverage for the ellipse/subject/invert fields added to masks and lens blur. */
class MaskLensBlurSerializationTest {

    @Test
    fun lensBlurFieldsRoundTrip() {
        val state = DevelopState(
            lensBlur = 60f, lensBlurFocusX = .3f, lensBlurFocusY = .7f, lensBlurFocusRadius = .4f,
            lensBlurAspect = 1.8f, lensBlurAngle = 35f, lensBlurSubject = "Face", lensBlurInvert = true,
        )
        val restored = developStateFromJson(state.toJson())
        assertEquals(1.8f, restored.lensBlurAspect, 1e-4f)
        assertEquals(35f, restored.lensBlurAngle, 1e-4f)
        assertEquals("Face", restored.lensBlurSubject)
        assertTrue(restored.lensBlurInvert)
        assertEquals(.4f, restored.lensBlurFocusRadius, 1e-4f)
    }

    @Test
    fun radialMaskEllipseRoundTrip() {
        val state = DevelopState(masks = listOf(LocalMask(id = "m1", type = "Radial", size = .5f, aspect = 2.4f, angle = 20f, invert = true)))
        val restored = developStateFromJson(state.toJson())
        assertEquals(1, restored.masks.size)
        assertEquals(2.4f, restored.masks[0].aspect, 1e-4f)
        assertEquals(20f, restored.masks[0].angle, 1e-4f)
        assertTrue(restored.masks[0].invert)
    }

    @Test
    fun maskAspectDefaultsToCircleWhenAbsent() {
        // Older recipes without "aspect" load as a circle (1.0), not a degenerate ellipse.
        val json = DevelopState(masks = listOf(LocalMask(id = "m1", type = "Radial"))).toJson()
        (json.getJSONArray("masks").getJSONObject(0)).remove("aspect")
        assertEquals(1f, developStateFromJson(json).masks[0].aspect, 1e-4f)
    }

    @Test
    fun unknownLensBlurSubjectFallsBackToNone() {
        val json: JSONObject = DevelopState(lensBlurSubject = "Person").toJson().put("lensBlurSubject", "Bogus")
        assertEquals("None", developStateFromJson(json).lensBlurSubject)
    }

    @Test
    fun lensBlurAspectClampedOnLoad() {
        val json = DevelopState().toJson().put("lensBlurAspect", 99f)
        assertTrue(developStateFromJson(json).lensBlurAspect <= 5f)
        assertFalse(developStateFromJson(json).lensBlurAspect < .2f)
    }
}
