package com.jaewon.brushalarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmUpStateCoordinatorTest {
    @Test
    fun `stop makes an abandoned warm up retryable on next start`() {
        val state = WarmUpStateCoordinator()

        val abandonedAttempt = state.onStart()
        assertNotNull(abandonedAttempt)
        assertTrue(state.warmingUp)

        state.onStop()
        assertFalse(state.warmingUp)

        val retryAttempt = state.onStart()
        assertNotNull(retryAttempt)
        assertNotEquals(abandonedAttempt, retryAttempt)
        assertTrue(state.warmingUp)
    }

    @Test
    fun `abandoned callback cannot affect a newer warm up`() {
        val state = WarmUpStateCoordinator()
        val abandonedAttempt = state.onStart()!!
        state.onStop()
        val currentAttempt = state.onStart()!!

        assertFalse(state.onReady(abandonedAttempt))
        assertFalse(state.modelReady)
        assertTrue(state.warmingUp)

        assertTrue(state.onReady(currentAttempt))
        assertTrue(state.modelReady)
        assertFalse(state.warmingUp)
        assertNull(state.onStart())
    }

    @Test
    fun `failed warm up can retry while started`() {
        val state = WarmUpStateCoordinator()
        val failedAttempt = state.onStart()!!

        assertTrue(state.onFailure(failedAttempt))
        assertFalse(state.modelReady)
        assertFalse(state.warmingUp)
        assertNotNull(state.onStart())
    }
}