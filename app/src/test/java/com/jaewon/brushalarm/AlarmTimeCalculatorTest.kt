package com.jaewon.brushalarm

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmTimeCalculatorTest {
    @Test
    fun `past selected time rolls to tomorrow`() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 22, 8, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val alarm = nextAlarmMillis(now, hour = 8, minute = 0)
        assertTrue(alarm > now)
        assertTrue(alarm - now in 23L * 60 * 60 * 1000..24L * 60 * 60 * 1000)
    }
}
