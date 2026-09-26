package com.jaewon.brushalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MultiAlarmScheduler.ACTION_MULTI_ALARM) {
            if (!intent.hasExtra(MultiAlarmScheduler.EXTRA_ALARM_ID) ||
                !intent.hasExtra(MultiAlarmScheduler.EXTRA_SCHEDULED_AT)) return
            val id = intent.getIntExtra(MultiAlarmScheduler.EXTRA_ALARM_ID, -1)
            val at = intent.getLongExtra(MultiAlarmScheduler.EXTRA_SCHEDULED_AT, Long.MIN_VALUE)
            if (id > 0 && MultiAlarmScheduler.handleFired(context, id, at)) startRinging(context, id, at)
            return
        }
        val scheduledAt = if (intent.hasExtra(RecurringAlarmScheduler.EXTRA_SCHEDULED_AT)) {
            intent.getLongExtra(RecurringAlarmScheduler.EXTRA_SCHEDULED_AT, Long.MIN_VALUE)
        } else {
            null
        }
        when (classifyAlarmDelivery(intent.action, scheduledAt)) {
            AlarmDeliveryKind.LEGACY -> {
                val legacyPrefs = context.getSharedPreferences("recurring_alarm", Context.MODE_PRIVATE)
                val store = MultiAlarmStore(context)
                if (!shouldAcceptV02Delivery(store.migrationDone(),
                        legacyPrefs.contains("hour"), AlarmPreferences(context).load().schedule.enabled,
                        store.legacyV02Allowed())) return
                startRinging(context)
                return
            }
            AlarmDeliveryKind.INVALID -> return
            AlarmDeliveryKind.RECURRING -> Unit
        }

        val firedAt = scheduledAt ?: return
        val store = MultiAlarmStore(context)
        if (store.migrationDone()) {
            if (shouldRouteLegacyToMulti(store.find(1), firedAt) &&
                MultiAlarmScheduler.handleFired(context, 1, firedAt)) startRinging(context, 1, firedAt)
            return
        }
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

    private fun startRinging(context: Context, id: Int? = null, at: Long? = null) {
        val service = Intent(context, AlarmRingingService::class.java)
        if (id != null && at != null) {
            service.putExtra(MultiAlarmScheduler.EXTRA_ALARM_ID, id)
                .putExtra(MultiAlarmScheduler.EXTRA_SCHEDULED_AT, at)
        }
        ContextCompat.startForegroundService(context, service)
    }
}
