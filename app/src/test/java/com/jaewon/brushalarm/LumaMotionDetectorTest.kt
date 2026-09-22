package com.jaewon.brushalarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LumaMotionDetectorTest {
    @Test
    fun `unchanged mouth region is not brushing`() {
        val detector = LumaMotionDetector(threshold = 10.0)
        val frame = ByteArray(100) { 40 }
        assertFalse(detector.detect(frame, width = 10, height = 10, left = 2, top = 5, right = 8, bottom = 9))
        assertFalse(detector.detect(frame, width = 10, height = 10, left = 2, top = 5, right = 8, bottom = 9))
    }

    @Test
    fun `large luminance change in mouth region is brushing`() {
        val detector = LumaMotionDetector(threshold = 10.0)
        val first = ByteArray(100) { 20 }
        val second = ByteArray(100) { 20 }.also { bytes ->
            for (y in 5 until 9) for (x in 2 until 8) bytes[y * 10 + x] = 90
        }
        detector.detect(first, 10, 10, 2, 5, 8, 9)
        assertTrue(detector.detect(second, 10, 10, 2, 5, 8, 9))
    }
}
