package com.jaewon.brushalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduledAt = if (intent.hasExtra(RecurringAlarmScheduler.EXTRA_SCHEDULED_AT)) {
            intent.getLongExtra(RecurringAlarmScheduler.EXTRA_SCHEDULED_AT, Long.MIN_VALUE)
        } else {
            null
        }
        when (classifyAlarmDelivery(intent.action, scheduledAt)) {
            AlarmDeliveryKind.LEGACY -> {
                startRinging(context)
                return
            }
            AlarmDeliveryKind.INVALID -> return
            AlarmDeliveryKind.RECURRING -> Unit
        }

        val firedAt = scheduledAt ?: return
        val preferences = AlarmPreferences(context)
        val state = preferences.load()
        if (!state.schedule.enabled || state.scheduledAtMillis != firedAt) return

        val following = planFollowingAlarmAfterFire(
            schedule = state.schedule,
            firedAtMillis = firedAt,
            nowMillis = System.currentTimeMillis(),
        )
        val scheduled = following != null &&
            RecurringAlarmScheduler.schedule(context, following.nextAtMillis)
        preferences.saveRuntime(
            scheduledAtMillis = if (scheduled) following?.nextAtMillis else null,
            skippedAtMillis = null,
        )
        startRinging(context)
    }

    private fun startRinging(context: Context) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmRingingService::class.java),
        )
    }
}
