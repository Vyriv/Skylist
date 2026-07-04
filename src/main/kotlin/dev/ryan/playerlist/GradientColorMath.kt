package dev.ryan.playerlist

import net.minecraft.ChatFormatting

internal const val LEGACY_MINECRAFT_FORMAT_CODE: Char = '§'

internal object GradientColorMath {
    fun interpolateRgbColor(startColor: Int, endColor: Int, progress: Float): Int {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val startRed = (startColor shr 16) and 0xFF
        val startGreen = (startColor shr 8) and 0xFF
        val startBlue = startColor and 0xFF

        val endRed = (endColor shr 16) and 0xFF
        val endGreen = (endColor shr 8) and 0xFF
        val endBlue = endColor and 0xFF

        val redChannel = (startRed + ((endRed - startRed) * clampedProgress)).toInt().coerceIn(0, 255)
        val greenChannel = (startGreen + ((endGreen - startGreen) * clampedProgress)).toInt().coerceIn(0, 255)
        val blueChannel = (startBlue + ((endBlue - startBlue) * clampedProgress)).toInt().coerceIn(0, 255)
        return (redChannel shl 16) or (greenChannel shl 8) or blueChannel
    }

    fun gradientFrequencyProgress(characterIndex: Int, characterCount: Int, gradientSpacing: Float): Float {
        val normalizedPosition = if (characterCount <= 1) 0f else characterIndex.toFloat() / (characterCount - 1).toFloat()
        return positiveModulo(normalizedPosition * gradientSpacing, 1f)
    }

    fun gradientLoopColor(startColor: Int, endColor: Int, progress: Float): Int {
        val loopProgress = if (progress <= 0.5f) progress * 2f else (1f - progress) * 2f
        return interpolateRgbColor(startColor, endColor, loopProgress)
    }

    fun positiveModulo(value: Float, modulus: Float): Float {
        val remainder = value % modulus
        return if (remainder < 0f) remainder + modulus else remainder
    }

    fun toLegacyColorCode(color: Int): String {
        val matchingFormatting = listOf(
            ChatFormatting.BLACK,
            ChatFormatting.DARK_BLUE,
            ChatFormatting.DARK_GREEN,
            ChatFormatting.DARK_AQUA,
            ChatFormatting.DARK_RED,
            ChatFormatting.DARK_PURPLE,
            ChatFormatting.GOLD,
            ChatFormatting.GRAY,
            ChatFormatting.DARK_GRAY,
            ChatFormatting.BLUE,
            ChatFormatting.GREEN,
            ChatFormatting.AQUA,
            ChatFormatting.RED,
            ChatFormatting.LIGHT_PURPLE,
            ChatFormatting.YELLOW,
            ChatFormatting.WHITE,
        ).firstOrNull { it.colorValue == color }

        return if (matchingFormatting != null) {
            "$LEGACY_MINECRAFT_FORMAT_CODE${matchingFormatting.code}"
        } else {
            buildLegacyHexColorCode(color)
        }
    }

    fun buildLegacyHexColorCode(color: Int): String {
        val sixDigitHexColor = "%06X".format(color and 0xFFFFFF)
        return buildString {
            append(LEGACY_MINECRAFT_FORMAT_CODE).append('x')
            sixDigitHexColor.forEach { hexDigit ->
                append(LEGACY_MINECRAFT_FORMAT_CODE).append(hexDigit)
            }
        }
    }
}
