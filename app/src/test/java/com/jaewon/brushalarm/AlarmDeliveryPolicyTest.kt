package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmDeliveryPolicyTest {
    @Test
    fun `actionless v0_2 pending intent is delivered as a legacy alarm`() {
        assertEquals(
            AlarmDeliveryKind.LEGACY,
            classifyAlarmDelivery(action = null, scheduledAtMillis = null),
        )
    }

    @Test
    fun `resume explicitly delivers a due alarm before advancing recurrence`() {
        assertEquals(
            AlarmResumeDecision.DELIVER_DUE,
            alarmResumeDecision(scheduledAtMillis = 1_000L, nowMillis = 1_001L),
        )
    }

    @Test
    fun `resume recalculates a future alarm for timezone changes`() {
        assertEquals(
            AlarmResumeDecision.RESCHEDULE,
            alarmResumeDecision(scheduledAtMillis = 2_000L, nowMillis = 1_000L),
        )
    }
}
