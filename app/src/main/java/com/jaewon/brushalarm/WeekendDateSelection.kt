package com.jaewon.brushalarm

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

/** Earliest Saturday/Sunday whose chosen local time is still in the future. */
fun nextWeekendDate(now: ZonedDateTime, hour: Int, minute: Int): LocalDate {
    require(hour in 0..23 && minute in 0..59)
    var date = now.toLocalDate()
    while (true) {
        if (date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
            date.atTime(hour, minute).atZone(now.zone).isAfter(now)
        ) return date
        date = date.plusDays(1)
    }
}
