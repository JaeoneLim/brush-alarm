package com.jaewon.brushalarm

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate

data class StoredAlarmState(
    val schedule: AlarmSchedule,
    val scheduledAtMillis: Long?,
    val skippedAtMillis: Long?,
    val skippedLocalDate: LocalDate?,
)

class AlarmPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): StoredAlarmState {
        val defaultDays = DayOfWeek.entries.toSet()
        val weekdays = runCatching {
            weekdaysFromStorage(
                preferences.getString(KEY_WEEKDAYS, weekdaysToStorage(defaultDays)).orEmpty(),
            )
        }.getOrDefault(defaultDays)
        return StoredAlarmState(
            schedule = AlarmSchedule(
                hour = preferences.getInt(KEY_HOUR, 7),
                minute = preferences.getInt(KEY_MINUTE, 0),
                weekdays = weekdays,
                enabled = preferences.getBoolean(KEY_ENABLED, false),
            ),
            scheduledAtMillis = nullableLong(KEY_SCHEDULED_AT),
            skippedAtMillis = nullableLong(KEY_SKIPPED_AT),
            skippedLocalDate = preferences.getString(KEY_SKIPPED_LOCAL_DATE, null)?.let {
                runCatching { LocalDate.parse(it) }.getOrNull()
            },
        )
    }

    fun saveSchedule(schedule: AlarmSchedule) {
        preferences.edit()
            .putInt(KEY_HOUR, schedule.hour)
            .putInt(KEY_MINUTE, schedule.minute)
            .putString(KEY_WEEKDAYS, weekdaysToStorage(schedule.weekdays))
            .putBoolean(KEY_ENABLED, schedule.enabled)
            .remove(KEY_SKIPPED_AT)
            .remove(KEY_SKIPPED_LOCAL_DATE)
            .apply()
    }

    fun saveRuntime(
        scheduledAtMillis: Long?,
        skippedAtMillis: Long?,
        skippedLocalDate: LocalDate? = null,
    ) {
        preferences.edit().apply {
            putNullableLong(KEY_SCHEDULED_AT, scheduledAtMillis)
            putNullableLong(KEY_SKIPPED_AT, skippedAtMillis)
            if (skippedLocalDate == null) remove(KEY_SKIPPED_LOCAL_DATE)
            else putString(KEY_SKIPPED_LOCAL_DATE, skippedLocalDate.toString())
        }.apply()
    }

    private fun nullableLong(key: String): Long? =
        if (preferences.contains(key)) preferences.getLong(key, 0L) else null

    private fun android.content.SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    private companion object {
        const val PREFERENCES_NAME = "recurring_alarm"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"
        const val KEY_WEEKDAYS = "weekdays"
        const val KEY_ENABLED = "enabled"
        const val KEY_SCHEDULED_AT = "scheduled_at"
        const val KEY_SKIPPED_AT = "skipped_at"
        const val KEY_SKIPPED_LOCAL_DATE = "skipped_local_date"
    }
}
