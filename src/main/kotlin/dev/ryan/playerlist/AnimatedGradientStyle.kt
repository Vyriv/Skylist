package dev.ryan.playerlist

import kotlin.math.floor

class AnimatedGradientStyle(
    // Precomputed RGB steps for a looping two-color gradient animation.
    val steps: IntArray,
    val speed: Float,
    val spacing: Float,
) {
    fun offsetAt(time: Double): Float {
        if (steps.isEmpty()) {
            return 0f
        }

        return positiveModulo(time * speed.toDouble(), steps.size.toDouble()).toFloat()
    }

    fun getColor(charIndex: Int, characterCount: Int, time: Double): Int {
        if (steps.isEmpty()) {
            return 0
        }

        val normalizedPosition = if (characterCount <= 1) 0.0 else charIndex.toDouble() / (characterCount - 1).toDouble()
        val normalizedOffset = offsetAt(time).toDouble() / steps.size.toDouble()
        val normalized = positiveModulo((normalizedPosition * spacing.toDouble()) + normalizedOffset, 1.0)
        val wrapped = normalized * steps.size.toDouble()
        val baseIndex = floor(wrapped).toInt()
        val nextIndex = (baseIndex + 1) % steps.size
        val blend = (wrapped - baseIndex).toFloat()
        return interpolate(steps[baseIndex], steps[nextIndex], blend)
    }

    companion object {
        fun buildLoopGradient(primaryColor: Int, secondaryColor: Int, stepsCount: Int): IntArray {
            val count = stepsCount.coerceAtLeast(2)
            val half = count / 2
            val remaining = count - half
            val gradientSteps = IntArray(count)

            for (index in 0 until half) {
                val progress = if (half <= 1) 1f else index.toFloat() / (half - 1).toFloat()
                gradientSteps[index] = interpolate(primaryColor, secondaryColor, progress)
            }

            for (index in 0 until remaining) {
                val progress = if (remaining <= 1) 1f else index.toFloat() / (remaining - 1).toFloat()
                gradientSteps[half + index] = interpolate(secondaryColor, primaryColor, progress)
            }

            return gradientSteps
        }

        private fun positiveModulo(value: Double, modulus: Double): Double {
            val remainder = value % modulus
            return if (remainder < 0.0) remainder + modulus else remainder
        }

        private fun interpolate(start: Int, end: Int, progress: Float): Int {
            val clamped = progress.coerceIn(0f, 1f)
            val startRed = (start shr 16) and 0xFF
            val startGreen = (start shr 8) and 0xFF
            val startBlue = start and 0xFF

            val endRed = (end shr 16) and 0xFF
            val endGreen = (end shr 8) and 0xFF
            val endBlue = end and 0xFF

            val redChannel = (startRed + ((endRed - startRed) * clamped)).toInt().coerceIn(0, 255)
            val greenChannel = (startGreen + ((endGreen - startGreen) * clamped)).toInt().coerceIn(0, 255)
            val blueChannel = (startBlue + ((endBlue - startBlue) * clamped)).toInt().coerceIn(0, 255)

            return (redChannel shl 16) or (greenChannel shl 8) or blueChannel
        }
    }
}
