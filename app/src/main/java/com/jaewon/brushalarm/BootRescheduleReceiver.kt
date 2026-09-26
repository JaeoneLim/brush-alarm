package com.jaewon.brushalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in RESCHEDULE_ACTIONS) {
            val store = MultiAlarmStore(context)
            val migrated = store.migrationDone()
            if (legacyNeedsBootReschedule(migrated)) RecurringAlarmScheduler.rescheduleStored(context)
            MultiAlarmScheduler.rescheduleAll(context, force = true)
        }
    }

    private companion object {
        val RESCHEDULE_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
