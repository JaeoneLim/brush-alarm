package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class AlarmStateCodecTest {
    @Test
    fun `weekday persistence round trip keeps every selected day`() {
        val selected = linkedSetOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.FRIDAY)

        val restored = weekdaysFromStorage(weekdaysToStorage(selected))

        assertEquals(selected, restored)
    }
}
