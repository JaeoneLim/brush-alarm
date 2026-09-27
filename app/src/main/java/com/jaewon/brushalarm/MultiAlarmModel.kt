package com.jaewon.brushalarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class AlarmEntry(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val weekdays: Set<DayOfWeek>,
    val date: LocalDate?,
    val enabled: Boolean,
    val scheduledAtMillis: Long?,
    val skippedAtMillis: Long? = null,
    val skippedLocalDate: LocalDate? = null,
) {
    fun validate() {
        require(id >= 0 && hour in 0..23 && minute in 0..59)
        require(if (date == null) weekdays.isNotEmpty() || !enabled else weekdays.isEmpty()) {
            "Choose weekdays before enabling a repeating alarm, or one date"
        }
    }
}

fun oneOffDateForToday(
    nowMillis: Long,
    hour: Int,
    minute: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): LocalDate? {
    require(hour in 0..23 && minute in 0..59)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val trigger = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    return today.takeIf { trigger > nowMillis }
}

fun canEnableOneOff(entry: AlarmEntry, nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()): Boolean =
    entry.date == null || !entry.enabled ||
        entry.date.atTime(entry.hour, entry.minute).atZone(zone).toInstant().toEpochMilli() > nowMillis

fun skipMultiAlarmNext(entry: AlarmEntry, nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault()): AlarmEntry {
    entry.validate()
    require(entry.enabled && entry.date == null) { "Only enabled weekly alarms can be skipped" }
    val logicalSkipAt = entry.skippedLocalDate?.atTime(entry.hour, entry.minute)
        ?.atZone(zone)?.toInstant()?.toEpochMilli() ?: entry.skippedAtMillis
    require(canSkipNextOnce(logicalSkipAt, nowMillis)) { "An upcoming skip already exists" }
    val at = nextSelectedOccurrenceMillis(nowMillis, entry.hour, entry.minute, entry.weekdays, zone)
    return entry.copy(skippedAtMillis = at,
        skippedLocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate())
}

fun nextTriggerMillis(
    entry: AlarmEntry,
    nowMillis: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    entry.validate()
    if (!entry.enabled) return null
    val date = entry.date
    if (date == null) return planNextAlarm(
        AlarmSchedule(entry.hour, entry.minute, entry.weekdays, true), nowMillis,
        entry.skippedAtMillis, entry.skippedLocalDate, zone,
    )?.nextAtMillis
    val trigger = date.atTime(entry.hour, entry.minute).atZone(zone).toInstant().toEpochMilli()
    return trigger.takeIf { it > nowMillis }
}
