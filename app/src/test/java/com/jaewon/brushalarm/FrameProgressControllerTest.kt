package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameProgressControllerTest {
    @Test
    fun `pre gate rejection breaks brushing continuity`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)

        controller.acceptVerified(timestampMillis = 0, brushing = true)
        controller.onPreGateRejected(timestampMillis = 250)
        controller.acceptVerified(timestampMillis = 500, brushing = true)

        assertEquals(0, progress.accumulatedMillis)
    }

    @Test
    fun `unverified busy interval is not credited across a missing inference gap`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)

        controller.acceptVerified(timestampMillis = 0, brushing = true)
        controller.onUnverifiedFrame()
        controller.acceptVerified(timestampMillis = 1_000, brushing = true)

        assertEquals(0, progress.accumulatedMillis)
    }

    @Test
    fun `busy or throttled frame remains unknown rather than breaking valid cadence`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)

        controller.acceptVerified(timestampMillis = 0, brushing = true)
        controller.onUnverifiedFrame()
        controller.acceptVerified(timestampMillis = 250, brushing = true)

        assertEquals(250, progress.accumulatedMillis)
    }

    @Test
    fun `delayed verified callback cannot undo a newer pre gate rejection`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)

        controller.acceptVerified(timestampMillis = 0, brushing = true)
        controller.onPreGateRejected(timestampMillis = 125)
        controller.acceptVerified(timestampMillis = 0, brushing = true)
        controller.acceptVerified(timestampMillis = 250, brushing = true)

        assertEquals(0, progress.accumulatedMillis)
    }

    @Test
    fun `stale rejection cannot erase a newer valid sequence`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)

        controller.acceptVerified(timestampMillis = 0, brushing = true)
        controller.acceptVerified(timestampMillis = 250, brushing = true)
        controller.onPreGateRejected(timestampMillis = 125)
        controller.acceptVerified(timestampMillis = 500, brushing = true)

        assertEquals(500, progress.accumulatedMillis)
    }

    @Test
    fun `newer pre gate rejection suppresses all delayed success callback side effects`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)
        var analyzerEffects = 0
        var verifierEffects = 0
        var uiEffects = 0

        // Inference for capture t=0 is now in flight. A newer cheap frame is conclusive first.
        controller.onPreGateRejected(timestampMillis = 125)
        val accepted = controller.onVerified(
            timestampMillis = 0,
            evaluate = {
                analyzerEffects++
                verifierEffects++
                true
            },
            isBrushing = { it },
            onAccepted = { uiEffects++ },
        )

        assertFalse(accepted)
        assertEquals(0, analyzerEffects)
        assertEquals(0, verifierEffects)
        assertEquals(0, uiEffects)

        assertTrue(
            controller.onVerified(
                timestampMillis = 250,
                evaluate = {
                    analyzerEffects++
                    verifierEffects++
                    true
                },
                isBrushing = { it },
                onAccepted = { uiEffects++ },
            ),
        )
        assertEquals(1, analyzerEffects)
        assertEquals(1, verifierEffects)
        assertEquals(1, uiEffects)
        assertEquals(0, progress.accumulatedMillis)
    }

    @Test
    fun `stale failure has no UI or progress effect`() {
        val progress = BrushingProgressEngine(requiredMillis = 30_000, maxFrameGapMillis = 500)
        val controller = FrameProgressController(progress)
        var uiEffects = 0

        controller.acceptVerified(250, brushing = true)
        controller.acceptVerified(500, brushing = true)
        assertEquals(250, progress.accumulatedMillis)

        assertFalse(controller.onFailure(timestampMillis = 375) { uiEffects++ })
        assertEquals(0, uiEffects)

        controller.acceptVerified(750, brushing = true)
        assertEquals(500, progress.accumulatedMillis)
    }

    private fun FrameProgressController.acceptVerified(timestampMillis: Long, brushing: Boolean) {
        onVerified(
            timestampMillis = timestampMillis,
            evaluate = { brushing },
            isBrushing = { it },
            onAccepted = {},
        )
    }
}
