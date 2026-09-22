package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushingProgressEngineTest {
    @Test
    fun `thirty seconds of valid brushing unlocks alarm`() {
        val engine = BrushingProgressEngine(requiredMillis = 30_000)
        engine.update(timestampMillis = 1_000, brushingDetected = true)
        engine.update(timestampMillis = 31_000, brushingDetected = true)
        assertTrue(engine.isComplete)
        assertEquals(30_000, engine.accumulatedMillis)
    }

    @Test
    fun `non brushing frames do not count and break continuity`() {
        val engine = BrushingProgressEngine(requiredMillis = 30_000)
        engine.update(1_000, true)
        engine.update(11_000, true)
        engine.update(12_000, false)
        engine.update(42_000, true)
        assertFalse(engine.isComplete)
        assertEquals(10_000, engine.accumulatedMillis)
    }

    @Test
    fun `large frame gaps are not credited`() {
        val engine = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 1_000)
        engine.update(1_000, true)
        engine.update(10_000, true)
        assertEquals(0, engine.accumulatedMillis)
    }

    @Test
    fun `progress never exceeds requirement`() {
        val engine = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 60_000)
        engine.update(0, true)
        engine.update(40_000, true)
        assertEquals(30_000, engine.accumulatedMillis)
        assertTrue(engine.isComplete)
    }
}
