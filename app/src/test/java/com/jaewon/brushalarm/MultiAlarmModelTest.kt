package com.jaewon.brushalarm

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MultiAlarmModelTest {
    private val zone = ZoneId.of("UTC")
    private fun epoch(date: String, hour: Int = 0, minute: Int = 0) =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private fun entry(days: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY), date: LocalDate? = null,
        enabled: Boolean = true) = AlarmEntry(1, 9, 30, days, date, enabled, null)

    @Test fun weeklyReturnsNextSelectedOccurrenceStrictlyAfterNow() {
        assertEquals(epoch("2026-10-03", 9, 30), nextTriggerMillis(entry(), epoch("2026-10-02"), zone))
        assertEquals(epoch("2026-10-10", 9, 30), nextTriggerMillis(entry(), epoch("2026-10-03", 9, 30), zone))
    }

    @Test fun weekdayOneOffOnStoredDateIsValidAndDoesNotRecur() {
        val alarm = entry(emptySet(), LocalDate.parse("2026-10-05"))
        alarm.validate()
        assertEquals(epoch("2026-10-05", 9, 30), nextTriggerMillis(alarm, epoch("2026-10-05", 8), zone))
        assertNull(nextTriggerMillis(alarm, epoch("2026-10-05", 9, 30), zone))
    }

    @Test fun dateOneOffNeverRollsForwardAfterItsDate() {
        val alarm = entry(emptySet(), LocalDate.parse("2026-10-03"))
        assertEquals(epoch("2026-10-03", 9, 30), nextTriggerMillis(alarm, epoch("2026-10-02"), zone))
        assertNull(nextTriggerMillis(alarm, epoch("2026-10-03", 9, 30), zone))
    }

    @Test fun rejectsInvalidScheduleShapes() {
        assertThrows(IllegalArgumentException::class.java) { entry(emptySet(), enabled = true).validate() }
        assertThrows(IllegalArgumentException::class.java) { entry(date = LocalDate.parse("2026-10-03")).validate() }
        assertThrows(IllegalArgumentException::class.java) { entry().copy(hour = 24).validate() }
    }

    @Test fun newOneOffUsesCurrentWeekdayWhenTimeIsStillFuture() {
        val now = epoch("2026-10-05", 8) // Monday
        val chosen = oneOffDateForToday(now, 9, 30, zone)
        assertEquals(LocalDate.parse("2026-10-05"), chosen)
        val alarm = entry(emptySet(), chosen)
        assertEquals(epoch("2026-10-05", 9, 30), nextTriggerMillis(alarm, now, zone))
    }

    @Test fun newOneOffRejectsPassedOrExactTimeInsteadOfRollingToTomorrow() {
        assertNull(oneOffDateForToday(epoch("2026-10-05", 9, 30), 9, 30, zone))
        assertNull(oneOffDateForToday(epoch("2026-10-05", 9, 31), 9, 30, zone))
        assertNull(oneOffDateForToday(epoch("2026-10-05", 23, 59), 0, 0, zone))
    }

    @Test fun newOneOffNearMidnightUsesTodaysRemainingMinutes() {
        assertEquals(LocalDate.parse("2026-10-05"),
            oneOffDateForToday(epoch("2026-10-05", 23, 58), 23, 59, zone))
        assertEquals(LocalDate.parse("2026-10-06"),
            oneOffDateForToday(epoch("2026-10-06", 0), 0, 1, zone))
    }

    @Test fun newOneOffUsesProvidedLocalZoneRatherThanUtcDate() {
        val now = epoch("2026-10-05", 0, 15)
        val losAngeles = ZoneId.of("America/Los_Angeles") // Sunday, October 4 at 17:15
        assertEquals(LocalDate.parse("2026-10-04"),
            oneOffDateForToday(now, 18, 0, losAngeles))
        assertEquals(LocalDate.parse("2026-10-05"),
            oneOffDateForToday(now, 9, 30, zone))
        assertNull(oneOffDateForToday(now, 17, 0, losAngeles))
    }

    @Test fun expiredOneOffCannotBeReenabled() {
        val once = AlarmEntry(4, 9, 30, emptySet(), LocalDate.parse("2026-10-03"), false, null)
        assertFalse(canEnableOneOff(once.copy(enabled = true), epoch("2026-10-04"), zone))
        assertTrue(canEnableOneOff(once.copy(enabled = true), epoch("2026-10-02"), zone))
    }

    @Test fun skipNextKeepsLogicalDateAcrossTimezoneChange() {
        val original = AlarmEntry(7, 9, 0, setOf(DayOfWeek.SATURDAY), null, true, null)
        val now = epoch("2026-10-02")
        val skipped = skipMultiAlarmNext(original, now, zone)
        assertEquals(LocalDate.parse("2026-10-03"), skipped.skippedLocalDate)
        assertEquals(epoch("2026-10-03", 9), skipped.skippedAtMillis)
        assertEquals(epoch("2026-10-10", 9), nextTriggerMillis(skipped, now, zone))
        assertEquals(epoch("2026-10-10"), nextTriggerMillis(skipped, now, ZoneId.of("Asia/Tokyo")))
    }

    @Test fun timezoneShiftCannotAllowSecondSkipBeforeLogicalOccurrence() {
        val utc = ZoneId.of("UTC")
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val original = AlarmEntry(8, 9, 0, setOf(DayOfWeek.SATURDAY), null, true, null)
        val skipped = skipMultiAlarmNext(original, epoch("2026-10-02"), utc)
        assertThrows(IllegalArgumentException::class.java) {
            skipMultiAlarmNext(skipped, epoch("2026-10-03", 10), honolulu)
        }
    }

    @Test fun disabledAlarmHasNoNextTrigger() {
        assertNull(nextTriggerMillis(entry(enabled = false), epoch("2026-10-02"), zone))
    }
}
