package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmVolumeEnforcerTest {
    @Test
    fun `start raises alarm stream to maximum and stop restores original volume`() {
        val port = FakeAlarmVolumePort(current = 3, maximum = 10)
        val enforcer = AlarmVolumeEnforcer(port)

        enforcer.start()
        assertEquals(10, port.current)

        port.current = 1
        enforcer.enforce()
        assertEquals(10, port.current)

        enforcer.stop()
        assertEquals(3, port.current)
    }

    private class FakeAlarmVolumePort(
        override var current: Int,
        override val maximum: Int,
    ) : AlarmVolumePort {
        override fun setVolume(value: Int) {
            current = value
        }
    }
}
