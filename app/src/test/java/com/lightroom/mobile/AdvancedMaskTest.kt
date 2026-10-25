package com.lightroom.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedMaskTest {
    private val gray = 0xFF646464.toInt()

    private fun render(masks: List<LocalMask>, coverage: Map<String, Float>, color: Int = gray): Int {
        lateinit var engine: ColorEngine
        val sampler = MaskSampler { index, _, _ -> coverage[engine.localMasks[index].id] ?: 0f }
        engine = ColorEngine(DevelopState(masks = masks), 1, 1, frameEffects = false, sampler = sampler)
        val pixels = intArrayOf(color)
        engine.process(pixels, 0, 1)
        return pixels[0]
    }

    @Test fun booleanComponentsChangeTheBaseCoverage() {
        val base = LocalMask(id = "base", type = "Brush", exposure = 1f)
        val add = LocalMask(id = "component", type = "Brush", combine = "Add")
        val subtract = add.copy(combine = "Subtract")
        val intersect = add.copy(combine = "Intersect")
        assertTrue(render(listOf(base, add), mapOf("base" to 0f, "component" to 1f)) != gray)
        assertEquals(gray, render(listOf(base, subtract), mapOf("base" to 1f, "component" to 1f)))
        assertEquals(gray, render(listOf(base, intersect), mapOf("base" to 1f, "component" to 0f)))
        assertTrue(render(listOf(base, intersect), mapOf("base" to 1f, "component" to 1f)) != gray)
    }

    @Test fun disablingBaseDoesNotPromoteItsComponentIntoAnAdjustment() {
        val base = LocalMask(id = "base", type = "Brush", exposure = 1f, enabled = false)
        val component = LocalMask(id = "component", type = "Brush", combine = "Add", exposure = 2f)
        assertEquals(gray, render(listOf(base, component), mapOf("component" to 1f)))
    }

    @Test fun luminanceRangeRestrictsAdjustmentToItsBand() {
        val mask = LocalMask(id = "range", type = "Brush", exposure = 1f, range = "Luminance", rangeLo = 0f, rangeHi = 35f)
        val dark = 0xFF303030.toInt(); val light = 0xFFD0D0D0.toInt()
        assertTrue(render(listOf(mask), mapOf("range" to 1f), dark) != dark)
        assertEquals(light, render(listOf(mask), mapOf("range" to 1f), light))
    }

    @Test fun facialMaskTypesSurviveRecipeSerialization() {
        val state = DevelopState(masks = faceMaskTypes.mapIndexed { index, type -> LocalMask(id = "f$index", type = type) })
        assertEquals(faceMaskTypes, developStateFromJson(state.toJson()).masks.map { it.type }.toSet())
    }
}
