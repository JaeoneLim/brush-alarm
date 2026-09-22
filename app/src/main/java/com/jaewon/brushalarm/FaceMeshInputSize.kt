package com.jaewon.brushalarm

data class InputSize(val width: Int, val height: Int)

/** Smallest aspect-preserving size whose two dimensions meet ML Kit's documented minimum. */
object FaceMeshInputSize {
    private const val MIN_WIDTH = 480
    private const val MIN_HEIGHT = 360

    fun minimum() = InputSize(MIN_WIDTH, MIN_HEIGHT)

    fun forSource(width: Int, height: Int): InputSize {
        require(width > 0 && height > 0)
        return if (MIN_WIDTH.toLong() * height >= MIN_HEIGHT.toLong() * width) {
            InputSize(MIN_WIDTH, ceilDivide(height.toLong() * MIN_WIDTH, width))
        } else {
            InputSize(ceilDivide(width.toLong() * MIN_HEIGHT, height), MIN_HEIGHT)
        }
    }

    private fun ceilDivide(numerator: Long, denominator: Int): Int =
        ((numerator + denominator - 1) / denominator).toInt()
}
