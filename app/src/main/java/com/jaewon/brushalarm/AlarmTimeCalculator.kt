package com.jaewon.brushalarm

import java.util.Calendar

fun nextAlarmMillis(nowMillis: Long, hour: Int, minute: Int): Long {
    val alarm = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
    }
    return alarm.timeInMillis
}
