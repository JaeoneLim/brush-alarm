package com.jaewon.brushalarm

/** Durable writes and OS operations are deliberately separate failure-injection boundaries. */
enum class WakeKind { PRIMARY, WATCHDOG }

interface AlarmCrashPort {
    var active: Long?
    var staged: Long?
    var pending: Long?
    var enabled: Boolean
    var skipped: Long?
    var acknowledged: Boolean
    fun install(at: Long, kind: WakeKind)
    fun cancel(at: Long, kind: WakeKind)
    fun acknowledge(at: Long): Boolean
}

object AlarmCrashProtocol {
    /** Keep the old row/token authoritative until the edited row has both OS wakes. */
    fun replaceEdited(port: AlarmCrashPort, next: Long, commit: () -> Unit,
        boundary: (Int) -> Unit = {}) {
        val prior = port.active
        port.install(next, WakeKind.WATCHDOG)
        boundary(1)
        port.install(next, WakeKind.PRIMARY)
        boundary(2)
        commit() // Atomically persist edited fields, epoch, and commit marker.
        boundary(3)
        if (prior != null && prior != next && port.pending != prior) {
            port.cancel(prior, WakeKind.PRIMARY)
            port.cancel(prior, WakeKind.WATCHDOG)
        }
        boundary(4)
    }

    fun replace(port: AlarmCrashPort, next: Long, boundary: (Int) -> Unit) {
        val prior = port.active
        port.staged = next
        boundary(1)
        // The retry is installed first; even an interrupted primary install has a wake source.
        port.install(next, WakeKind.WATCHDOG)
        boundary(2)
        port.install(next, WakeKind.PRIMARY)
        boundary(3)
        port.active = next
        boundary(4)
        if (prior != null && prior != next && port.pending != prior) {
            port.cancel(prior, WakeKind.PRIMARY)
            port.cancel(prior, WakeKind.WATCHDOG)
        }
        boundary(5)
        port.staged = null
    }

    fun deliver(port: AlarmCrashPort, at: Long, boundary: (Int) -> Unit): Boolean {
        if (!port.enabled || port.skipped == at ||
            (port.active != at && port.staged != at && port.pending != at)) return false
        // Re-arm BEFORE consuming the OS broadcast or changing the durable delivery state.
        port.install(at, WakeKind.WATCHDOG)
        boundary(1)
        if (port.pending != at) port.pending = at
        boundary(2)
        boundary(3)
        boundary(4)
        return true
    }

    fun ack(port: AlarmCrashPort, at: Long, foreground: Boolean, sound: Boolean,
        retainRetryForNext: Boolean = false, boundary: (Int) -> Unit = {}): Boolean {
        if (!port.enabled || !foreground || !sound || port.pending != at) return false
        boundary(1)
        if (!port.acknowledge(at)) return false
        port.acknowledged = true
        boundary(2)
        port.cancel(at, WakeKind.PRIMARY)
        if (!retainRetryForNext) port.cancel(at, WakeKind.WATCHDOG)
        return true
    }
}
