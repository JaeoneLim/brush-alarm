package com.jaewon.brushalarm

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class WeekendDateSelectionTest {
    private val zone = ZoneId.of("Asia/Seoul")
    @Test fun futureSaturdayTimeCanUseToday() {
        val now = ZonedDateTime.of(2026, 9, 26, 9, 0, 0, 0, zone)
        assertEquals(LocalDate.of(2026, 9, 26), nextWeekendDate(now, 10, 0))
    }
    @Test fun pastSaturdayTimeUsesSunday() {
        val now = ZonedDateTime.of(2026, 9, 26, 11, 0, 0, 0, zone)
        assertEquals(LocalDate.of(2026, 9, 27), nextWeekendDate(now, 10, 0))
    }
    @Test fun pastSundayTimeUsesNextSaturday() {
        val now = ZonedDateTime.of(2026, 9, 27, 11, 0, 0, 0, zone)
        assertEquals(LocalDate.of(2026, 10, 3), nextWeekendDate(now, 10, 0))
    }
    @Test fun weekdayUsesFollowingSaturday() {
        val now = ZonedDateTime.of(2026, 9, 28, 11, 0, 0, 0, zone)
        assertEquals(LocalDate.of(2026, 10, 3), nextWeekendDate(now, 10, 0))
    }
}
