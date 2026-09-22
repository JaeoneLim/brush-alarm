package com.jaewon.brushalarm

/** Keeps warm-up attempts retryable while rejecting callbacks from abandoned attempts. */
class WarmUpStateCoordinator {
    var modelReady = false
        private set
    var warmingUp = false
        private set

    private var attemptId = 0L

    fun onStart(): Long? {
        if (modelReady || warmingUp) return null
        warmingUp = true
        attemptId += 1
        return attemptId
    }

    fun onStop() {
        warmingUp = false
        attemptId += 1
    }

    fun onReady(attempt: Long): Boolean {
        if (!warmingUp || attempt != attemptId) return false
        warmingUp = false
        modelReady = true
        return true
    }

    fun onFailure(attempt: Long): Boolean {
        if (!warmingUp || attempt != attemptId) return false
        warmingUp = false
        modelReady = false
        return true
    }
}