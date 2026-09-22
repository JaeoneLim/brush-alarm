package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridBrushingVerifierTest {
    private val face = NormalizedFace(centerX = 0.5, centerY = 0.45, width = 0.5, height = 0.65)

    @Test
    fun `one mouth motion spike never qualifies as brushing`() {
        val verifier = HybridBrushingVerifier()

        val result = verifier.update(signal(0, x = 0.35, local = 18.0))

        assertFalse(result.brushing)
        assertEquals(VerificationState.SEEKING_CADENCE, result.state)
    }

    @Test
    fun `repeated plausible direction reversals qualify as brushing`() {
        val verifier = HybridBrushingVerifier()
        val xs = listOf(0.30, 0.45, 0.31, 0.46, 0.30, 0.45)

        val results = xs.mapIndexed { index, x -> verifier.update(signal(index * 300L, x)) }

        assertTrue(results.last().brushing)
        assertEquals(VerificationState.BRUSHING, results.last().state)
        assertTrue(results.last().reversalCount >= 3)
    }

    @Test
    fun `implausibly fast jitter is rejected`() {
        val verifier = HybridBrushingVerifier()
        val xs = listOf(0.30, 0.45, 0.31, 0.46, 0.30, 0.45, 0.31)

        val result = xs.mapIndexed { index, x -> verifier.update(signal(index * 50L, x)) }.last()

        assertFalse(result.brushing)
        assertEquals(VerificationState.SEEKING_CADENCE, result.state)
    }

    @Test
    fun `slow isolated reversals are rejected`() {
        val verifier = HybridBrushingVerifier()
        val xs = listOf(0.30, 0.45, 0.31, 0.46, 0.30)

        val result = xs.mapIndexed { index, x -> verifier.update(signal(index * 1_800L, x)) }.last()

        assertFalse(result.brushing)
    }

    @Test
    fun `global light flicker is rejected when mouth and frame change together`() {
        val verifier = HybridBrushingVerifier()

        val result = verifier.update(signal(0, x = 0.3, local = 24.0, global = 20.0))

        assertFalse(result.brushing)
        assertEquals(VerificationState.LIGHTING_CHANGE, result.state)
    }

    @Test
    fun `localized mouth change passes lighting discrimination`() {
        val verifier = HybridBrushingVerifier()
        val xs = listOf(0.30, 0.45, 0.31, 0.46, 0.30, 0.45)

        val result = xs.mapIndexed { index, x ->
            verifier.update(signal(index * 300L, x, local = 18.0, global = 2.0))
        }.last()

        assertTrue(result.brushing)
    }

    @Test
    fun `normalized head translation resets cadence`() {
        val verifier = HybridBrushingVerifier()
        verifier.update(signal(0, x = 0.30))
        verifier.update(signal(300, x = 0.45))
        verifier.update(signal(600, x = 0.31))

        val shiftedFace = face.copy(centerX = 0.68)
        val result = verifier.update(signal(900, x = 0.46, observedFace = shiftedFace))

        assertFalse(result.brushing)
        assertEquals(VerificationState.HOLD_STILL, result.state)
        assertEquals(0, result.reversalCount)
    }

    @Test
    fun `normalized face scale jump resets cadence`() {
        val verifier = HybridBrushingVerifier()
        verifier.update(signal(0, x = 0.30))
        verifier.update(signal(300, x = 0.45))

        val largerFace = face.copy(width = 0.7, height = 0.85)
        val result = verifier.update(signal(600, x = 0.31, observedFace = largerFace))

        assertFalse(result.brushing)
        assertEquals(VerificationState.HOLD_STILL, result.state)
    }

    @Test
    fun `material face roll change resets cadence`() {
        val verifier = HybridBrushingVerifier()
        verifier.update(signal(0, x = 0.30))
        verifier.update(signal(300, x = 0.45))

        val rolledFace = face.copy(rollRadians = 0.25)
        val result = verifier.update(signal(600, x = 0.31, observedFace = rolledFace))

        assertFalse(result.brushing)
        assertEquals(VerificationState.HOLD_STILL, result.state)
        assertEquals(0, result.reversalCount)
    }

    @Test
    fun `material yaw proxy change resets cadence`() {
        val verifier = HybridBrushingVerifier()
        verifier.update(signal(0, x = 0.30))
        verifier.update(signal(300, x = 0.45))

        val turnedFace = face.copy(yawProxy = 0.25)
        val result = verifier.update(signal(600, x = 0.31, observedFace = turnedFace))

        assertFalse(result.brushing)
        assertEquals(VerificationState.HOLD_STILL, result.state)
        assertEquals(0, result.reversalCount)
    }

    @Test
    fun `missing face reports clear state and resets cadence`() {
        val verifier = HybridBrushingVerifier()
        verifier.update(signal(0, x = 0.30))
        verifier.update(signal(300, x = 0.45))

        val result = verifier.update(signal(600, x = 0.31, observedFace = null))

        assertFalse(result.brushing)
        assertEquals(VerificationState.NO_FACE, result.state)
        assertEquals(0, result.reversalCount)
    }

    @Test
    fun `stale samples fall out of rolling window`() {
        val verifier = HybridBrushingVerifier(windowMillis = 2_000)
        val xs = listOf(0.30, 0.45, 0.31, 0.46)
        xs.forEachIndexed { index, x -> verifier.update(signal(index * 300L, x)) }

        val result = verifier.update(signal(4_000, x = 0.30))

        assertFalse(result.brushing)
        assertTrue(result.reversalCount < 3)
    }

    private fun signal(
        time: Long,
        x: Double,
        local: Double = 18.0,
        global: Double = 2.0,
        observedFace: NormalizedFace? = face,
    ) = BrushingSignal(
        timestampMillis = time,
        face = observedFace,
        localLumaDelta = local,
        globalLumaDelta = global,
        mouthMotionX = x,
    )
}

class FaceMeshObservationMapperTest {
    @Test
    fun `mapper keeps official indexed lip landmarks and face bounds`() {
        val points = listOf(
            IndexedMeshPoint(0, 1.0, 1.0),
            IndexedMeshPoint(13, 4.0, 6.0),
            IndexedMeshPoint(14, 4.0, 7.0),
            IndexedMeshPoint(61, 2.0, 6.5),
            IndexedMeshPoint(291, 7.0, 6.5),
        )

        val observation = FaceMeshObservationMapper.map(PixelRect(1.0, 1.0, 9.0, 9.0), points)

        assertEquals(4, observation.mouthLandmarks.size)
        assertFalse(observation.mouthLandmarks.any { it.x == 1.0 && it.y == 1.0 })
        assertEquals(8.0, observation.faceBounds.width, 0.001)
    }

    @Test
    fun `mapper derives roll and yaw proxy from eye and nose landmarks`() {
        val neutral = FaceMeshObservationMapper.map(
            PixelRect(0.0, 0.0, 10.0, 10.0),
            listOf(
                IndexedMeshPoint(33, 3.0, 3.0),
                IndexedMeshPoint(263, 7.0, 3.0),
                IndexedMeshPoint(1, 5.0, 5.0),
            ),
        )
        val turnedAndRolled = FaceMeshObservationMapper.map(
            PixelRect(0.0, 0.0, 10.0, 10.0),
            listOf(
                IndexedMeshPoint(33, 3.0, 3.0),
                IndexedMeshPoint(263, 7.0, 5.0),
                IndexedMeshPoint(1, 6.0, 5.0),
            ),
        )

        assertEquals(0.0, neutral.rollRadians, 0.001)
        assertEquals(0.0, neutral.yawProxy, 0.001)
        assertTrue(turnedAndRolled.rollRadians > 0.4)
        assertTrue(turnedAndRolled.yawProxy > 0.1)
    }
}

class InferenceGateTest {
    @Test
    fun `heavy inference is capped at four hertz`() {
        val gate = InferenceGate(minIntervalMillis = 250)

        assertTrue(gate.tryAcquire(0))
        gate.release()
        assertFalse(gate.tryAcquire(249))
        assertTrue(gate.tryAcquire(250))
    }

    @Test
    fun `frames are dropped while inference is busy`() {
        val gate = InferenceGate(minIntervalMillis = 250)

        assertTrue(gate.tryAcquire(0))
        assertFalse(gate.tryAcquire(300))
        gate.release()
        assertTrue(gate.tryAcquire(300))
    }
}

class LumaSignalAnalyzerTest {
    @Test
    fun `cheap pre gate ignores unchanged frames and sees sparse local motion`() {
        val analyzer = LumaSignalAnalyzer(pixelChangeThreshold = 10, changedFractionThreshold = 0.02)
        val first = ByteArray(100) { 30 }
        val second = first.copyOf().also { frame ->
            for (index in 44..48) frame[index] = 80
        }

        assertFalse(analyzer.passesPreGate(first, 10, 10))
        assertTrue(analyzer.passesPreGate(second, 10, 10))
    }

    @Test
    fun `mouth ROI is derived from mesh landmarks and normalized to face`() {
        val analyzer = LumaSignalAnalyzer()
        val frame = ByteArray(100) { 20 }
        val changed = frame.copyOf().also { bytes ->
            for (y in 6..7) for (x in 3..6) bytes[y * 10 + x] = 90
        }
        val mouth = listOf(
            MeshPoint(3.0, 6.0), MeshPoint(6.0, 6.0), MeshPoint(6.0, 7.0), MeshPoint(3.0, 7.0),
        )
        val observation = MeshObservation(
            faceBounds = PixelRect(1.0, 1.0, 9.0, 9.0),
            mouthLandmarks = mouth,
        )

        analyzer.analyze(frame, 10, 10, observation)
        val signal = analyzer.analyze(changed, 10, 10, observation)

        assertTrue(signal.localLumaDelta > signal.globalLumaDelta * 2)
        assertTrue(signal.mouthMotionX in 0.0..1.0)
        assertEquals(0.5, signal.face.centerX, 0.001)
        assertEquals(0.8, signal.face.width, 0.001)
    }

    @Test
    fun `mouth motion coordinate follows repeated left right object positions`() {
        val analyzer = LumaSignalAnalyzer(pixelChangeThreshold = 10)
        val mesh = MeshObservation(
            faceBounds = PixelRect(0.0, 0.0, 12.0, 10.0),
            mouthLandmarks = listOf(MeshPoint(2.0, 4.0), MeshPoint(9.0, 7.0)),
        )
        fun frame(blockLeft: Int?): ByteArray = ByteArray(120) { 30 }.also { bytes ->
            if (blockLeft != null) {
                for (y in 4..7) for (x in blockLeft..blockLeft + 2) bytes[y * 12 + x] = 100
            }
        }
        analyzer.analyze(frame(null), 12, 10, mesh)
        val left = analyzer.analyze(frame(2), 12, 10, mesh)
        val right = analyzer.analyze(frame(7), 12, 10, mesh)
        val leftAgain = analyzer.analyze(frame(2), 12, 10, mesh)

        assertTrue(left.mouthMotionX < right.mouthMotionX)
        assertTrue(leftAgain.mouthMotionX < right.mouthMotionX)
    }
}
