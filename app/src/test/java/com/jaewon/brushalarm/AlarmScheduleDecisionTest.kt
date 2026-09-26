package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmScheduleDecisionTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val mondayWednesday = AlarmSchedule(
        hour = 8,
        minute = 0,
        weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
        enabled = true,
    )

    @Test
    fun `legacy v0_2 alarm delivery remains ringable after upgrade`() {
        assertEquals(AlarmDeliveryKind.LEGACY, classifyAlarmDelivery(action = null, scheduledAtMillis = null))
    }

    @Test
    fun `skip next once replaces exactly the current occurrence with the following one`() {
        val decision = skipNextAlarm(
            schedule = mondayWednesday,
            nowMillis = at(2026, 9, 21, 7, 0),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 21, 8, 0), decision.skippedAtMillis)
        assertEquals(at(2026, 9, 23, 8, 0), decision.nextAtMillis)
    }

    @Test
    fun `another skip is refused while one skipped occurrence is still pending`() {
        assertEquals(false, canSkipNextOnce(
            existingSkippedAtMillis = at(2026, 9, 21, 8, 0),
            nowMillis = at(2026, 9, 20, 12, 0),
        ))
    }

    @Test
    fun `reschedule before skipped occurrence preserves skip and does not restore it`() {
        val skipped = at(2026, 9, 21, 8, 0)

        val plan = planNextAlarm(
            schedule = mondayWednesday,
            nowMillis = at(2026, 9, 21, 7, 30),
            skippedAtMillis = skipped,
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 23, 8, 0), plan?.nextAtMillis)
        assertEquals(skipped, plan?.skippedAtMillis)
    }

    @Test
    fun `reschedule after timezone change still honors the skipped local occurrence`() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 9, 21, 6, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        val plan = planNextAlarm(
            schedule = mondayWednesday,
            nowMillis = now,
            skippedAtMillis = at(2026, 9, 21, 8, 0),
            skippedLocalDate = LocalDate.of(2026, 9, 21),
            zoneId = shanghai,
        )

        val expected = ZonedDateTime.of(2026, 9, 23, 8, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        assertEquals(expected, plan?.nextAtMillis)
    }

    @Test
    fun `logical skip date wins when date-line change makes old epoch match another weekday`() {
        val honolulu = ZoneId.of("Pacific/Honolulu")
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val skippedEpoch = ZonedDateTime.of(2026, 9, 21, 8, 0, 0, 0, honolulu)
            .toInstant().toEpochMilli()
        val now = ZonedDateTime.of(2026, 9, 21, 15, 0, 0, 0, kiritimati)
            .toInstant().toEpochMilli()
        val schedule = mondayWednesday.copy(
            weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        )

        val plan = planNextAlarm(
            schedule = schedule,
            nowMillis = now,
            skippedAtMillis = skippedEpoch,
            skippedLocalDate = LocalDate.of(2026, 9, 21),
            zoneId = kiritimati,
        )

        val expected = ZonedDateTime.of(2026, 9, 22, 8, 0, 0, 0, kiritimati)
            .toInstant().toEpochMilli()
        assertEquals(expected, plan?.nextAtMillis)
    }

    @Test
    fun `receiver schedules the following occurrence after the fired occurrence`() {
        val firedAt = at(2026, 9, 21, 8, 0)

        val plan = planFollowingAlarmAfterFire(
            schedule = mondayWednesday,
            firedAtMillis = firedAt,
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 23, 8, 0), plan?.nextAtMillis)
        assertNull(plan?.skippedAtMillis)
    }

    @Test
    fun `late receiver schedules after current time instead of a historical occurrence`() {
        val plan = planFollowingAlarmAfterFire(
            schedule = mondayWednesday,
            firedAtMillis = at(2026, 9, 21, 8, 0),
            nowMillis = at(2026, 9, 24, 12, 0),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 28, 8, 0), plan?.nextAtMillis)
    }

    @Test
    fun `normal recurrence resumes after skipped occurrence has passed`() {
        val plan = planNextAlarm(
            schedule = mondayWednesday,
            nowMillis = at(2026, 9, 21, 9, 0),
            skippedAtMillis = at(2026, 9, 21, 8, 0),
            zoneId = zone,
        )

        assertEquals(at(2026, 9, 23, 8, 0), plan?.nextAtMillis)
        assertNull(plan?.skippedAtMillis)
    }

    @Test
    fun `disabled schedule has no next alarm`() {
        val plan = planNextAlarm(
            schedule = mondayWednesday.copy(enabled = false),
            nowMillis = at(2026, 9, 21, 7, 0),
            skippedAtMillis = null,
            zoneId = zone,
        )

        assertNull(plan)
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
