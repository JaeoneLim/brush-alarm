package com.jaewon.brushalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.time.Instant
import java.time.ZoneId

fun shouldCancelLegacyPending(id: Int): Boolean = id == 1

fun shouldPreserveDueDelivery(scheduledAtMillis: Long?, nowMillis: Long,
    pendingExists: Boolean): Boolean =
    pendingExists && scheduledAtMillis != null && scheduledAtMillis <= nowMillis

fun legacyNeedsBootReschedule(migrated: Boolean): Boolean = !migrated

fun shouldAcceptV02Delivery(migrated: Boolean, savedScheduleExists: Boolean,
    legacyScheduleEnabled: Boolean, legacyV02Allowed: Boolean = false): Boolean =
    (!migrated && (!savedScheduleExists || legacyScheduleEnabled)) ||
        (migrated && !savedScheduleExists && legacyV02Allowed)

fun shouldRouteLegacyToMulti(entry: AlarmEntry?, scheduledAtMillis: Long): Boolean =
    entry?.date == null && shouldDeliverMultiAlarm(entry, scheduledAtMillis)

fun shouldAcceptScheduledWake(entry: AlarmEntry, at: Long, committedAt: Long?,
    @Suppress("UNUSED_PARAMETER") stagedAt: Long?, pendingAt: Long?): Boolean =
    entry.enabled && ((entry.scheduledAtMillis == at && committedAt == at) || pendingAt == at)

fun shouldRetryNextWithoutRinging(acknowledgedAt: Long?, deliveredAt: Long): Boolean =
    acknowledgedAt == deliveredAt

fun shouldDeliverMultiAlarm(entry: AlarmEntry?, scheduledAtMillis: Long): Boolean =
    entry?.enabled == true && entry.scheduledAtMillis == scheduledAtMillis

enum class AlarmRecovery { CANCEL, PRESERVE_FUTURE, DELIVER_DUE, RESCHEDULE, RETIRE_ONE_OFF }

fun isSkippedOccurrence(entry: AlarmEntry, scheduledAtMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()): Boolean =
    entry.enabled && entry.date == null && entry.scheduledAtMillis == scheduledAtMillis &&
        (entry.skippedAtMillis == scheduledAtMillis ||
            entry.skippedLocalDate == Instant.ofEpochMilli(scheduledAtMillis).atZone(zone).toLocalDate())

fun consumeSkippedMultiAlarm(entry: AlarmEntry?, scheduledAtMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()): AlarmEntry? =
    entry?.takeIf { isSkippedOccurrence(it, scheduledAtMillis, zone) }
        ?.copy(scheduledAtMillis = null)

fun alarmRecoveryDecision(entry: AlarmEntry, nowMillis: Long,
    pendingExists: Boolean, unverifiedPendingExists: Boolean = false): AlarmRecovery {
    if (!entry.enabled) return AlarmRecovery.CANCEL
    val due = entry.scheduledAtMillis
    if (due != null && isSkippedOccurrence(entry, due)) return AlarmRecovery.RESCHEDULE
    // Explicit delivery wins the receiver race. The receiver consumes the timestamp only once.
    if (due != null && due <= nowMillis &&
        (nowMillis - due <= 120_000L || pendingExists || unverifiedPendingExists)) {
        return AlarmRecovery.DELIVER_DUE
    }
    if (entry.date != null && nextTriggerMillis(entry, nowMillis) == null) {
        return AlarmRecovery.RETIRE_ONE_OFF
    }
    return if (pendingExists && due != null && due > nowMillis)
        AlarmRecovery.PRESERVE_FUTURE else AlarmRecovery.RESCHEDULE
}

fun hasMatchingAlarmToken(entry: AlarmEntry, broadcastExists: Boolean,
    committedMarkerExists: Boolean): Boolean =
    entry.scheduledAtMillis != null && broadcastExists && committedMarkerExists

fun consumeMultiAlarm(entry: AlarmEntry?, scheduledAtMillis: Long): AlarmEntry? =
    entry?.takeIf { shouldDeliverMultiAlarm(it, scheduledAtMillis) }
        ?.copy(enabled = entry.date == null, scheduledAtMillis = null)

fun nextAfterMultiAlarmFire(entry: AlarmEntry, scheduledAtMillis: Long,
    nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (!shouldDeliverMultiAlarm(entry, scheduledAtMillis)) return null
    return if (entry.date != null) null else nextTriggerMillis(
        entry, maxOf(scheduledAtMillis, nowMillis), zone,
    )
}

object MultiAlarmScheduler {
    const val ACTION_MULTI_ALARM = "com.jaewon.brushalarm.action.MULTI_ALARM"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_SCHEDULED_AT = "scheduled_at"
    private const val REQUEST_BASE = 10000

    fun rescheduleAll(context: Context, nowMillis: Long = System.currentTimeMillis(),
        force: Boolean = false) {
        val store = MultiAlarmStore(context)
        store.load() // Finish migration before deciding whether an old actionless alarm is orphaned.
        if (!store.legacyV02Allowed()) cancelOrphanedV02(context)
        if (!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return
        store.load().forEach { entry ->
            if (!entry.enabled) { cancel(context, entry.id); return@forEach }
            val pending = store.pendingAt(entry.id)
            if (pending != null) {
                if (force) runCatching { PlatformPort(context, entry.id).install(pending, WakeKind.WATCHDOG) }
                dispatch(context, entry.id, pending)
                return@forEach
            }
            val staged = store.stagedAt(entry.id)
            if (staged != null) {
                // Installing a past-due primary hands delivery back to AlarmManager;
                // the interrupted staged wake itself is not a ringing request.
                scheduleEpoch(context, entry.id, staged)
                return@forEach
            }
            val at = entry.scheduledAtMillis
            val primary = at != null && existing(context, entry.id, at, WakeKind.PRIMARY) != null
            val watchdog = at != null && existing(context, entry.id, at, WakeKind.WATCHDOG) != null
            val matched = !force && at != null && primary && watchdog && store.committedAt(entry.id) == at
            val oldToken = entry.id == 1 && legacyPending(context) != null && legacyMatches(context, entry)
            when (alarmRecoveryDecision(entry, nowMillis, matched,
                unverifiedPendingExists = !force && (primary || watchdog || oldToken))) {
                AlarmRecovery.CANCEL -> cancel(context, entry.id)
                AlarmRecovery.PRESERVE_FUTURE -> Unit
                AlarmRecovery.DELIVER_DUE -> dispatch(context, entry.id, requireNotNull(at))
                AlarmRecovery.RESCHEDULE -> schedule(context, entry, nowMillis)
                AlarmRecovery.RETIRE_ONE_OFF -> {
                    store.update(entry.copy(enabled = false, scheduledAtMillis = null))
                    cancel(context, entry.id)
                }
            }
        }
    }

    fun schedule(context: Context, entry: AlarmEntry,
        nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        val current = store.find(entry.id) ?: return false
        if (!current.enabled) return false
        val next = nextTriggerMillis(current, nowMillis)
        if (next == null) return false // Preserve stored timestamp if scheduling is unavailable.
        return scheduleEpoch(context, current.id, next)
    }

    private fun scheduleEpoch(context: Context, id: Int, next: Long): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        val current = store.find(id) ?: return false
        if (!current.enabled || !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return false
        try {
            AlarmCrashProtocol.replace(PlatformPort(context, id), next) {}
        } catch (_: SecurityException) { return false }
        val retryAt = store.acknowledgedAt(id)
        if (retryAt != null && retryAt != next) {
            PlatformPort(context, id).cancel(retryAt, WakeKind.WATCHDOG)
        }
        // Keep a due legacy token until its receiver has a chance to deliver.
        if (id == 1 && legacyMatches(context, current) &&
            (current.scheduledAtMillis == null || current.scheduledAtMillis > System.currentTimeMillis())) {
            RecurringAlarmScheduler.cancel(context)
        }
        true
    }

    /** A newly created or OFF row becomes ON only after both platform wakes exist. */
    fun activate(context: Context, entry: AlarmEntry,
        nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        val previous = store.find(entry.id) ?: return false
        if (previous.enabled || store.pendingAt(entry.id) != null ||
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return false
        val next = nextTriggerMillis(previous.copy(enabled = true, scheduledAtMillis = null,
            skippedAtMillis = null, skippedLocalDate = null), nowMillis) ?: return false
        val port = PlatformPort(context, entry.id)
        try {
            AlarmCrashProtocol.replaceEdited(port, next,
                commit = { store.commitActivation(entry.id, next) })
        } catch (_: SecurityException) {
            port.cancel(next, WakeKind.PRIMARY)
            port.cancel(next, WakeKind.WATCHDOG)
            return false
        }
        true
    }

    /** Install the edited schedule before making its fields visible as the confirmed row. */
    fun replaceEdited(context: Context, edited: AlarmEntry,
        nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        val previous = store.find(edited.id) ?: return false
        if (!previous.enabled || !edited.enabled || store.pendingAt(edited.id) != null ||
            !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return false
        val next = nextTriggerMillis(edited.copy(scheduledAtMillis = null,
            skippedAtMillis = null, skippedLocalDate = null), nowMillis) ?: return false
        val port = PlatformPort(context, edited.id)
        try {
            AlarmCrashProtocol.replaceEdited(port, next,
                commit = { store.commitEditedSchedule(edited, next) })
        } catch (_: SecurityException) {
            // The existing wake is still authoritative; never cancel it for an identical epoch.
            if (next != previous.scheduledAtMillis) {
                port.cancel(next, WakeKind.PRIMARY)
                port.cancel(next, WakeKind.WATCHDOG)
            }
            return false
        }
        if (edited.id == 1 && legacyMatches(context, previous) &&
            (previous.scheduledAtMillis == null || previous.scheduledAtMillis > System.currentTimeMillis())) {
            RecurringAlarmScheduler.cancel(context)
        }
        true
    }

    fun skipNext(context: Context, id: Int,
        nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        val entry = store.find(id) ?: return false
        if (entry.scheduledAtMillis != null && entry.scheduledAtMillis <= nowMillis) return false
        if (!context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) return false
        val skipped = runCatching { skipMultiAlarmNext(entry, nowMillis) }.getOrNull() ?: return false
        store.update(skipped)
        // The skip is durable even when the permission is revoked during replacement.
        // A still-queued old broadcast must be consumed without ringing.
        schedule(context, skipped, nowMillis)
    }

    fun cancel(context: Context, id: Int) = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        store.setCommittedAt(id, null)
        val epochs = store.trackedEpochs(id) + listOfNotNull(store.stagedAt(id),
            store.pendingAt(id), store.find(id)?.scheduledAtMillis)
        val port = PlatformPort(context, id)
        epochs.forEach { at ->
            port.cancel(at, WakeKind.PRIMARY)
            port.cancel(at, WakeKind.WATCHDOG)
        }
        oldStable(context, id)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
        if (shouldCancelLegacyPending(id)) {
            RecurringAlarmScheduler.cancel(context)
            cancelOrphanedV02(context)
        }
    }

    private fun cancelOrphanedV02(context: Context) {
        PendingIntent.getBroadcast(context, 1001,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    fun handleFired(context: Context, id: Int, scheduledAtMillis: Long,
        nowMillis: Long = System.currentTimeMillis()): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        var entry = store.find(id) ?: return false
        if (!entry.enabled) return false
        if (shouldRetryNextWithoutRinging(store.acknowledgedAt(id), scheduledAtMillis)) {
            // A completed brushing session must never ring again just because the
            // following recurrence was not schedulable at the ACK boundary.
            val port = PlatformPort(context, id)
            try { port.install(scheduledAtMillis, WakeKind.WATCHDOG) }
            catch (_: SecurityException) { return false }
            val next = entry.scheduledAtMillis
            val alreadyScheduled = next != null && next != scheduledAtMillis &&
                store.committedAt(id) == next &&
                existing(context, id, next, WakeKind.PRIMARY) != null &&
                existing(context, id, next, WakeKind.WATCHDOG) != null
            val staged = store.stagedAt(id)
            val recovered = alreadyScheduled || (if (staged != null)
                scheduleEpoch(context, id, staged) else schedule(context, entry, nowMillis))
            if (recovered) port.cancel(scheduledAtMillis, WakeKind.WATCHDOG)
            return false
        }
        if (store.stagedAt(id) == scheduledAtMillis && store.pendingAt(id) != scheduledAtMillis &&
            store.committedAt(id) != scheduledAtMillis) {
            // This is an interrupted install, not delivery of a confirmed occurrence.
            // The newly installed primary (and its watchdog) gets its own broadcast.
            if (!scheduleEpoch(context, id, scheduledAtMillis)) return false
            return false
        }
        val valid = shouldAcceptScheduledWake(entry, scheduledAtMillis,
            store.committedAt(id), store.stagedAt(id), store.pendingAt(id)) ||
            (id == 1 && entry.scheduledAtMillis == scheduledAtMillis && legacyMatches(context, entry))
        if (!valid) {
            if (entry.scheduledAtMillis == scheduledAtMillis && store.committedAt(id) != scheduledAtMillis &&
                store.pendingAt(id) == null) schedule(context, entry, nowMillis)
            return false
        }
        if (isSkippedOccurrence(entry, scheduledAtMillis)) {
            // Install another retry before consuming this broadcast. A failed replacement
            // must not strand the skip with no remaining platform wake.
            try { PlatformPort(context, id).install(scheduledAtMillis, WakeKind.WATCHDOG) }
            catch (_: SecurityException) { return false }
            schedule(context, entry, maxOf(scheduledAtMillis, nowMillis))
            return false
        }
        return try {
            AlarmCrashProtocol.deliver(PlatformPort(context, id), scheduledAtMillis) {}
        } catch (_: SecurityException) {
            // If permission vanished, still attempt immediate ringing; retry cannot be guaranteed.
            store.setPendingAt(id, scheduledAtMillis)
            true
        }
    }

    fun isPending(context: Context, id: Int, at: Long): Boolean = synchronized(multiAlarmStoreLock) {
        val store = MultiAlarmStore(context)
        store.find(id)?.enabled == true && store.pendingAt(id) == at
    }

    /** Called only after foreground registration and sound start in the service. */
    fun acknowledgeRinging(context: Context, id: Int, at: Long): Boolean = synchronized(multiAlarmStoreLock) {
        if (!isPending(context, id, at)) return false
        val store = MultiAlarmStore(context)
        val entry = store.find(id) ?: return false
        val nextScheduled = entry.date != null ||
            schedule(context, entry, maxOf(at, System.currentTimeMillis()))
        if (!AlarmCrashProtocol.ack(PlatformPort(context, id), at,
            foreground = true, sound = true,
            retainRetryForNext = !nextScheduled)) return false
        true
    }

    private fun dispatch(context: Context, id: Int, at: Long) {
        context.sendBroadcast(Intent(context, AlarmReceiver::class.java).setAction(ACTION_MULTI_ALARM)
            .putExtra(EXTRA_ALARM_ID, id).putExtra(EXTRA_SCHEDULED_AT, at))
    }

    private fun identity(context: Context, id: Int, at: Long, kind: WakeKind): Intent =
        Intent(context, AlarmReceiver::class.java).setAction(ACTION_MULTI_ALARM)
            .setData(Uri.parse("brushalarm://alarm/$id/$at/${kind.name.lowercase()}"))
            .putExtra(EXTRA_ALARM_ID, id).putExtra(EXTRA_SCHEDULED_AT, at)

    private fun existing(context: Context, id: Int, at: Long, kind: WakeKind): PendingIntent? =
        PendingIntent.getBroadcast(context, REQUEST_BASE + id, identity(context, id, at, kind),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun oldStable(context: Context, id: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, REQUEST_BASE + id,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_MULTI_ALARM),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private class PlatformPort(val context: Context, val id: Int) : AlarmCrashPort {
        private val store get() = MultiAlarmStore(context)
        private val manager get() = context.getSystemService(AlarmManager::class.java)
        override var active: Long?
            get() = store.find(id)?.scheduledAtMillis
            set(value) { store.commitActive(id, requireNotNull(value)) }
        override var staged: Long?
            get() = store.stagedAt(id)
            set(value) { store.setStagedAt(id, value) }
        override var pending: Long?
            get() = store.pendingAt(id)
            set(value) { store.setPendingAt(id, value) }
        override var enabled: Boolean
            get() = store.find(id)?.enabled == true
            set(value) { store.find(id)?.let { store.update(it.copy(enabled = value)) } }
        override var skipped: Long?
            get() = store.find(id)?.skippedAtMillis
            set(value) { store.find(id)?.let { store.update(it.copy(skippedAtMillis = value)) } }
        override var acknowledged: Boolean
            get() = store.pendingAt(id) == null
            set(@Suppress("UNUSED_PARAMETER") value) { /* ACK is the preceding durable pending write. */ }

        override fun install(at: Long, kind: WakeKind) {
            store.trackEpoch(id, at)
            val token = PendingIntent.getBroadcast(context, REQUEST_BASE + id, identity(context, id, at, kind),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            if (kind == WakeKind.PRIMARY) manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(at, showIntent(context, id)), token)
            else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                maxOf(at + 60_000L, System.currentTimeMillis() + 60_000L), token)
        }

        override fun acknowledge(at: Long): Boolean = store.acknowledge(id, at)

        override fun cancel(at: Long, kind: WakeKind) {
            existing(context, id, at, kind)?.let { manager.cancel(it); it.cancel() }
            if (existing(context, id, at, WakeKind.PRIMARY) == null &&
                existing(context, id, at, WakeKind.WATCHDOG) == null) store.forgetEpoch(id, at)
        }
    }

    private fun legacyPending(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(context, 1001,
            Intent(context, AlarmReceiver::class.java).setAction(RecurringAlarmScheduler.ACTION_RECURRING_ALARM),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)

    private fun legacyMatches(context: Context, entry: AlarmEntry): Boolean {
        val legacy = AlarmPreferences(context).load().schedule
        return legacy.enabled && entry.hour == legacy.hour && entry.minute == legacy.minute &&
            entry.weekdays == legacy.weekdays
    }

    private fun showIntent(context: Context, id: Int): PendingIntent =
        PendingIntent.getActivity(context, REQUEST_BASE + id,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
