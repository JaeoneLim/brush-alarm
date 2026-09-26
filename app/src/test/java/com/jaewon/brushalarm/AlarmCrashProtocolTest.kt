package com.jaewon.brushalarm

import org.junit.Assert.*
import org.junit.Test

/** Failure injection at each durable-write / AlarmManager-call boundary. */
class AlarmCrashProtocolTest {
    private val old = 1_800_000L
    private val next = 2_400_000L

    private class CrashWorld(
        override var active: Long? = null,
        val os: MutableSet<Pair<Long, WakeKind>> = mutableSetOf(),
    ) : AlarmCrashPort {
        override var staged: Long? = null
        override var pending: Long? = null
        override var enabled = true
        override var skipped: Long? = null
        override var acknowledged = false
        override fun install(at: Long, kind: WakeKind) { os.add(at to kind) }
        override fun cancel(at: Long, kind: WakeKind) { os.remove(at to kind) }
        override fun acknowledge(at: Long): Boolean {
            if (pending != at || !enabled) return false
            pending = null
            if (active == at) active = null // One-off retired; a recurring row installed next first.
            return true
        }
    }

    @Test fun activationIsNotVisibleBeforeBothOsTokensExist() {
        for (boundary in 1..4) {
            val world = CrashWorld()
            world.enabled = false
            runCatching {
                AlarmCrashProtocol.replaceEdited(world, next,
                    commit = { world.active = next; world.enabled = true },
                    boundary = { if (it == boundary) error("process died") })
            }
            if (world.enabled) {
                assertEquals(next, world.active)
                assertTrue(next to WakeKind.PRIMARY in world.os)
                assertTrue(next to WakeKind.WATCHDOG in world.os)
            } else assertNull(world.active)
        }
    }

    @Test fun editedScheduleIsNotVisibleUntilItsEarlierWakeIsInstalled() {
        for (boundary in 1..4) {
            val world = CrashWorld(active = old,
                os = mutableSetOf(old to WakeKind.PRIMARY, old to WakeKind.WATCHDOG))
            var displayedEpoch = old
            runCatching {
                AlarmCrashProtocol.replaceEdited(world, next,
                    commit = { displayedEpoch = next; world.active = next },
                    boundary = { if (it == boundary) error("process died") })
            }
            if (displayedEpoch == next) {
                assertTrue(next to WakeKind.PRIMARY in world.os)
                assertTrue(next to WakeKind.WATCHDOG in world.os)
            } else {
                assertEquals(old, world.active)
                assertTrue(old to WakeKind.PRIMARY in world.os)
            }
        }
    }

    @Test fun replacementRetainsAnOsWakeAtEveryCrashBoundary() {
        for (boundary in 1..5) {
            val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.PRIMARY, old to WakeKind.WATCHDOG))
            runCatching { AlarmCrashProtocol.replace(world, next) { if (it == boundary) error("process died") } }
            assertTrue("boundary $boundary", world.os.any { it.first == old || it.first == next })
            if (world.active == next) {
                assertTrue("committed replacement must have primary", next to WakeKind.PRIMARY in world.os)
                assertTrue("committed replacement must have retry", next to WakeKind.WATCHDOG in world.os)
            }
        }
    }

    @Test fun initialScheduleIsConfirmedOnlyAfterBothOsTokensExist() {
        val complete = CrashWorld()
        AlarmCrashProtocol.replace(complete, next) {}
        assertEquals(next, complete.active)
        assertTrue(next to WakeKind.PRIMARY in complete.os)
        assertTrue(next to WakeKind.WATCHDOG in complete.os)
        for (boundary in 1..5) {
            val world = CrashWorld()
            runCatching { AlarmCrashProtocol.replace(world, next) { if (it == boundary) error("process died") } }
            if (world.active == next) {
                assertTrue(next to WakeKind.PRIMARY in world.os)
                assertTrue(next to WakeKind.WATCHDOG in world.os)
            }
        }
    }

    @Test fun acknowledgedRingWithFailedNextScheduleRetainsRetryWithoutRingingAgain() {
        val world = CrashWorld(active = old,
            os = mutableSetOf(old to WakeKind.WATCHDOG))
        world.pending = old
        assertTrue(AlarmCrashProtocol.ack(world, old, foreground = true, sound = true,
            retainRetryForNext = true))
        assertNull(world.pending)
        assertTrue(old to WakeKind.WATCHDOG in world.os)
        assertFalse(AlarmCrashProtocol.deliver(world, old) {})
        assertTrue(shouldRetryNextWithoutRinging(acknowledgedAt = old, deliveredAt = old))
    }

    @Test fun deliveryKeepsRetryUntilSoundAndForegroundAreAcknowledged() {
        for (boundary in 1..4) {
            val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.WATCHDOG))
            runCatching { AlarmCrashProtocol.deliver(world, old) { if (it == boundary) error("process died") } }
            assertTrue("boundary $boundary", old to WakeKind.WATCHDOG in world.os)
            assertFalse(world.acknowledged)
        }
        val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.WATCHDOG))
        AlarmCrashProtocol.deliver(world, old) {}
        assertTrue(world.pending == old)
        assertFalse(world.acknowledged)
        AlarmCrashProtocol.ack(world, old, foreground = true, sound = true)
        assertTrue(world.acknowledged)
        assertFalse(old to WakeKind.WATCHDOG in world.os)
    }

    @Test fun replacingDuringRingingKeepsPriorWatchdogUntilAcknowledgment() {
        val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.WATCHDOG))
        world.pending = old
        AlarmCrashProtocol.replace(world, next) {}
        assertTrue(old to WakeKind.WATCHDOG in world.os)
        assertTrue(next to WakeKind.PRIMARY in world.os)
        assertTrue(AlarmCrashProtocol.ack(world, old, foreground = true, sound = true))
        assertFalse(old to WakeKind.WATCHDOG in world.os)
        assertTrue(next to WakeKind.WATCHDOG in world.os)
    }

    @Test fun skipWrittenBeforeReplacementBlocksOldDeliveryAcrossCrashes() {
        for (boundary in 1..5) {
            val world = CrashWorld(active = old,
                os = mutableSetOf(old to WakeKind.PRIMARY, old to WakeKind.WATCHDOG))
            world.skipped = old
            runCatching { AlarmCrashProtocol.replace(world, next) { if (it == boundary) error("crash") } }
            assertFalse(AlarmCrashProtocol.deliver(world, old) {})
            assertFalse(world.acknowledged)
            assertTrue(world.os.any { it.first == old || it.first == next })
        }
    }

    @Test fun acknowledgmentRequiresBothSoundAndForegroundAndExactPendingEpoch() {
        val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.WATCHDOG))
        world.pending = old
        assertFalse(AlarmCrashProtocol.ack(world, old, foreground = false, sound = true))
        assertFalse(AlarmCrashProtocol.ack(world, old, foreground = true, sound = false))
        assertFalse(AlarmCrashProtocol.ack(world, next, foreground = true, sound = true))
        assertEquals(old, world.pending)
        assertTrue(old to WakeKind.WATCHDOG in world.os)
    }

    @Test fun ackCrashBeforeDurableWriteRetainsRetryAndAfterWriteCannotReplay() {
        for (boundary in 1..2) {
            val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.WATCHDOG))
            world.pending = old
            runCatching { AlarmCrashProtocol.ack(world, old, foreground = true, sound = true) {
                if (it == boundary) error("process died")
            } }
            if (boundary == 1) {
                assertEquals(old, world.pending)
                assertTrue(old to WakeKind.WATCHDOG in world.os)
                assertTrue(AlarmCrashProtocol.deliver(world, old) {})
            } else {
                assertNull(world.pending)
                assertTrue(old to WakeKind.WATCHDOG in world.os)
                assertFalse(AlarmCrashProtocol.deliver(world, old) {})
            }
        }
    }

    @Test fun offAndSkipWinAgainstQueuedDeliveryAndRepeatedRetry() {
        val world = CrashWorld(active = old, os = mutableSetOf(old to WakeKind.WATCHDOG))
        world.skipped = old
        assertFalse(AlarmCrashProtocol.deliver(world, old) {})
        world.skipped = null
        assertTrue(AlarmCrashProtocol.deliver(world, old) {})
        assertTrue(AlarmCrashProtocol.deliver(world, old) {}) // Retry can re-dispatch to the singleton service.
        world.enabled = false
        assertFalse(AlarmCrashProtocol.ack(world, old, foreground = true, sound = true))
    }
}
