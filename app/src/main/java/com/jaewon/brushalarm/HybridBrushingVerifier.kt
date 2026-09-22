package com.jaewon.brushalarm

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Pure Kotlin signal types shared by the camera adapter and verifier. */
data class PixelRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = (right - left).coerceAtLeast(0.0)
    val height: Double get() = (bottom - top).coerceAtLeast(0.0)
}

data class MeshPoint(val x: Double, val y: Double)

data class IndexedMeshPoint(val index: Int, val x: Double, val y: Double)

object FaceMeshObservationMapper {
    // MediaPipe/ML Kit's fixed 468-point topology: outer and inner lip rings.
    private val lipIndices = setOf(
        61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291,
        78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308,
        191, 80, 81, 82, 13, 312, 311, 310, 415,
    )

    fun map(faceBounds: PixelRect, points: List<IndexedMeshPoint>): MeshObservation {
        val byIndex = points.associateBy { it.index }
        val leftEye = byIndex[33]
        val rightEye = byIndex[263]
        val nose = byIndex[1]
        var rollRadians = 0.0
        var yawProxy = 0.0
        if (leftEye != null && rightEye != null && nose != null) {
            val eyeX = rightEye.x - leftEye.x
            val eyeY = rightEye.y - leftEye.y
            val eyeDistanceSquared = eyeX * eyeX + eyeY * eyeY
            if (eyeDistanceSquared > 0.0) {
                rollRadians = atan2(eyeY, eyeX)
                val midpointX = (leftEye.x + rightEye.x) / 2.0
                val midpointY = (leftEye.y + rightEye.y) / 2.0
                yawProxy = ((nose.x - midpointX) * eyeX + (nose.y - midpointY) * eyeY) /
                    eyeDistanceSquared
            }
        }
        return MeshObservation(
            faceBounds = faceBounds,
            mouthLandmarks = points.filter { it.index in lipIndices }.map { MeshPoint(it.x, it.y) },
            rollRadians = rollRadians,
            yawProxy = yawProxy,
        )
    }
}

data class MeshObservation(
    val faceBounds: PixelRect,
    val mouthLandmarks: List<MeshPoint>,
    val rollRadians: Double = 0.0,
    val yawProxy: Double = 0.0,
)

data class NormalizedFace(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val rollRadians: Double = 0.0,
    val yawProxy: Double = 0.0,
)

data class BrushingSignal(
    val timestampMillis: Long,
    val face: NormalizedFace?,
    val localLumaDelta: Double,
    val globalLumaDelta: Double,
    val mouthMotionX: Double,
)

enum class VerificationState {
    WARMING_UP,
    NO_FACE,
    HOLD_STILL,
    LIGHTING_CHANGE,
    MOVE_AT_MOUTH,
    SEEKING_CADENCE,
    BRUSHING,
    ANALYSIS_ERROR,
}

data class VerificationResult(
    val state: VerificationState,
    val brushing: Boolean,
    val reversalCount: Int,
)

/**
 * Temporal verifier. It accepts only localized mouth motion with a stable face and several
 * direction reversals at a plausible brushing cadence inside a rolling window.
 */
class HybridBrushingVerifier(
    private val windowMillis: Long = 3_500,
    private val localMotionThreshold: Double = 7.0,
    private val localToGlobalRatio: Double = 1.6,
    private val maxTranslationFraction: Double = 0.10,
    private val maxScaleChangeFraction: Double = 0.20,
    private val maxRollChangeRadians: Double = 0.12,
    private val maxYawProxyChange: Double = 0.12,
    private val minMotionStep: Double = 0.035,
    private val minReversalIntervalMillis: Long = 180,
    private val maxReversalIntervalMillis: Long = 1_500,
    private val requiredReversals: Int = 3,
) {
    private data class MotionSample(val timestampMillis: Long, val x: Double)

    private val samples = ArrayDeque<MotionSample>()
    private var previousFace: NormalizedFace? = null

    fun update(signal: BrushingSignal): VerificationResult {
        val face = signal.face
        if (face == null) {
            resetMotion()
            previousFace = null
            return result(VerificationState.NO_FACE)
        }

        val oldFace = previousFace
        previousFace = face
        if (oldFace != null && !isStable(oldFace, face)) {
            resetMotion()
            return result(VerificationState.HOLD_STILL)
        }

        val lightingChange = signal.globalLumaDelta >= localMotionThreshold &&
            signal.localLumaDelta < signal.globalLumaDelta * localToGlobalRatio
        if (lightingChange) {
            resetMotion()
            return result(VerificationState.LIGHTING_CHANGE)
        }

        val localizedMotion = signal.localLumaDelta >= localMotionThreshold &&
            signal.localLumaDelta >= max(1.0, signal.globalLumaDelta) * localToGlobalRatio
        if (!localizedMotion) {
            trim(signal.timestampMillis)
            return result(VerificationState.MOVE_AT_MOUTH)
        }

        samples.addLast(MotionSample(signal.timestampMillis, signal.mouthMotionX))
        trim(signal.timestampMillis)
        val reversals = countPlausibleReversals()
        return if (reversals >= requiredReversals) {
            VerificationResult(VerificationState.BRUSHING, true, reversals)
        } else {
            VerificationResult(VerificationState.SEEKING_CADENCE, false, reversals)
        }
    }

    fun reset() {
        resetMotion()
        previousFace = null
    }

    private fun isStable(previous: NormalizedFace, current: NormalizedFace): Boolean {
        val translationX = abs(current.centerX - previous.centerX) / previous.width.coerceAtLeast(0.01)
        val translationY = abs(current.centerY - previous.centerY) / previous.height.coerceAtLeast(0.01)
        val widthChange = abs(current.width - previous.width) / previous.width.coerceAtLeast(0.01)
        val heightChange = abs(current.height - previous.height) / previous.height.coerceAtLeast(0.01)
        val rollDelta = abs(atan2(sin(current.rollRadians - previous.rollRadians), cos(current.rollRadians - previous.rollRadians)))
        val yawDelta = abs(current.yawProxy - previous.yawProxy)
        return max(translationX, translationY) <= maxTranslationFraction &&
            max(widthChange, heightChange) <= maxScaleChangeFraction &&
            rollDelta <= maxRollChangeRadians &&
            yawDelta <= maxYawProxyChange
    }

    private fun trim(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().timestampMillis > windowMillis) {
            samples.removeFirst()
        }
    }

    private fun countPlausibleReversals(): Int {
        if (samples.size < 3) return 0
        var previousDirection = 0
        var previousReversalTime: Long? = null
        var reversals = 0
        for (index in 1 until samples.size) {
            val previous = samples[index - 1]
            val current = samples[index]
            val delta = current.x - previous.x
            if (abs(delta) < minMotionStep) continue
            val direction = if (delta > 0) 1 else -1
            if (previousDirection != 0 && direction != previousDirection) {
                val lastTime = previousReversalTime
                if (lastTime != null && current.timestampMillis - lastTime in
                    minReversalIntervalMillis..maxReversalIntervalMillis
                ) {
                    reversals++
                }
                // Track every actual reversal so fast jitter cannot be downsampled into a fake cadence.
                previousReversalTime = current.timestampMillis
            }
            previousDirection = direction
        }
        return reversals
    }

    private fun resetMotion() = samples.clear()

    private fun result(state: VerificationState) = VerificationResult(state, false, countPlausibleReversals())
}

/** Thread-safe 3–5 Hz inference limiter that also drops frames while a task is active. */
class InferenceGate(private val minIntervalMillis: Long = 250) {
    private var busy = false
    private var lastStartedAt: Long? = null

    @Synchronized
    fun tryAcquire(timestampMillis: Long): Boolean {
        if (busy) return false
        val previous = lastStartedAt
        if (previous != null && timestampMillis - previous < minIntervalMillis) return false
        busy = true
        lastStartedAt = timestampMillis
        return true
    }

    @Synchronized
    fun release() {
        busy = false
    }
}

/**
 * Low-cost luma pre-gate plus mesh-normalized ROI analysis. State is instance-local and frames
 * are retained only as in-memory luma arrays for comparison with the next frame.
 */
class LumaSignalAnalyzer(
    private val pixelChangeThreshold: Int = 12,
    private val changedFractionThreshold: Double = 0.006,
) {
    data class Analysis(
        val face: NormalizedFace,
        val localLumaDelta: Double,
        val globalLumaDelta: Double,
        val mouthMotionX: Double,
    )

    private var preGatePrevious: ByteArray? = null
    private var analysisPrevious: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    fun passesPreGate(luma: ByteArray, width: Int, height: Int): Boolean {
        require(luma.size == width * height)
        val old = preGatePrevious
        preGatePrevious = luma.copyOf()
        if (old == null || old.size != luma.size) return false
        var changed = 0
        var sampled = 0
        for (index in luma.indices step 2) {
            if (abs(unsigned(luma[index]) - unsigned(old[index])) >= pixelChangeThreshold) changed++
            sampled++
        }
        return sampled > 0 && changed.toDouble() / sampled >= changedFractionThreshold
    }

    fun analyze(
        luma: ByteArray,
        width: Int,
        height: Int,
        mesh: MeshObservation,
    ): Analysis {
        require(luma.size == width * height)
        val face = mesh.faceBounds
        val normalizedFace = NormalizedFace(
            centerX = ((face.left + face.right) / 2.0 / width).coerceIn(0.0, 1.0),
            centerY = ((face.top + face.bottom) / 2.0 / height).coerceIn(0.0, 1.0),
            width = (face.width / width).coerceIn(0.0, 1.0),
            height = (face.height / height).coerceIn(0.0, 1.0),
            rollRadians = mesh.rollRadians,
            yawProxy = mesh.yawProxy,
        )
        val old = analysisPrevious
        val compatible = old != null && previousWidth == width && previousHeight == height
        analysisPrevious = luma.copyOf()
        previousWidth = width
        previousHeight = height
        if (!compatible || mesh.mouthLandmarks.isEmpty()) {
            return Analysis(normalizedFace, 0.0, 0.0, 0.5)
        }

        val minX = mesh.mouthLandmarks.minOf { it.x }
        val maxX = mesh.mouthLandmarks.maxOf { it.x }
        val minY = mesh.mouthLandmarks.minOf { it.y }
        val maxY = mesh.mouthLandmarks.maxOf { it.y }
        val padX = max(maxX - minX, face.width * 0.12)
        val padY = max(maxY - minY, face.height * 0.08)
        val left = (minX - padX).toInt().coerceIn(0, width - 1)
        val right = (maxX + padX).toInt().coerceIn(left + 1, width)
        val top = (minY - padY).toInt().coerceIn(0, height - 1)
        val bottom = (maxY + padY).toInt().coerceIn(top + 1, height)

        var globalDifference = 0L
        var globalSamples = 0
        for (index in luma.indices step 4) {
            globalDifference += abs(unsigned(luma[index]) - unsigned(old!![index]))
            globalSamples++
        }

        var localDifference = 0L
        var localSamples = 0
        var localLumaTotal = 0L
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val index = y * width + x
                localDifference += abs(unsigned(luma[index]) - unsigned(old!![index]))
                localLumaTotal += unsigned(luma[index])
                localSamples++
            }
        }
        val localMean = if (localSamples == 0) 0.0 else localLumaTotal.toDouble() / localSamples
        var weightedX = 0.0
        var weight = 0.0
        for (y in top until bottom step 2) {
            for (x in left until right step 2) {
                val contrast = abs(unsigned(luma[y * width + x]) - localMean)
                val contrastWeight = contrast * contrast
                weightedX += contrastWeight * x
                weight += contrastWeight
            }
        }
        val motionX = if (weight > 0.0) {
            ((weightedX / weight - left) / (right - left).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        return Analysis(
            face = normalizedFace,
            localLumaDelta = if (localSamples == 0) 0.0 else localDifference.toDouble() / localSamples,
            globalLumaDelta = if (globalSamples == 0) 0.0 else globalDifference.toDouble() / globalSamples,
            mouthMotionX = motionX,
        )
    }

    fun reset() {
        preGatePrevious = null
        analysisPrevious = null
        previousWidth = 0
        previousHeight = 0
    }

    private fun unsigned(value: Byte) = value.toInt() and 0xff
}
