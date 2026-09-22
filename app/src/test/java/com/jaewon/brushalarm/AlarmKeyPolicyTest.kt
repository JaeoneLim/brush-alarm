package com.jaewon.brushalarm

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmKeyPolicyTest {
    @Test
    fun `volume keys are blocked while alarm is active`() {
        assertTrue(shouldBlockAlarmKey(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertTrue(shouldBlockAlarmKey(KeyEvent.KEYCODE_VOLUME_UP))
        assertTrue(shouldBlockAlarmKey(KeyEvent.KEYCODE_VOLUME_MUTE))
        assertFalse(shouldBlockAlarmKey(KeyEvent.KEYCODE_BACK))
    }
}
