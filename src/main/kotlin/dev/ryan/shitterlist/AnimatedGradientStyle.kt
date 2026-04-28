package dev.ryan.throwerlist

import kotlin.math.floor

class AnimatedGradientStyle(
    val steps: IntArray,
    val speed: Float,
) {
    fun offsetAt(time: Double): Float {
        if (steps.isEmpty()) {
            return 0f
        }

        return positiveModulo(time * speed.toDouble(), steps.size.toDouble()).toFloat()
    }

    fun getColor(charIndex: Int, time: Double): Int {
        if (steps.isEmpty()) {
            return 0
        }

        val wrapped = positiveModulo(charIndex.toDouble() + offsetAt(time).toDouble(), steps.size.toDouble())
        val baseIndex = floor(wrapped).toInt()
        val nextIndex = (baseIndex + 1) % steps.size
        val blend = (wrapped - baseIndex).toFloat()
        return interpolate(steps[baseIndex], steps[nextIndex], blend)
    }

    companion object {
        fun buildLoopGradient(color1: Int, color2: Int, stepsCount: Int): IntArray {
            val count = stepsCount.coerceAtLeast(2)
            val half = count / 2
            val remaining = count - half
            val steps = IntArray(count)

            for (index in 0 until half) {
                val progress = if (half <= 1) 1f else index.toFloat() / (half - 1).toFloat()
                steps[index] = interpolate(color1, color2, progress)
            }

            for (index in 0 until remaining) {
                val progress = if (remaining <= 1) 1f else index.toFloat() / (remaining - 1).toFloat()
                steps[half + index] = interpolate(color2, color1, progress)
            }

            return steps
        }

        private fun positiveModulo(value: Double, modulus: Double): Double {
            val remainder = value % modulus
            return if (remainder < 0.0) remainder + modulus else remainder
        }

        private fun interpolate(start: Int, end: Int, progress: Float): Int {
            val startR = (start shr 16) and 0xFF
            val startG = (start shr 8) and 0xFF
            val startB = start and 0xFF

            val endR = (end shr 16) and 0xFF
            val endG = (end shr 8) and 0xFF
            val endB = end and 0xFF

            val r = (startR + ((endR - startR) * progress)).toInt().coerceIn(0, 255)
            val g = (startG + ((endG - startG) * progress)).toInt().coerceIn(0, 255)
            val b = (startB + ((endB - startB) * progress)).toInt().coerceIn(0, 255)

            return (r shl 16) or (g shl 8) or b
        }
    }
}
