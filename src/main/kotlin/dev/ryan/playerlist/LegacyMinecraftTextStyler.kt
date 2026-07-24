package dev.ryan.playerlist

import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.util.Optional

internal data class RankPrefixReplacement(
    val before: String,
    val replacement: String,
    val copyName: Boolean,
)

internal object LegacyMinecraftTextStyler {
    private const val maximumTrackedPrefixLength = 96
    private val rankPrefixSuffixRegex = Regex("\\[[^\\]]+]\\s*$")

    // Hypixel always prefixes ranked players with "[RANK] " immediately before the
    // username. We only ever replace that exact trailing bracket + whitespace, so a
    // player with no matching rank tag in front of their name is left untouched.
    fun resolveRankPrefixReplacement(
        beforeText: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): RankPrefixReplacement? {
        val rankPrefix = customization.nameRankPrefix
        if (!customization.hasRankPrefix || rankPrefix == null || beforeText.isEmpty()) {
            return null
        }

        val match = rankPrefixSuffixRegex.find(beforeText) ?: return null
        val matchedText = match.value
        val bracketEnd = matchedText.indexOf(']') + 1
        val trailingWhitespace = if (bracketEnd in 0..matchedText.length) matchedText.substring(bracketEnd) else ""
        return RankPrefixReplacement(
            before = beforeText.substring(0, match.range.first),
            replacement = rankPrefix.text + trailingWhitespace,
            copyName = rankPrefix.copyName(),
        )
    }

    fun rememberStyledSegment(recentSegments: ArrayDeque<StyledSegment>, segmentText: String, segmentStyle: Style) {
        if (segmentText.isEmpty()) {
            return
        }

        recentSegments.addLast(StyledSegment(segmentText, segmentStyle))
        var totalTrackedLength = recentSegments.sumOf { it.text.length }
        while (totalTrackedLength > maximumTrackedPrefixLength && recentSegments.isNotEmpty()) {
            totalTrackedLength -= recentSegments.removeFirst().text.length
        }
    }

    fun inheritedLegacyRankCodes(
        text: String,
        matchIndex: Int,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): String {
        if (customization.hasExplicitNameColors()) {
            return ""
        }

        val prefix = text.substring(0, matchIndex)
        val visiblePrefix = StringBuilder(prefix.length)
        val visibleIndexToRawIndex = ArrayList<Int>(prefix.length)
        var rawIndex = 0
        while (rawIndex < prefix.length) {
            val character = prefix[rawIndex]
            if (character == LEGACY_MINECRAFT_FORMAT_CODE && rawIndex + 1 < prefix.length) {
                rawIndex += 2
                continue
            }

            visiblePrefix.append(character)
            visibleIndexToRawIndex.add(rawIndex)
            rawIndex++
        }

        val rankSuffixMatch = rankPrefixSuffixRegex.find(visiblePrefix.toString()) ?: return ""
        val lastVisibleCharacterIndex = (rankSuffixMatch.range.last downTo rankSuffixMatch.range.first)
            .firstOrNull { !visiblePrefix[it].isWhitespace() } ?: return ""
        val rawEndExclusive = visibleIndexToRawIndex[lastVisibleCharacterIndex] + 1
        return activeLegacyCodes(prefix, rawEndExclusive)
    }

    fun parseLegacyFormattedText(raw: String): Text {
        if (LEGACY_MINECRAFT_FORMAT_CODE !in raw) {
            return Component.literal(raw)
        }

        val output = Component.empty()
        var activeStyle = Style.EMPTY
        val plainTextBuffer = StringBuilder()
        var rawIndex = 0

        fun flushBufferedText() {
            if (plainTextBuffer.isNotEmpty()) {
                output.append(Component.literal(plainTextBuffer.toString()).setStyle(activeStyle))
                plainTextBuffer.clear()
            }
        }

        while (rawIndex < raw.length) {
            val character = raw[rawIndex]
            if (character == LEGACY_MINECRAFT_FORMAT_CODE && rawIndex + 1 < raw.length) {
                flushBufferedText()
                val formatCode = raw[rawIndex + 1].lowercaseChar()
                if (formatCode == 'x') {
                    val hexColor = StringBuilder(6)
                    var hexCursor = rawIndex + 2
                    var validHexColor = true
                    repeat(6) {
                        if (hexCursor + 1 >= raw.length || raw[hexCursor] != LEGACY_MINECRAFT_FORMAT_CODE) {
                            validHexColor = false
                            return@repeat
                        }

                        val hexDigit = raw[hexCursor + 1]
                        if (!hexDigit.isDigit() && hexDigit.lowercaseChar() !in 'a'..'f') {
                            validHexColor = false
                            return@repeat
                        }

                        hexColor.append(hexDigit)
                        hexCursor += 2
                    }

                    if (validHexColor) {
                        activeStyle = activeStyle.withColor(hexColor.toString().toInt(16))
                        rawIndex = hexCursor
                        continue
                    }
                }

                activeStyle = applyLegacyCode(activeStyle, formatCode)
                rawIndex += 2
                continue
            }

            plainTextBuffer.append(character)
            rawIndex++
        }
        flushBufferedText()

        return output
    }

    fun normalizeLegacyText(message: Text): Text {
        val rebuiltText = Component.empty()
        var changed = false
        message.visit({ style, segment ->
            if (segment.isEmpty()) {
                return@visit Optional.empty<Unit>()
            }

            if (LEGACY_MINECRAFT_FORMAT_CODE in segment) {
                appendLegacySegment(rebuiltText, segment, style)
                changed = true
            } else {
                rebuiltText.append(Component.literal(segment).setStyle(style))
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return if (changed) rebuiltText else message
    }

    fun appendLegacySegment(target: MutableComponent, raw: String, baseStyle: Style) {
        parseLegacyFormattedText(raw).visit({ style, segment ->
            target.append(Component.literal(segment).setStyle(style.withParent(baseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
    }

    fun inheritedLegacyRankStyle(text: String, defaultStyle: Style): Style {
        val visibleText = StringBuilder(text.length)
        val visibleIndexToRawIndex = ArrayList<Int>(text.length)
        var rawIndex = 0
        while (rawIndex < text.length) {
            val character = text[rawIndex]
            if (character == LEGACY_MINECRAFT_FORMAT_CODE && rawIndex + 1 < text.length) {
                rawIndex += 2
                continue
            }

            visibleText.append(character)
            visibleIndexToRawIndex.add(rawIndex)
            rawIndex++
        }

        val rankSuffixMatch = rankPrefixSuffixRegex.find(visibleText.toString()) ?: return defaultStyle
        val lastVisibleCharacterIndex = (rankSuffixMatch.range.last downTo rankSuffixMatch.range.first)
            .firstOrNull { !visibleText[it].isWhitespace() } ?: return defaultStyle
        val rawEndExclusive = visibleIndexToRawIndex[lastVisibleCharacterIndex] + 1
        val inheritedCodes = activeLegacyCodes(text, rawEndExclusive)
        return if (inheritedCodes.isEmpty()) defaultStyle else applyActiveLegacyCodes(defaultStyle, inheritedCodes)
    }

    fun hasBadgeImmediatelyAfter(text: String, startIndex: Int, badgeText: String): Boolean {
        var cursor = startIndex
        while (cursor + 1 < text.length && text[cursor] == LEGACY_MINECRAFT_FORMAT_CODE) {
            cursor += 2
        }
        if (cursor >= text.length || text[cursor] != ' ') {
            return false
        }
        cursor++
        while (cursor + 1 < text.length && text[cursor] == LEGACY_MINECRAFT_FORMAT_CODE) {
            cursor += 2
        }
        return cursor + badgeText.length <= text.length && text.regionMatches(cursor, badgeText, 0, badgeText.length)
    }

    fun hasVisibleContentAfter(text: String, startIndex: Int): Boolean {
        var cursor = startIndex
        while (cursor < text.length) {
            if (text[cursor] == LEGACY_MINECRAFT_FORMAT_CODE && cursor + 1 < text.length) {
                cursor += 2
                continue
            }
            if (!text[cursor].isWhitespace()) {
                return true
            }
            cursor++
        }
        return false
    }

    fun hasPlainBadgeImmediatelyAfter(text: String, startIndex: Int, badgeText: String): Boolean {
        var cursor = startIndex
        while (cursor < text.length && text[cursor].isWhitespace()) {
            cursor++
        }
        return cursor + badgeText.length <= text.length && text.regionMatches(cursor, badgeText, 0, badgeText.length)
    }

    fun hasPlainVisibleContentAfter(text: String, startIndex: Int): Boolean {
        var cursor = startIndex
        while (cursor < text.length) {
            if (!text[cursor].isWhitespace()) {
                return true
            }
            cursor++
        }
        return false
    }

    fun activeLegacyCodes(text: String, endExclusive: Int): String {
        var activeColorCode: Char? = null
        val activeFormattingCodes = linkedSetOf<Char>()
        var index = 0
        while (index < endExclusive - 1) {
            if (text[index] != LEGACY_MINECRAFT_FORMAT_CODE) {
                index++
                continue
            }

            val formatCode = text[index + 1].lowercaseChar()
            when (formatCode) {
                in '0'..'9', in 'a'..'f' -> {
                    activeColorCode = formatCode
                    activeFormattingCodes.clear()
                }
                'k', 'l', 'm', 'n', 'o' -> activeFormattingCodes.add(formatCode)
                'r' -> {
                    activeColorCode = null
                    activeFormattingCodes.clear()
                }
            }
            index += 2
        }

        return buildString {
            activeColorCode?.let { colorCode ->
                append(LEGACY_MINECRAFT_FORMAT_CODE)
                append(colorCode)
            }
            activeFormattingCodes.forEach { formatCode ->
                append(LEGACY_MINECRAFT_FORMAT_CODE)
                append(formatCode)
            }
        }
    }

    private fun applyLegacyCode(style: Style, code: Char): Style =
        when (code) {
            in '0'..'9', in 'a'..'f' -> {
                val formatting = ChatFormatting.getByCode(code) ?: return Style.EMPTY
                Style.EMPTY.withColor(formatting)
            }
            'k' -> style.withObfuscated(true)
            'l' -> style.withBold(true)
            'm' -> style.withStrikethrough(true)
            'n' -> style.withUnderline(true)
            'o' -> style.withItalic(true)
            'r' -> Style.EMPTY
            else -> style
        }

    private fun applyActiveLegacyCodes(style: Style, codes: String): Style {
        var outputStyle = style
        var index = 0
        while (index < codes.length - 1) {
            if (codes[index] != LEGACY_MINECRAFT_FORMAT_CODE) {
                index++
                continue
            }

            outputStyle = when (val code = codes[index + 1].lowercaseChar()) {
                in '0'..'9', in 'a'..'f' -> {
                    val formatting = ChatFormatting.getByCode(code)
                    if (formatting != null) outputStyle.withColor(formatting) else outputStyle
                }
                'k' -> outputStyle.withObfuscated(true)
                'l' -> outputStyle.withBold(true)
                'm' -> outputStyle.withStrikethrough(true)
                'n' -> outputStyle.withUnderline(true)
                'o' -> outputStyle.withItalic(true)
                'r' -> Style.EMPTY
                else -> outputStyle
            }
            index += 2
        }
        return outputStyle
    }
}
