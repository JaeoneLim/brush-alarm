package com.jaewon.brushalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

object RecurringAlarmScheduler {
    const val ACTION_RECURRING_ALARM = "com.jaewon.brushalarm.action.RECURRING_ALARM"
    const val EXTRA_SCHEDULED_AT = "scheduled_at"
    private const val ALARM_REQUEST_CODE = 1001
    private const val SHOW_REQUEST_CODE = 1002

    fun schedule(context: Context, triggerAtMillis: Long): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (!alarmManager.canScheduleExactAlarms()) return false
        val operation = createAlarmPendingIntent(context, triggerAtMillis)
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent(context)),
            operation,
        )
        return true
    }

    fun cancel(context: Context) {
        existingAlarmPendingIntent(context)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    fun rescheduleStored(context: Context, nowMillis: Long = System.currentTimeMillis()): AlarmPlan? {
        val preferences = AlarmPreferences(context)
        val state = preferences.load()
        if (!state.schedule.enabled || state.schedule.weekdays.isEmpty()) {
            cancel(context)
            preferences.saveRuntime(null, null)
            return null
        }
        val plan = planNextAlarm(
            schedule = state.schedule,
            nowMillis = nowMillis,
            skippedAtMillis = state.skippedAtMillis,
            skippedLocalDate = state.skippedLocalDate,
        )
        val scheduled = plan != null && schedule(context, plan.nextAtMillis)
        preferences.saveRuntime(
            scheduledAtMillis = if (scheduled) plan?.nextAtMillis else null,
            skippedAtMillis = plan?.skippedAtMillis,
            skippedLocalDate = plan?.skippedLocalDate,
        )
        return plan?.takeIf { scheduled }
    }

    private fun createAlarmPendingIntent(context: Context, scheduledAtMillis: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            recurringAlarmIntent(context, scheduledAtMillis),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun existingAlarmPendingIntent(context: Context): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            recurringAlarmIntent(context, 0L),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun recurringAlarmIntent(context: Context, scheduledAtMillis: Long) =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_RECURRING_ALARM)
            .putExtra(EXTRA_SCHEDULED_AT, scheduledAtMillis)

    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
