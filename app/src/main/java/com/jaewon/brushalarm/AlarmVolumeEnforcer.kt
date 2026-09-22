package com.jaewon.brushalarm

interface AlarmVolumePort {
    val current: Int
    val maximum: Int
    fun setVolume(value: Int)
}

class AlarmVolumeEnforcer(private val port: AlarmVolumePort) {
    private var originalVolume: Int? = null

    fun start() {
        if (originalVolume == null) originalVolume = port.current
        enforce()
    }

    fun enforce() {
        if (port.current != port.maximum) port.setVolume(port.maximum)
    }

    fun stop() {
        originalVolume?.let(port::setVolume)
        originalVolume = null
    }
}
