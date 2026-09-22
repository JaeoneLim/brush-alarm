package com.jaewon.brushalarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameLoopStateCoordinatorTest {
    @Test
    fun `camera binding schedules exactly one loop while started`() {
        val state = FrameLoopStateCoordinator()

        assertFalse(state.onStart())
        assertTrue(state.onCameraReady())
        assertFalse(state.onCameraReady())
        assertFalse(state.onStart())
        assertTrue(state.running)
    }

    @Test
    fun `stop disables loop and next start reschedules bound camera`() {
        val state = FrameLoopStateCoordinator()

        state.onStart()
        assertTrue(state.onCameraReady())
        state.onStop()

        assertFalse(state.running)
        assertFalse(state.onCameraReady())
        assertTrue(state.onStart())
        assertTrue(state.running)
    }
}
