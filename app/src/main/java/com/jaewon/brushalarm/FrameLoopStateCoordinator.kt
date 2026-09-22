package com.jaewon.brushalarm

/** Owns the single scheduling decision for the preview-frame loop. */
class FrameLoopStateCoordinator {
    private var started = false
    private var cameraReady = false

    var running = false
        private set

    fun onStart(): Boolean {
        started = true
        return startIfReady()
    }

    fun onCameraReady(): Boolean {
        cameraReady = true
        return startIfReady()
    }

    fun onStop() {
        started = false
        running = false
    }

    private fun startIfReady(): Boolean {
        if (!started || !cameraReady || running) return false
        running = true
        return true
    }
}