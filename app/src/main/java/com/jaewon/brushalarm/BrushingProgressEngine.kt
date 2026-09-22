package com.jaewon.brushalarm

class BrushingProgressEngine(
    private val requiredMillis: Long = 30_000,
    private val maxFrameGapMillis: Long = 60_000,
) {
    var accumulatedMillis: Long = 0
        private set

    val isComplete: Boolean
        get() = accumulatedMillis >= requiredMillis

    private var lastValidTimestamp: Long? = null

    fun update(timestampMillis: Long, brushingDetected: Boolean) {
        if (!brushingDetected || isComplete) {
            lastValidTimestamp = null
            return
        }

        lastValidTimestamp?.let { previous ->
            val delta = timestampMillis - previous
            if (delta in 0..maxFrameGapMillis) {
                accumulatedMillis = (accumulatedMillis + delta).coerceAtMost(requiredMillis)
            }
        }
        lastValidTimestamp = timestampMillis
    }
}

/** Applies only known frame outcomes; dropped busy/throttled frames remain unknown. */
class FrameProgressController(private val progress: BrushingProgressEngine) {
    private var newestConclusiveTimestamp: Long? = null

    /**
     * Claims the capture timestamp before evaluating any stateful detection work. A callback that
     * lost to a newer conclusive frame returns without invoking either lambda.
     */
    fun <T> onVerified(
        timestampMillis: Long,
        evaluate: () -> T,
        isBrushing: (T) -> Boolean,
        onAccepted: (T) -> Unit,
    ): Boolean {
        if (!tryAccept(timestampMillis)) return false
        val result = evaluate()
        progress.update(timestampMillis, isBrushing(result))
        onAccepted(result)
        return true
    }

    fun onFailure(timestampMillis: Long, onAccepted: () -> Unit): Boolean {
        if (!tryAccept(timestampMillis)) return false
        progress.update(timestampMillis, false)
        onAccepted()
        return true
    }

    fun onPreGateRejected(timestampMillis: Long): Boolean {
        if (!tryAccept(timestampMillis)) return false
        progress.update(timestampMillis, false)
        return true
    }

    fun onUnverifiedFrame() = Unit

    @Synchronized
    private fun tryAccept(timestampMillis: Long): Boolean {
        val newest = newestConclusiveTimestamp
        if (newest != null && timestampMillis < newest) return false
        newestConclusiveTimestamp = timestampMillis
        return true
    }
}
