package com.jaewon.brushalarm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmRingingServiceRecoveryTest {
    @Test fun duplicateStartAfterAckCannotDowngradeAnAudibleServiceToNonSticky() {
        val source = File("src/main/java/com/jaewon/brushalarm/AlarmRingingService.kt").readText()
        val start = source.substringAfter("override fun onStartCommand(")
            .substringBefore("private fun ensureRingingStarted()")
        val invalid = start.substringAfter("if (id < 1 || !MultiAlarmScheduler.isPending(this, id, at)) {")
            .substringBefore("ensureRingingStarted()")
        assertTrue(invalid.contains("if (player != null) return START_STICKY"))
        assertTrue(invalid.contains("stopSelf(startId)"))
        assertTrue(invalid.contains("return START_NOT_STICKY"))
    }

    @Test fun stickyRestartWithoutIntentKeepsRingingUntilBrushingCompletes() {
        val source = File("src/main/java/com/jaewon/brushalarm/AlarmRingingService.kt").readText()
        val start = source.substringAfter("override fun onStartCommand(").substringBefore("private fun ensureRingingStarted()")
        assertTrue(start.contains("if (intent == null) {\n            ensureRingingStarted()\n            return START_STICKY\n        }"))
        assertTrue(start.contains("if (intent?.action == ACTION_STOP_AFTER_BRUSHING)"))
        assertTrue(start.contains("stopSelf()"))
    }
}
