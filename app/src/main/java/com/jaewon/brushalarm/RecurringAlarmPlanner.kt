package com.jaewon.brushalarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AlarmDeliveryKind { RECURRING, LEGACY, INVALID }

fun classifyAlarmDelivery(action: String?, scheduledAtMillis: Long?): AlarmDeliveryKind = when {
    action == "com.jaewon.brushalarm.action.RECURRING_ALARM" && scheduledAtMillis != null ->
        AlarmDeliveryKind.RECURRING
    action == null && scheduledAtMillis == null -> AlarmDeliveryKind.LEGACY
    else -> AlarmDeliveryKind.INVALID
}

fun weekdaysToStorage(weekdays: Set<DayOfWeek>): String =
    weekdays.joinToString(",") { it.name }

fun weekdaysFromStorage(value: String): Set<DayOfWeek> =
    value.split(',')
        .filter { it.isNotBlank() }
        .mapTo(linkedSetOf()) { DayOfWeek.valueOf(it) }

data class AlarmSchedule(
    val hour: Int,
    val minute: Int,
    val weekdays: Set<DayOfWeek>,
    val enabled: Boolean,
)

enum class AlarmResumeDecision { RESCHEDULE, DELIVER_DUE }

fun alarmResumeDecision(scheduledAtMillis: Long?, nowMillis: Long): AlarmResumeDecision =
    if (scheduledAtMillis == null || scheduledAtMillis > nowMillis) {
        AlarmResumeDecision.RESCHEDULE
    } else {
        AlarmResumeDecision.DELIVER_DUE
    }

data class SkipDecision(
    val skippedAtMillis: Long,
    val nextAtMillis: Long,
)

data class AlarmPlan(
    val nextAtMillis: Long,
    val skippedAtMillis: Long?,
    val skippedLocalDate: LocalDate?,
)

fun canSkipNextOnce(existingSkippedAtMillis: Long?, nowMillis: Long): Boolean =
    existingSkippedAtMillis == null || existingSkippedAtMillis <= nowMillis

fun planNextAlarm(
    schedule: AlarmSchedule,
    nowMillis: Long,
    skippedAtMillis: Long?,
    skippedLocalDate: LocalDate? = null,
    zoneId: ZoneId = ZoneId.systemDefault(),
): AlarmPlan? {
    if (!schedule.enabled) return null
    var next = nextSelectedOccurrenceMillis(
        nowMillis, schedule.hour, schedule.minute, schedule.weekdays, zoneId,
    )
    val nextLocalDate = Instant.ofEpochMilli(next).atZone(zoneId).toLocalDate()
    val nextIsSkipped = if (skippedLocalDate != null) {
        nextLocalDate == skippedLocalDate
    } else {
        next == skippedAtMillis
    }
    if (nextIsSkipped) {
        next = nextSelectedOccurrenceMillis(
            next, schedule.hour, schedule.minute, schedule.weekdays, zoneId,
        )
    }
    val logicalSkip = skippedLocalDate ?: skippedAtMillis?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate()
    }
    val logicalSkipMillis = logicalSkip?.atTime(schedule.hour, schedule.minute)
        ?.atZone(zoneId)?.toInstant()?.toEpochMilli()
    val retainSkip = logicalSkipMillis != null && logicalSkipMillis > nowMillis
    return AlarmPlan(
        nextAtMillis = next,
        skippedAtMillis = logicalSkipMillis?.takeIf { retainSkip },
        skippedLocalDate = logicalSkip?.takeIf { retainSkip },
    )
}

fun planFollowingAlarmAfterFire(
    schedule: AlarmSchedule,
    firedAtMillis: Long,
    nowMillis: Long = firedAtMillis,
    zoneId: ZoneId = ZoneId.systemDefault(),
): AlarmPlan? = planNextAlarm(
    schedule = schedule,
    nowMillis = maxOf(firedAtMillis, nowMillis),
    skippedAtMillis = null,
    zoneId = zoneId,
)

fun skipNextAlarm(
    schedule: AlarmSchedule,
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): SkipDecision {
    require(schedule.enabled) { "Alarm schedule is disabled" }
    val skipped = nextSelectedOccurrenceMillis(
        nowMillis, schedule.hour, schedule.minute, schedule.weekdays, zoneId,
    )
    val next = nextSelectedOccurrenceMillis(
        skipped, schedule.hour, schedule.minute, schedule.weekdays, zoneId,
    )
    return SkipDecision(skipped, next)
}

fun nextSelectedOccurrenceMillis(
    nowMillis: Long,
    hour: Int,
    minute: Int,
    weekdays: Set<DayOfWeek>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    require(weekdays.isNotEmpty()) { "At least one weekday must be selected" }
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val today = now.toLocalDate()
    return weekdays.minOf { selectedDay ->
        val daysAhead = (selectedDay.value - today.dayOfWeek.value + 7) % 7
        var candidateDate = today.plusDays(daysAhead.toLong())
        var candidate = candidateDate.atTime(hour, minute).atZone(zoneId)
        if (!candidate.isAfter(now)) {
            candidateDate = candidateDate.plusWeeks(1)
            candidate = candidateDate.atTime(hour, minute).atZone(zoneId)
        }
        candidate.toInstant().toEpochMilli()
    }
}
