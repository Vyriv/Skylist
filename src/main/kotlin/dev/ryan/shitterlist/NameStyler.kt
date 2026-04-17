package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import net.minecraft.text.MutableText
import net.minecraft.text.OrderedText
import net.minecraft.text.StringVisitable
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object NameStyler {
    private const val legacyFormat = '\u00A7'

    private data class GradientCacheKey(
        val content: String,
        val leftColor: Int,
        val rightColor: Int,
    )

    private data class StyledSegment(
        val text: String,
        val style: Style,
    )

    private data class NameMatch(
        val index: Int,
        val matchedName: String,
    )

    private val componentCache = ConcurrentHashMap<GradientCacheKey, Text>()

    fun isTargetProfile(profile: GameProfile?): Boolean = PlayerCustomizationRegistry.find(profile) != null

    fun isTargetName(name: String?): Boolean = PlayerCustomizationRegistry.findByName(name) != null

    fun hasStyledProfile(profile: GameProfile?): Boolean =
        PlayerCustomizationRegistry.find(profile)?.let(::hasNameCustomization) == true

    fun hasStyledName(name: String?): Boolean =
        PlayerCustomizationRegistry.findByName(name)?.let(::hasNameCustomization) == true

    fun hasExplicitNameColors(name: String?): Boolean =
        PlayerCustomizationRegistry.findByName(name)?.nameColors != null

    fun containsTargetName(text: String?): Boolean =
        !text.isNullOrEmpty() && PlayerCustomizationRegistry.entries.any { customization ->
            customization.matchNames().any { name -> text.contains(name, ignoreCase = true) }
        }

    fun containsStyledTargetName(text: String?): Boolean =
        !text.isNullOrEmpty() &&
            PlayerCustomizationRegistry.entries.any { customization ->
                hasNameCustomization(customization) &&
                    customization.matchNames().any { name -> text.contains(name, ignoreCase = true) }
            }

    fun styledSelfName(profile: GameProfile?): Text {
        val customization = PlayerCustomizationRegistry.find(profile)
            ?: return Text.literal(profile?.name ?: "")
        val matchedName = profile?.name ?: customization.username
        return appendBadge(cachedGradient(matchedName, customization), customization)
    }

    fun styledSelfName(name: String?): Text {
        val customization = PlayerCustomizationRegistry.findByName(name)
            ?: return Text.literal(name ?: "")
        val matchedName = name ?: customization.username
        return appendBadge(cachedGradient(matchedName, customization), customization)
    }

    fun applyNameplateDecorations(message: Text): Text =
        rebuildVisitable(normalizeLegacyText(message), includeBadges = true)

    fun applyNameplateDecorations(raw: String): Text = rebuildVisitable(parseLegacyFormattedText(raw), includeBadges = true)

    fun applyGradientToName(message: Text): Text = rebuildVisitable(normalizeLegacyText(message))

    fun applyGradientToChatHeader(message: Text): Text =
        rebuildVisitable(normalizeLegacyText(message), chatHeaderOnly = true)

    fun applyGradientToVisitable(message: StringVisitable): StringVisitable = rebuildVisitable(message)

    fun applyGradientToOrderedText(text: OrderedText): OrderedText {
        val rebuilt = rebuildOrderedText(text) ?: return text
        return rebuilt.asOrderedText()
    }

    fun applyGradientToString(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return raw
        }

        var output: String = raw
        PlayerCustomizationRegistry.entries.forEach { customization ->
            if (!hasNameCustomization(customization) || customization.nameColors == null) {
                return@forEach
            }

            if (findNameMatch(output, customization) == null) {
                return@forEach
            }

            val rebuilt = StringBuilder()
            var index = 0
            while (index < output.length) {
                val match = findNameMatch(output, customization, index)
                if (match == null) {
                    rebuilt.append(output.substring(index))
                    break
                }

                val matchIndex = match.index
                if (matchIndex > index) {
                    rebuilt.append(output.substring(index, matchIndex))
                }

                val matchedName = output.substring(matchIndex, matchIndex + match.matchedName.length)
                rebuilt.append(toLegacyGradient(matchedName, customization))
                index = matchIndex + match.matchedName.length
            }

            output = rebuilt.toString()
        }

        return output
    }

    fun applyDecorationsToString(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return raw
        }

        var output: String = raw
        PlayerCustomizationRegistry.entries.forEach { customization ->
            if (!hasNameCustomization(customization) || findNameMatch(output, customization) == null) {
                return@forEach
            }

            val rebuilt = StringBuilder()
            var index = 0
            while (index < output.length) {
                val match = findNameMatch(output, customization, index)
                if (match == null) {
                    rebuilt.append(output.substring(index))
                    break
                }

                val matchIndex = match.index
                if (matchIndex > index) {
                    rebuilt.append(output.substring(index, matchIndex))
                }

                val matchedName = output.substring(matchIndex, matchIndex + match.matchedName.length)
                val inheritedRankCodes = inheritedLegacyRankCodes(output, matchIndex, customization)
                rebuilt.append(
                    when {
                        customization.nameColors != null -> toLegacyGradient(matchedName, customization)
                        inheritedRankCodes.isNotEmpty() -> inheritedRankCodes + matchedName
                        else -> matchedName
                    },
                )

                customization.nameBadge?.takeUnless { badge ->
                    hasBadgeImmediatelyAfter(output, matchIndex + match.matchedName.length, badge.text)
                }?.let { badge ->
                    rebuilt.append(' ')
                    rebuilt.append(toLegacyColorCode(badge.color))
                    if (badge.bold) {
                        rebuilt.append(legacyFormat).append('l')
                    }
                    rebuilt.append(badge.text)
                    rebuilt.append(legacyFormat).append('r')
                    rebuilt.append(activeLegacyCodes(output, matchIndex))
                }

                index = matchIndex + match.matchedName.length
            }

            output = rebuilt.toString()
        }

        return output
    }

    private fun rebuildVisitable(
        message: StringVisitable,
        includeBadges: Boolean = false,
        chatHeaderOnly: Boolean = false,
    ): Text {
        val rebuilt = Text.empty()
        var changed = false
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = plainText(message)
        val headerBoundary = if (chatHeaderOnly) chatHeaderBoundary(plain) else Int.MAX_VALUE
        var visibleIndex = 0

        message.visit({ style, segment ->
            val segmentStart = visibleIndex
            val segmentEnd = segmentStart + segment.length
            visibleIndex = segmentEnd

            val decoratedLength = (headerBoundary - segmentStart).coerceIn(0, segment.length)
            val decoratedSegment = segment.substring(0, decoratedLength)
            val untouchedSegment = segment.substring(decoratedLength)

            if (decoratedSegment.isNotEmpty() && containsTargetName(decoratedSegment)) {
                changed = true
                appendStyledSegment(rebuilt, decoratedSegment, style, recentSegments, includeBadges, plain, segmentStart)
            } else if (decoratedSegment.isNotEmpty()) {
                rebuilt.append(Text.literal(decoratedSegment).setStyle(style))
            }

            if (untouchedSegment.isNotEmpty()) {
                rebuilt.append(Text.literal(untouchedSegment).setStyle(style))
            }
            rememberSegment(recentSegments, segment, style)
            Optional.empty<Unit>()
        }, Style.EMPTY)

        return if (changed) rebuilt else if (message is Text) message else rebuilt
    }

    private fun rebuildOrderedText(message: OrderedText): Text? {
        val segments = mutableListOf<Pair<Style, StringBuilder>>()
        var changed = false
        var currentStyle: Style? = null
        var currentBuilder = StringBuilder()

        fun flush() {
            val style = currentStyle ?: return
            if (currentBuilder.isNotEmpty()) {
                segments.add(style to currentBuilder)
                currentBuilder = StringBuilder()
            }
        }

        message.accept { _, style, codePoint ->
            if (currentStyle != null && currentStyle != style) {
                flush()
            }
            currentStyle = style
            currentBuilder.appendCodePoint(codePoint)
            true
        }
        flush()

        if (segments.none { containsTargetName(it.second.toString()) }) {
            return null
        }

        val rebuilt = Text.empty()
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = segments.joinToString(separator = "") { it.second.toString() }
        var visibleIndex = 0
        segments.forEach { (style, builder) ->
            val segment = builder.toString()
            if (containsTargetName(segment)) {
                changed = true
                appendStyledSegment(rebuilt, segment, style, recentSegments, false, plain, visibleIndex)
            } else {
                rebuilt.append(Text.literal(segment).setStyle(style))
            }
            rememberSegment(recentSegments, segment, style)
            visibleIndex += segment.length
        }

        return if (changed) rebuilt else null
    }

    private fun appendStyledSegment(
        target: MutableText,
        segment: String,
        style: Style,
        recentSegments: ArrayDeque<StyledSegment>,
        includeBadges: Boolean = false,
        plainText: String = segment,
        segmentStart: Int = 0,
    ) {
        var remaining = segment
        var localOffset = 0

        while (remaining.isNotEmpty()) {
            val match = PlayerCustomizationRegistry.entries
                .mapNotNull { customization ->
                    if (!hasNameCustomization(customization)) {
                        return@mapNotNull null
                    }
                    val match = findNameMatch(remaining, customization) ?: return@mapNotNull null
                    Triple(match.index, customization, match.matchedName)
                }
                .minByOrNull { it.first }

            if (match == null) {
                target.append(Text.literal(remaining).setStyle(style))
                return
            }

            val (matchIndex, customization, matchedName) = match
            if (matchIndex > 0) {
                target.append(Text.literal(remaining.substring(0, matchIndex)).setStyle(style))
            }

            val resolvedMatchedName = remaining.substring(matchIndex, matchIndex + matchedName.length)
            val baseNameStyle = inheritedRankStyle(
                prefix = remaining.substring(0, matchIndex),
                customization = customization,
                defaultStyle = style,
                recentSegments = recentSegments,
            )
            val styledName = cachedGradient(resolvedMatchedName, customization, baseNameStyle)
            val hasBadgeAlready = includeBadges && customization.nameBadge != null &&
                hasPlainBadgeImmediatelyAfter(plainText, segmentStart + localOffset + matchIndex + matchedName.length, customization.nameBadge.text)
            target.append(if (includeBadges && !hasBadgeAlready) appendBadge(styledName, customization, style) else styledName)
            remaining = remaining.substring(matchIndex + matchedName.length)
            localOffset += matchIndex + matchedName.length
        }
    }

    private fun findNameMatch(
        text: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        startIndex: Int = 0,
    ): NameMatch? {
        var bestIndex = Int.MAX_VALUE
        var bestName: String? = null
        customization.matchNames().forEach { candidate ->
            val index = text.indexOf(candidate, startIndex, ignoreCase = true)
            if (index == -1) {
                return@forEach
            }

            if (index < bestIndex || (index == bestIndex && (bestName == null || candidate.length > bestName.length))) {
                bestIndex = index
                bestName = candidate
            }
        }

        return bestName?.let { NameMatch(bestIndex, it) }
    }

    private fun inheritedRankStyle(
        prefix: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        defaultStyle: Style,
        recentSegments: ArrayDeque<StyledSegment>,
    ): Style {
        if (customization.nameColors != null) {
            return defaultStyle
        }

        if (defaultStyle.color != null) {
            return defaultStyle
        }

        val rawPrefix = buildString {
            recentSegments.forEach { append(it.text) }
            append(prefix)
        }
        return inheritedLegacyRankStyle(rawPrefix, defaultStyle)
    }

    private fun rememberSegment(recentSegments: ArrayDeque<StyledSegment>, segment: String, style: Style) {
        if (segment.isEmpty()) {
            return
        }

        recentSegments.addLast(StyledSegment(segment, style))
        var totalLength = recentSegments.sumOf { it.text.length }
        while (totalLength > maxTrackedPrefixLength && recentSegments.isNotEmpty()) {
            totalLength -= recentSegments.removeFirst().text.length
        }
    }

    private fun plainText(message: StringVisitable): String = buildString {
        message.visit({ _, segment ->
            append(segment)
            Optional.empty<Unit>()
        }, Style.EMPTY)
    }

    private fun chatHeaderBoundary(plain: String): Int {
        val delimiterIndex = plain.indexOf(": ")
        return if (delimiterIndex == -1) Int.MAX_VALUE else delimiterIndex
    }

    private fun inheritedLegacyRankCodes(
        text: String,
        matchIndex: Int,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): String {
        if (customization.nameColors != null) {
            return ""
        }

        val prefix = text.substring(0, matchIndex)
        val visible = StringBuilder(prefix.length)
        val mapping = ArrayList<Int>(prefix.length)
        var index = 0
        while (index < prefix.length) {
            val character = prefix[index]
            if (character == legacyFormat && index + 1 < prefix.length) {
                index += 2
                continue
            }

            visible.append(character)
            mapping.add(index)
            index++
        }

        val match = rankPrefixSuffixRegex.find(visible.toString()) ?: return ""
        val lastVisibleIndex = (match.range.last downTo match.range.first)
            .firstOrNull { !visible[it].isWhitespace() } ?: return ""
        val rawEndExclusive = mapping[lastVisibleIndex] + 1
        return activeLegacyCodes(prefix, rawEndExclusive)
    }

    private fun appendBadge(
        nameText: Text,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        baseStyle: Style = Style.EMPTY,
    ): Text {
        val badge = customization.nameBadge ?: return nameText
        val output = nameText.copy()
        output.append(Text.literal(" ").setStyle(baseStyle))
        output.append(
            Text.literal(badge.text).setStyle(
                baseStyle
                    .withColor(badge.color)
                    .withBold(badge.bold),
            ),
        )
        return output
    }

    private fun parseLegacyFormattedText(raw: String): Text {
        if (legacyFormat !in raw) {
            return Text.literal(raw)
        }

        val output = Text.empty()
        var style = Style.EMPTY
        val buffer = StringBuilder()
        var index = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                output.append(Text.literal(buffer.toString()).setStyle(style))
                buffer.clear()
            }
        }

        while (index < raw.length) {
            val character = raw[index]
            if (character == legacyFormat && index + 1 < raw.length) {
                flush()
                style = applyLegacyCode(style, raw[index + 1].lowercaseChar())
                index += 2
                continue
            }

            buffer.append(character)
            index++
        }
        flush()

        return output
    }

    private fun normalizeLegacyText(message: Text): Text {
        val rebuilt = Text.empty()
        var changed = false
        message.visit({ style, segment ->
            if (segment.isEmpty()) {
                return@visit Optional.empty<Unit>()
            }

            if (legacyFormat in segment) {
                appendLegacySegment(rebuilt, segment, style)
                changed = true
            } else {
                rebuilt.append(Text.literal(segment).setStyle(style))
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return if (changed) rebuilt else message
    }

    private fun appendLegacySegment(target: MutableText, raw: String, baseStyle: Style) {
        parseLegacyFormattedText(raw).visit({ style, segment ->
            target.append(Text.literal(segment).setStyle(style.withParent(baseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
    }

    private fun applyLegacyCode(style: Style, code: Char): Style =
        when (code) {
            in '0'..'9', in 'a'..'f' -> {
                val formatting = Formatting.byCode(code) ?: return Style.EMPTY
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

    private fun inheritedLegacyRankStyle(text: String, defaultStyle: Style): Style {
        val visible = StringBuilder(text.length)
        val mapping = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == legacyFormat && index + 1 < text.length) {
                index += 2
                continue
            }

            visible.append(character)
            mapping.add(index)
            index++
        }

        val match = rankPrefixSuffixRegex.find(visible.toString()) ?: return defaultStyle
        val lastVisibleIndex = (match.range.last downTo match.range.first)
            .firstOrNull { !visible[it].isWhitespace() } ?: return defaultStyle
        val rawEndExclusive = mapping[lastVisibleIndex] + 1
        val inheritedCodes = activeLegacyCodes(text, rawEndExclusive)
        return if (inheritedCodes.isEmpty()) {
            defaultStyle
        } else {
            applyActiveLegacyCodes(defaultStyle, inheritedCodes)
        }
    }

    private fun applyActiveLegacyCodes(style: Style, codes: String): Style {
        var output = style
        var index = 0
        while (index < codes.length - 1) {
            if (codes[index] != legacyFormat) {
                index++
                continue
            }

            output = when (val code = codes[index + 1].lowercaseChar()) {
                in '0'..'9', in 'a'..'f' -> {
                    val formatting = Formatting.byCode(code)
                    if (formatting != null) output.withColor(formatting) else output
                }
                'k' -> output.withObfuscated(true)
                'l' -> output.withBold(true)
                'm' -> output.withStrikethrough(true)
                'n' -> output.withUnderline(true)
                'o' -> output.withItalic(true)
                'r' -> Style.EMPTY
                else -> output
            }
            index += 2
        }
        return output
    }

    private fun hasNameCustomization(customization: PlayerCustomizationRegistry.PlayerCustomization): Boolean =
        customization.nameColors != null || customization.nameBadge != null

    private fun cachedGradient(
        content: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        baseStyle: Style = Style.EMPTY,
    ): Text {
        val colors = customization.nameColors ?: return Text.literal(content).setStyle(baseStyle)
        val key = GradientCacheKey(content.lowercase(), colors.left, colors.right)
        val cached = componentCache.computeIfAbsent(key) {
            gradientText(content, colors.left, colors.right, Style.EMPTY)
        }

        if (baseStyle.isEmpty) {
            return cached.copy()
        }

        val rebuilt = Text.empty()
        cached.visit({ style, segment ->
            rebuilt.append(Text.literal(segment).setStyle(style.withParent(baseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return rebuilt
    }

    private fun gradientText(content: String, leftColor: Int, rightColor: Int, baseStyle: Style): Text {
        val text = Text.empty()
        val maxIndex = (content.length - 1).coerceAtLeast(1)

        content.forEachIndexed { index, character ->
            val progress = index.toFloat() / maxIndex.toFloat()
            val color = interpolate(leftColor, rightColor, progress)
            text.append(Text.literal(character.toString()).setStyle(baseStyle.withColor(color)))
        }

        return text
    }

    private fun toLegacyGradient(content: String, customization: PlayerCustomizationRegistry.PlayerCustomization): String {
        val colors = customization.nameColors ?: return content
        val output = StringBuilder()
        val maxIndex = (content.length - 1).coerceAtLeast(1)

        content.forEachIndexed { index, character ->
            val progress = index.toFloat() / maxIndex.toFloat()
            val color = interpolate(colors.left, colors.right, progress)
            val hex = "%06X".format(color)
            output.append(legacyFormat).append('x')
            hex.forEach { digit ->
                output.append(legacyFormat).append(digit)
            }
            output.append(character)
        }

        return output.toString()
    }

    private fun activeLegacyCodes(text: String, endExclusive: Int): String {
        var colorCode: Char? = null
        val formats = linkedSetOf<Char>()
        var index = 0
        while (index < endExclusive - 1) {
            if (text[index] != legacyFormat) {
                index++
                continue
            }

            val code = text[index + 1].lowercaseChar()
            when (code) {
                in '0'..'9', in 'a'..'f' -> {
                    colorCode = code
                    formats.clear()
                }
                'k', 'l', 'm', 'n', 'o' -> formats.add(code)
                'r' -> {
                    colorCode = null
                    formats.clear()
                }
            }
            index += 2
        }

        return buildString {
            colorCode?.let {
                append(legacyFormat)
                append(it)
            }
            formats.forEach {
                append(legacyFormat)
                append(it)
            }
        }
    }

    private fun hasBadgeImmediatelyAfter(text: String, startIndex: Int, badgeText: String): Boolean {
        var index = startIndex
        while (index + 1 < text.length && text[index] == legacyFormat) {
            index += 2
        }
        if (index >= text.length || text[index] != ' ') {
            return false
        }
        index++
        while (index + 1 < text.length && text[index] == legacyFormat) {
            index += 2
        }
        return index + badgeText.length <= text.length && text.regionMatches(index, badgeText, 0, badgeText.length)
    }

    private fun hasPlainBadgeImmediatelyAfter(text: String, startIndex: Int, badgeText: String): Boolean {
        var index = startIndex
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        return index + badgeText.length <= text.length && text.regionMatches(index, badgeText, 0, badgeText.length)
    }

    private fun toLegacyColorCode(color: Int): String {
        val formatting = listOf(
            Formatting.BLACK,
            Formatting.DARK_BLUE,
            Formatting.DARK_GREEN,
            Formatting.DARK_AQUA,
            Formatting.DARK_RED,
            Formatting.DARK_PURPLE,
            Formatting.GOLD,
            Formatting.GRAY,
            Formatting.DARK_GRAY,
            Formatting.BLUE,
            Formatting.GREEN,
            Formatting.AQUA,
            Formatting.RED,
            Formatting.LIGHT_PURPLE,
            Formatting.YELLOW,
            Formatting.WHITE,
        ).firstOrNull { it.colorValue == color }

        return if (formatting != null) {
            "$legacyFormat${formatting.code}"
        } else {
            val hex = "%06X".format(color and 0xFFFFFF)
            buildString {
                append(legacyFormat).append('x')
                hex.forEach {
                    append(legacyFormat).append(it)
                }
            }
        }
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
    private const val maxTrackedPrefixLength = 96
    private val rankPrefixSuffixRegex = Regex("""\[[^\]]+]\s*$""")
}
