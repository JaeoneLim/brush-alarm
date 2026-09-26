package com.jaewon.brushalarm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MultiAlarmSchedulerTest {
    private val zone = ZoneId.of("UTC")
    private val fired = Instant.parse("2026-10-03T09:30:00Z").toEpochMilli()
    private val weekly = AlarmEntry(3, 9, 30, setOf(DayOfWeek.SATURDAY), null, true, fired)

    @Test fun failedNextScheduleAcknowledgesCompletedRingAndWatchdogOnlyRetriesScheduling() {
        assertTrue(shouldRetryNextWithoutRinging(fired, fired))
        assertFalse(shouldRetryNextWithoutRinging(null, fired))
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        val ack = source.substringAfter("fun acknowledgeRinging(").substringBefore("private fun dispatch(")
        assertTrue(ack.contains("retainRetryForNext = !nextScheduled"))
        val fire = source.substringAfter("fun handleFired(").substringBefore("fun isPending(")
        assertTrue(fire.contains("shouldRetryNextWithoutRinging(store.acknowledgedAt(id), scheduledAtMillis)"))
        assertTrue(fire.indexOf("shouldRetryNextWithoutRinging(store.acknowledgedAt(id), scheduledAtMillis)") <
            fire.indexOf("AlarmCrashProtocol.deliver("))
        val store = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmStore.kt").readText()
        assertTrue(store.contains(".putLong(\"ack_${'$'}id\", at)"))
    }

    @Test fun recoveringStagedEpochUsesANewCommittedWakeNotTheInterruptedWake() {
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        val recovery = source.substringAfter("val staged = store.stagedAt(entry.id)")
            .substringBefore("val at = entry.scheduledAtMillis")
        assertFalse(recovery.contains("dispatch(context, entry.id, staged)"))
        val fire = source.substringAfter("fun handleFired(").substringBefore("val valid = shouldAcceptScheduledWake(")
        val staged = fire.substringAfter("if (store.stagedAt(id) == scheduledAtMillis")
        assertTrue(staged.contains("if (!scheduleEpoch(context, id, scheduledAtMillis)) return false"))
        assertTrue(staged.contains("return false"))
        assertFalse(staged.contains("entry = store.find(id)"))
    }

    @Test fun stagedButUncommittedWakeMustFinishInstallationBeforeDelivery() {
        assertFalse(shouldAcceptScheduledWake(weekly, fired, committedAt = null,
            stagedAt = fired, pendingAt = null))
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        val fire = source.substringAfter("fun handleFired(").substringBefore("fun isPending(")
        assertTrue(fire.contains("if (store.stagedAt(id) == scheduledAtMillis &&"))
        assertTrue(fire.indexOf("scheduleEpoch(context, id, scheduledAtMillis)") <
            fire.indexOf("shouldAcceptScheduledWake(entry, scheduledAtMillis,"))
    }

    @Test fun editedScheduleRejectsOldUncommittedBroadcast() {
        val edited = weekly.copy(hour = 10) // The UI retains old scheduledAt while replacing it.
        assertFalse(shouldAcceptScheduledWake(edited, fired, committedAt = null,
            stagedAt = null, pendingAt = null))
        assertTrue(shouldAcceptScheduledWake(weekly, fired, committedAt = fired,
            stagedAt = null, pendingAt = null))
        assertFalse(shouldAcceptScheduledWake(weekly, fired, committedAt = null,
            stagedAt = fired, pendingAt = null))
        assertTrue(shouldAcceptScheduledWake(weekly, fired, committedAt = null,
            stagedAt = null, pendingAt = fired))
        val scheduler = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        assertTrue(scheduler.contains("shouldAcceptScheduledWake(entry, scheduledAtMillis,"))
        val store = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmStore.kt").readText()
        assertTrue(store.contains("editor.remove(\"pending_${'$'}{entry.id}\").remove(\"staged_${'$'}{entry.id}\")"))
    }

    @Test fun interruptedReplacementCannotPreserveOrphanedPendingToken() {
        val future = weekly.copy(scheduledAtMillis = fired + 7L * 24 * 60 * 60 * 1000)
        assertFalse(hasMatchingAlarmToken(future, broadcastExists = true, committedMarkerExists = false))
        assertEquals(AlarmRecovery.RESCHEDULE,
            alarmRecoveryDecision(future, fired, hasMatchingAlarmToken(future, true, false)))
        assertTrue(hasMatchingAlarmToken(future, broadcastExists = true, committedMarkerExists = true))
        assertEquals(AlarmRecovery.PRESERVE_FUTURE,
            alarmRecoveryDecision(future, fired, hasMatchingAlarmToken(future, true, true)))
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        assertTrue(source.contains("primary && watchdog && store.committedAt(entry.id) == at"))
        assertTrue(source.contains("AlarmCrashProtocol.replace(PlatformPort(context, id), next)"))
        val protocol = java.io.File("src/main/java/com/jaewon/brushalarm/AlarmCrashProtocol.kt").readText()
        assertTrue(protocol.indexOf("port.install(next, WakeKind.WATCHDOG)") <
            protocol.indexOf("port.install(next, WakeKind.PRIMARY)"))
        assertTrue(protocol.indexOf("port.install(next, WakeKind.PRIMARY)") <
            protocol.indexOf("port.active = next"))
    }

    @Test fun skipSavedBeforeAlarmReplacementCannotRingOrBePreserved() {
        val before = fired - 60_000
        val skipped = skipMultiAlarmNext(weekly, before, zone)
        assertTrue(isSkippedOccurrence(skipped, fired, zone))
        assertEquals(AlarmRecovery.RESCHEDULE, alarmRecoveryDecision(skipped, before, true))
        val consumed = consumeSkippedMultiAlarm(skipped, fired, zone)
        assertNotNull(consumed)
        assertNull(consumed!!.scheduledAtMillis)
        assertNull(consumeSkippedMultiAlarm(consumed, fired, zone))
        assertTrue(nextTriggerMillis(consumed, before, zone)!! > fired)
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        assertTrue(source.contains("if (isSkippedOccurrence(entry, scheduledAtMillis)) {"))
        val fire = source.substringAfter("fun handleFired(").substringBefore("fun isPending(")
        assertTrue(fire.indexOf("install(scheduledAtMillis, WakeKind.WATCHDOG)") <
            fire.indexOf("schedule(context, entry, maxOf(scheduledAtMillis, nowMillis))"))
        val skip = source.substringAfter("fun skipNext(context: Context, id: Int,").substringBefore("fun cancel(")
        assertFalse(skip.contains("store.update(entry)"))
    }

    @Test fun watchdogOnlyAfterInterruptedInstallKeepsLateOneOffDelivery() {
        val once = weekly.copy(weekdays = emptySet(), date = LocalDate.parse("2026-10-03"))
        assertEquals(AlarmRecovery.DELIVER_DUE,
            alarmRecoveryDecision(once, fired + 3_600_000,
                pendingExists = false, unverifiedPendingExists = true))
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        assertTrue(source.contains("unverifiedPendingExists = !force && (primary || watchdog || oldToken)"))
    }

    @Test fun delayedOneOffAfterInterruptedCommitKeepsPhysicalAlarmDelivery() {
        val once = weekly.copy(weekdays = emptySet(), date = LocalDate.parse("2026-10-03"))
        assertEquals(AlarmRecovery.DELIVER_DUE,
            alarmRecoveryDecision(once, fired + 3_600_000,
                pendingExists = false, unverifiedPendingExists = true))
        assertEquals(AlarmRecovery.RETIRE_ONE_OFF,
            alarmRecoveryDecision(once, fired + 3_600_000,
                pendingExists = false, unverifiedPendingExists = false))
        assertEquals(AlarmRecovery.RESCHEDULE,
            alarmRecoveryDecision(weekly.copy(scheduledAtMillis = fired + 604_800_000), fired,
                pendingExists = false, unverifiedPendingExists = true))
    }

    @Test fun dueOccurrenceIsDeliveredOnceOnResumeEvenIfPendingTokenExists() {
        assertEquals(AlarmRecovery.DELIVER_DUE,
            alarmRecoveryDecision(weekly, fired + 30_000, pendingExists = true))
        assertEquals(AlarmRecovery.DELIVER_DUE,
            alarmRecoveryDecision(weekly, fired + 30_000, pendingExists = false))
    }

    @Test fun v02PendingSurvivesRowlessUpgradeUntilItCanFire() {
        assertTrue(shouldAcceptV02Delivery(migrated = true, savedScheduleExists = false,
            legacyScheduleEnabled = false, legacyV02Allowed = true))
        assertFalse(shouldAcceptV02Delivery(migrated = true, savedScheduleExists = false,
            legacyScheduleEnabled = false, legacyV02Allowed = false))
        assertFalse(shouldAcceptV02Delivery(migrated = true, savedScheduleExists = true,
            legacyScheduleEnabled = false, legacyV02Allowed = true))
        val source = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        assertTrue(source.contains("if (!store.legacyV02Allowed()) cancelOrphanedV02(context)"))
        val receiver = java.io.File("src/main/java/com/jaewon/brushalarm/AlarmReceiver.kt").readText()
        assertTrue(receiver.contains("store.legacyV02Allowed()"))
    }

    @Test fun actionlessLegacyBeforeMigrationRespectsSavedOffState() {
        assertTrue(shouldAcceptV02Delivery(false, false, false)) // v0.2 has no v0.3 schedule.
        assertTrue(shouldAcceptV02Delivery(false, true, true))
        assertFalse(shouldAcceptV02Delivery(false, true, false))
        assertFalse(shouldAcceptV02Delivery(true, true, true))
        val receiver = java.io.File("src/main/java/com/jaewon/brushalarm/AlarmReceiver.kt").readText()
        assertTrue(receiver.contains("legacyPrefs.contains(\"hour\")"))
        assertTrue(receiver.contains("AlarmPreferences(context).load().schedule.enabled"))
        assertTrue(receiver.contains("shouldAcceptV02Delivery("))
    }

    @Test fun actionlessLegacyCannotRingAfterMigrationAndIsCancelled() {
        assertFalse(shouldAcceptV02Delivery(true, false, false))
        val scheduler = java.io.File("src/main/java/com/jaewon/brushalarm/MultiAlarmScheduler.kt").readText()
        assertTrue(scheduler.contains("cancelOrphanedV02(context)"))
    }

    @Test fun delayedOneOffWithPendingDeliveryIsNotRetiredOnResume() {
        val once = weekly.copy(weekdays = emptySet(), date = LocalDate.parse("2026-10-03"))
        assertEquals(AlarmRecovery.DELIVER_DUE,
            alarmRecoveryDecision(once, fired + 3_600_000, pendingExists = true))
    }

    @Test fun oldMissedOccurrenceDoesNotLeaveAOneOffEnabledForever() {
        val once = weekly.copy(weekdays = emptySet(), date = LocalDate.parse("2026-10-03"))
        assertEquals(AlarmRecovery.RETIRE_ONE_OFF,
            alarmRecoveryDecision(once, fired + 3_600_000, pendingExists = false))
        assertEquals(AlarmRecovery.RESCHEDULE,
            alarmRecoveryDecision(weekly, fired + 3_600_000, pendingExists = false))
    }

    @Test fun acceptsExactlyOneMatchingEnabledDelivery() {
        assertNotNull(nextAfterMultiAlarmFire(weekly, fired, fired + 1000, zone))
        assertNull(nextAfterMultiAlarmFire(weekly.copy(enabled = false), fired, fired, zone))
        assertNull(nextAfterMultiAlarmFire(weekly.copy(scheduledAtMillis = null), fired, fired, zone))
        assertNull(nextAfterMultiAlarmFire(weekly, fired - 1000, fired, zone))
    }

    @Test fun lateWeeklyDeliveryAdvancesFromNowNotPastTrigger() {
        val late = fired + 8L * 24 * 60 * 60 * 1000
        val next = nextAfterMultiAlarmFire(weekly, fired, late, zone)
        assertTrue(next != null && next > late)
    }

    @Test fun exposesPerEntrySkipApi() {
        assertNotNull(MultiAlarmScheduler::skipNext)
    }

    @Test fun consumedOccurrenceCannotBeDeliveredTwice() {
        val consumed = consumeMultiAlarm(weekly, fired)
        assertNotNull(consumed)
        assertNull(consumed!!.scheduledAtMillis)
        assertNull(consumeMultiAlarm(consumed, fired))
        val once = weekly.copy(weekdays = emptySet(), date = LocalDate.parse("2026-10-03"))
        assertFalse(consumeMultiAlarm(once, fired)!!.enabled)
    }

    @Test fun duePendingDeliveryIsPreservedEvenBeyondGrace() {
        assertTrue(shouldPreserveDueDelivery(fired, fired + 3600000, true))
        assertFalse(shouldPreserveDueDelivery(fired, fired + 3600000, false))
    }

    @Test fun explicitCancelOfMigratedEntryAlsoTargetsOldPendingIntent() {
        assertTrue(shouldCancelLegacyPending(1))
        assertFalse(shouldCancelLegacyPending(2))
    }

    @Test fun bootRunsOldSchedulerOnlyBeforeMigration() {
        assertTrue(legacyNeedsBootReschedule(false))
        assertFalse(legacyNeedsBootReschedule(true))
    }

    @Test fun migratedLegacyFireRoutesOnlyMatchingSavedOccurrence() {
        assertTrue(shouldRouteLegacyToMulti(weekly, fired))
        assertFalse(shouldRouteLegacyToMulti(weekly, fired - 1))
        assertFalse(shouldRouteLegacyToMulti(weekly.copy(enabled = false), fired))
    }

    @Test fun oneOffHasNoFollowingTrigger() {
        val once = weekly.copy(weekdays = emptySet(), date = LocalDate.parse("2026-10-03"))
        assertTrue(shouldDeliverMultiAlarm(once, fired))
        assertNull(nextAfterMultiAlarmFire(once, fired, fired, zone))
    }
}
