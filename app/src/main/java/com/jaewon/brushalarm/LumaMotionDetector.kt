package com.jaewon.brushalarm

class LumaMotionDetector(private val threshold: Double = 13.0) {
    private var previous: ByteArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    fun detect(
        luma: ByteArray,
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean {
        val old = previous
        var difference = 0L
        var samples = 0
        if (old != null && previousWidth == width && previousHeight == height) {
            val safeLeft = left.coerceIn(0, width - 1)
            val safeTop = top.coerceIn(0, height - 1)
            val safeRight = right.coerceIn(safeLeft + 1, width)
            val safeBottom = bottom.coerceIn(safeTop + 1, height)
            for (y in safeTop until safeBottom step 2) {
                for (x in safeLeft until safeRight step 2) {
                    val index = y * width + x
                    difference += kotlin.math.abs((luma[index].toInt() and 0xff) - (old[index].toInt() and 0xff))
                    samples++
                }
            }
        }
        previous = luma.copyOf()
        previousWidth = width
        previousHeight = height
        return samples > 0 && difference.toDouble() / samples >= threshold
    }
}
