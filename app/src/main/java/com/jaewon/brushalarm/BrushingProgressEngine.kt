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
