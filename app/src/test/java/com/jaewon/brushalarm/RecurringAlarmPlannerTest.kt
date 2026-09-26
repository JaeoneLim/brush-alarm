package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurringAlarmPlannerTest {
    private val zone = ZoneId.of("Asia/Seoul")

    @Test
    fun `today's selected time is the next occurrence when still in the future`() {
        val now = at(2026, 9, 21, 7, 30)

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 8,
            minute = 0,
            weekdays = setOf(DayOfWeek.MONDAY),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 21, 8, 0), next)
    }

    @Test
    fun `today's selected time rolls to the same weekday next week when already past`() {
        val now = at(2026, 9, 21, 8, 30)

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 8,
            minute = 0,
            weekdays = setOf(DayOfWeek.MONDAY),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 28, 8, 0), next)
    }

    @Test
    fun `selected weekday gap advances to the nearest selected day`() {
        val now = at(2026, 9, 21, 8, 30) // Monday

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 8,
            minute = 0,
            weekdays = setOf(DayOfWeek.WEDNESDAY),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 23, 8, 0), next)
    }

    @Test
    fun `week wrap advances from Friday to selected Monday`() {
        val now = at(2026, 9, 25, 9, 0) // Friday

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 8,
            minute = 0,
            weekdays = setOf(DayOfWeek.MONDAY),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 28, 8, 0), next)
    }

    @Test
    fun `multiple weekdays choose the nearest occurrence regardless of set order`() {
        val now = at(2026, 9, 21, 9, 0) // Monday

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 8,
            minute = 0,
            weekdays = linkedSetOf(DayOfWeek.WEDNESDAY, DayOfWeek.TUESDAY),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 22, 8, 0), next)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty weekday selection is rejected`() {
        nextSelectedOccurrenceMillis(
            nowMillis = at(2026, 9, 21, 7, 0),
            hour = 8,
            minute = 0,
            weekdays = emptySet(),
            zoneId = zone,
        )
    }

    @Test
    fun `DST spring gap resolves to the next valid local time`() {
        val newYork = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 7, 10, 0, 0, 0, newYork).toInstant().toEpochMilli()

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 2,
            minute = 30,
            weekdays = setOf(DayOfWeek.SUNDAY),
            zoneId = newYork,
        )

        val expected = ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, newYork).toInstant().toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `DST gap day after adjusted alarm keeps requested local time next week`() {
        val newYork = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 4, 0, 0, 0, newYork).toInstant().toEpochMilli()

        val next = nextSelectedOccurrenceMillis(
            nowMillis = now,
            hour = 2,
            minute = 30,
            weekdays = setOf(DayOfWeek.SUNDAY),
            zoneId = newYork,
        )

        val expected = ZonedDateTime.of(2026, 3, 15, 2, 30, 0, 0, newYork).toInstant().toEpochMilli()
        assertEquals(expected, next)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
