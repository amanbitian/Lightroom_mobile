package com.lightroom.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPullFillTest {
    @Test
    fun knownValuesStayAndHolesFillBetweenTheirNeighbours() {
        val w = 32; val h = 32
        // Left half known at 0, right half known at 100, a vertical band in the middle unknown.
        val weight = FloatArray(w * h) { i -> if (i % w in 12..19) 0f else 1f }
        val value = FloatArray(w * h) { i -> if (i % w < 16) 0f else 100f }
        val out = PushPullFill.interpolate(value, value, value, weight, w, h)[0]
        for (y in 0 until h) {
            assertEquals(0f, out[y * w + 2], 1e-3f)
            assertEquals(100f, out[y * w + 29], 1e-3f)
            val holeValues = (12..19).map { out[y * w + it] }
            assertTrue(holeValues.all { it in 0f..100f })
            // Filled smoothly: rising from the dark side to the bright side, no gap left at zero.
            assertTrue(holeValues.first() < holeValues.last())
        }
    }

    @Test
    fun singleKnownPixelFloodsTheGrid() {
        val w = 9; val h = 9
        val weight = FloatArray(w * h).also { it[40] = 1f }
        val value = FloatArray(w * h) { 7f }
        val out = PushPullFill.interpolate(value, value, value, weight, w, h)[1]
        assertTrue(out.all { kotlin.math.abs(it - 7f) < 1e-3f })
    }
}
