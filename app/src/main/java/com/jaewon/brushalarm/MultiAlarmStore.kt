package com.jaewon.brushalarm

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate

fun encodeAlarmEntry(entry: AlarmEntry): String {
    entry.validate()
    return listOf(entry.id, entry.hour, entry.minute, weekdaysToStorage(entry.weekdays),
        entry.date?.toString().orEmpty(), entry.enabled, entry.scheduledAtMillis?.toString().orEmpty(),
        entry.skippedAtMillis?.toString().orEmpty(), entry.skippedLocalDate?.toString().orEmpty())
        .joinToString("|")
}

fun decodeAlarmEntry(value: String): AlarmEntry? = runCatching {
    val fields = value.split('|')
    require(fields.size in 7..9 && fields[5] in setOf("true", "false"))
    AlarmEntry(fields[0].toInt(), fields[1].toInt(), fields[2].toInt(),
        weekdaysFromStorage(fields[3]), fields[4].takeIf { it.isNotEmpty() }?.let(LocalDate::parse),
        fields[5].toBooleanStrict(), fields[6].takeIf { it.isNotEmpty() }?.toLong(),
        fields.getOrNull(7)?.takeIf { it.isNotEmpty() }?.toLong(),
        fields.getOrNull(8)?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse))
        .also { it.validate() }
}.getOrNull()

internal val multiAlarmStoreLock = Any()

fun migrateLegacyEntry(state: StoredAlarmState, hasSavedSchedule: Boolean): AlarmEntry? {
    val schedule = state.schedule
    if (!hasSavedSchedule && !schedule.enabled) return null
    return AlarmEntry(1, schedule.hour, schedule.minute, schedule.weekdays, null,
        schedule.enabled, state.scheduledAtMillis.takeIf { schedule.enabled },
        state.skippedAtMillis.takeIf { schedule.enabled },
        state.skippedLocalDate.takeIf { schedule.enabled })
}

/** v0.3's PendingIntent remains in place until its delivery or a successful replacement. */
class MultiAlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("multi_alarms", Context.MODE_PRIVATE)
    private val legacyPrefs = context.getSharedPreferences("recurring_alarm", Context.MODE_PRIVATE)
    private val legacy = AlarmPreferences(context)

    @Synchronized fun load(): List<AlarmEntry> {
        migrateIfNeeded()
        return ids().mapNotNull { id -> prefs.getString("entry_$id", null)?.let(::decodeAlarmEntry) }
    }

    @Synchronized fun add(hour: Int, minute: Int, weekdays: Set<DayOfWeek>,
        date: LocalDate?, enabled: Boolean = true): AlarmEntry {
        load()
        val id = prefs.getInt("next_id", 1)
        val entry = AlarmEntry(id, hour, minute, weekdays, date, enabled, null)
        entry.validate()
        require(canEnableOneOff(entry, System.currentTimeMillis())) { "One-off alarm date has elapsed" }
        require(prefs.edit().putInt("next_id", id + 1)
            .putString("ids", (ids() + id).joinToString(","))
            .putBoolean("legacy_v02_allowed", false)
            .putString("entry_$id", encodeAlarmEntry(entry)).commit())
        return entry
    }

    fun update(entry: AlarmEntry) = synchronized(multiAlarmStoreLock) {
        entry.validate()
        load()
        val prior = find(entry.id)
        require(prior != null) { "Unknown alarm ID ${entry.id}" }
        if (!prior.enabled && entry.enabled) {
            require(canEnableOneOff(entry, System.currentTimeMillis())) { "One-off alarm date has elapsed" }
        }
        val editor = prefs.edit().putString("entry_${entry.id}", encodeAlarmEntry(entry))
        val scheduleChanged = prior.hour != entry.hour || prior.minute != entry.minute ||
            prior.weekdays != entry.weekdays || prior.date != entry.date
        if (scheduleChanged || prior.scheduledAtMillis != entry.scheduledAtMillis || !entry.enabled) {
            editor.remove("committed_${entry.id}")
        }
        if (scheduleChanged || !entry.enabled) {
            editor.remove("pending_${entry.id}").remove("staged_${entry.id}")
        }
        require(editor.commit())
    }

    fun consumeSkipped(id: Int, atMillis: Long): AlarmEntry? = synchronized(multiAlarmStoreLock) {
        val original = find(id) ?: return@synchronized null
        val updated = consumeSkippedMultiAlarm(original, atMillis) ?: return@synchronized null
        update(updated)
        original
    }

    fun consume(id: Int, atMillis: Long): AlarmEntry? = synchronized(multiAlarmStoreLock) {
        val original = find(id) ?: return@synchronized null
        val updated = consumeMultiAlarm(original, atMillis) ?: return@synchronized null
        update(updated)
        original
    }

    fun delete(id: Int) = synchronized(multiAlarmStoreLock) {
        load()
        require(prefs.edit().putString("ids", ids().filterNot { it == id }.joinToString(","))
            .remove("entry_$id").remove("committed_$id").remove("ack_$id")
            .remove("pending_$id").remove("staged_$id").commit())
    }

    fun committedAt(id: Int): Long? =
        if (prefs.contains("committed_$id")) prefs.getLong("committed_$id", 0L) else null

    fun setCommittedAt(id: Int, atMillis: Long?) {
        val editor = prefs.edit()
        if (atMillis == null) editor.remove("committed_$id") else editor.putLong("committed_$id", atMillis)
        require(editor.commit()) { "Could not record alarm scheduling state" }
    }

    fun acknowledgedAt(id: Int): Long? = longOrNull("ack_$id")

    fun stagedAt(id: Int): Long? = longOrNull("staged_$id")
    fun pendingAt(id: Int): Long? = longOrNull("pending_$id")
    private fun longOrNull(key: String): Long? =
        if (prefs.contains(key)) prefs.getLong(key, 0L) else null

    fun setStagedAt(id: Int, at: Long?) = setNullableLong("staged_$id", at)
    fun setPendingAt(id: Int, at: Long?) = setNullableLong("pending_$id", at)
    private fun setNullableLong(key: String, at: Long?) {
        val editor = prefs.edit()
        if (at == null) editor.remove(key) else editor.putLong(key, at)
        require(editor.commit()) { "Could not persist alarm delivery state" }
    }

    /** Track identities before the platform call so OFF/delete can cancel interrupted installs. */
    fun trackedEpochs(id: Int): Set<Long> = prefs.getString("tokens_$id", "").orEmpty()
        .split(',').mapNotNull(String::toLongOrNull).toSet()

    fun trackEpoch(id: Int, at: Long) {
        require(prefs.edit().putString("tokens_$id", (trackedEpochs(id) + at).joinToString(","))
            .commit())
    }

    fun forgetEpoch(id: Int, at: Long) {
        require(prefs.edit().putString("tokens_$id", (trackedEpochs(id) - at).joinToString(","))
            .commit())
    }

    /** OFF/new rows remain OFF on disk until both wake sources have been installed. */
    fun commitActivation(id: Int, at: Long) = synchronized(multiAlarmStoreLock) {
        val previous = find(id) ?: error("Deleted alarm")
        require(!previous.enabled && pendingAt(id) == null)
        val enabled = previous.copy(enabled = true, scheduledAtMillis = at,
            skippedAtMillis = null, skippedLocalDate = null)
        enabled.validate()
        require(prefs.edit().putString("entry_$id", encodeAlarmEntry(enabled))
            .putLong("committed_$id", at).remove("staged_$id").commit())
    }

    /** Editing is committed only once both replacement wakes exist. */
    fun commitEditedSchedule(edited: AlarmEntry, at: Long) = synchronized(multiAlarmStoreLock) {
        edited.validate()
        val previous = find(edited.id) ?: error("Deleted alarm")
        require(previous.enabled && edited.enabled && pendingAt(edited.id) == null)
        val committed = edited.copy(scheduledAtMillis = at,
            skippedAtMillis = null, skippedLocalDate = null)
        require(prefs.edit().putString("entry_${edited.id}", encodeAlarmEntry(committed))
            .putLong("committed_${edited.id}", at)
            .remove("staged_${edited.id}").commit())
    }

    /** Commit the active epoch and marker together, after both platform calls returned. */
    fun commitActive(id: Int, at: Long) = synchronized(multiAlarmStoreLock) {
        val entry = find(id) ?: error("Deleted alarm")
        require(entry.enabled)
        require(prefs.edit().putString("entry_$id", encodeAlarmEntry(entry.copy(scheduledAtMillis = at)))
            .putLong("committed_$id", at).commit())
    }

    /** Retire a one-off and acknowledge in the same durable write to prevent replay. */
    fun acknowledge(id: Int, at: Long): Boolean = synchronized(multiAlarmStoreLock) {
        val entry = find(id) ?: return@synchronized false
        if (!entry.enabled || pendingAt(id) != at) return@synchronized false
        val editor = prefs.edit().remove("pending_$id").putLong("ack_$id", at)
        if (entry.date != null) editor.putString("entry_$id",
            encodeAlarmEntry(entry.copy(enabled = false, scheduledAtMillis = null)))
            .remove("committed_$id")
        else if (entry.scheduledAtMillis == at) {
            // The following recurrence could not be installed. Keep the old watchdog
            // as a retry-only wake, never as a second ringing request.
            editor.putString("entry_$id", encodeAlarmEntry(entry.copy(scheduledAtMillis = null)))
                .remove("committed_$id")
        }
        require(editor.commit())
        true
    }

    fun find(id: Int): AlarmEntry? = load().firstOrNull { it.id == id }

    fun legacyV02Allowed(): Boolean = prefs.getBoolean("legacy_v02_allowed", false)

    fun migrationDone(): Boolean = prefs.getBoolean("migrated", false)

    private fun ids(): List<Int> = prefs.getString("ids", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }

    private fun migrateIfNeeded() {
        if (migrationDone()) return
        val state = legacy.load()
        val hadLegacySchedule = legacyPrefs.contains("hour")
        val entry = migrateLegacyEntry(state, hadLegacySchedule)
        val editor = prefs.edit().putBoolean("migrated", true)
            .putBoolean("legacy_v02_allowed", entry == null && !hadLegacySchedule)
            .putInt("next_id", if (entry == null) 1 else 2)
            .putString("ids", if (entry == null) "" else "1")
        if (entry != null) editor.putString("entry_1", encodeAlarmEntry(entry))
        require(editor.commit()) { "Could not migrate legacy alarm" }
    }
}
