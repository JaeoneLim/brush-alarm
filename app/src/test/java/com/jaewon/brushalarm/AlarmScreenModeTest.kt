package com.jaewon.brushalarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmScreenModeTest {
    @Test fun previewUsesCameraButNeverEnforcesAnAlarm() {
        val mode = AlarmScreenMode.PREVIEW
        assertTrue(mode.showCamera)
        assertTrue(mode.showExitButton)
        assertFalse(mode.wakeAndUnlockScreen)
        assertFalse(mode.blockDismissal)
        assertFalse(mode.runBrushingVerification)
        assertFalse(mode.stopRingingOnCompletion)
    }

    @Test fun realAlarmKeepsBrushingEnforcement() {
        val mode = AlarmScreenMode.ALARM
        assertTrue(mode.showCamera)
        assertFalse(mode.showExitButton)
        assertTrue(mode.wakeAndUnlockScreen)
        assertTrue(mode.blockDismissal)
        assertTrue(mode.runBrushingVerification)
        assertTrue(mode.stopRingingOnCompletion)
    }
}
