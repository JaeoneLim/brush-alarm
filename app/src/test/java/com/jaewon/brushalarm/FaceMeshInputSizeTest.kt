package com.jaewon.brushalarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceMeshInputSizeTest {
    @Test
    fun `warm up input meets face mesh minimum dimensions`() {
        val size = FaceMeshInputSize.minimum()

        assertTrue(size.width >= 480)
        assertTrue(size.height >= 360)
    }

    @Test
    fun `portrait input meets both minimums while preserving aspect ratio`() {
        val size = FaceMeshInputSize.forSource(width = 1080, height = 1920)

        assertEquals(480, size.width)
        assertEquals(854, size.height)
        assertMinimumsAndAspect(size, sourceWidth = 1080, sourceHeight = 1920)
    }

    @Test
    fun `landscape input meets both minimums while preserving aspect ratio`() {
        val size = FaceMeshInputSize.forSource(width = 1920, height = 1080)

        assertEquals(640, size.width)
        assertEquals(360, size.height)
        assertMinimumsAndAspect(size, sourceWidth = 1920, sourceHeight = 1080)
    }

    @Test
    fun `wide input raises width enough to keep height at minimum`() {
        val size = FaceMeshInputSize.forSource(width = 1920, height = 800)

        assertEquals(864, size.width)
        assertEquals(360, size.height)
        assertMinimumsAndAspect(size, sourceWidth = 1920, sourceHeight = 800)
    }

    private fun assertMinimumsAndAspect(size: InputSize, sourceWidth: Int, sourceHeight: Int) {
        assertTrue(size.width >= 480)
        assertTrue(size.height >= 360)
        val sourceRatio = sourceWidth.toDouble() / sourceHeight
        val outputRatio = size.width.toDouble() / size.height
        assertEquals(sourceRatio, outputRatio, 0.002)
    }
}
