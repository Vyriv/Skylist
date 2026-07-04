package dev.ryan.playerlist

import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import java.util.Optional

internal object OrderedTextStyleSupport {
    fun runsToPlain(runs: List<StyledRun>): String = buildString {
        runs.forEach { styledRun -> append(styledRun.text) }
    }

    fun styleHash(runs: List<StyledRun>): Int {
        var hashCode = 1
        runs.forEach { styledRun ->
            hashCode = 31 * hashCode + styledRun.start
            hashCode = 31 * hashCode + styledRun.end
            hashCode = 31 * hashCode + styledRun.style.hashCode()
        }
        return hashCode
    }

    fun orderedTextSource(
        text: FormattedCharSequence,
        bypassCache: Boolean,
        sourceCache: NameStylerIdentityCache<FormattedCharSequence, OrderedTextSourceData>,
        onCacheHit: () -> Unit,
        onCacheMiss: () -> Unit,
    ): OrderedTextSourceLookup {
        if (!bypassCache) {
            val cachedSourceData = sourceCache.get(text)
            if (cachedSourceData != null) {
                onCacheHit()
                return OrderedTextSourceLookup(cachedSourceData, true)
            }
        }

        onCacheMiss()
        val styledRuns = collectRuns(text)
        val plainText = runsToPlain(styledRuns)
        val styleHash = styleHash(styledRuns)
        val sourceData = OrderedTextSourceData(
            plain = plainText,
            characterCount = plainText.length,
            runs = styledRuns,
            styleHash = styleHash,
        )
        if (!bypassCache) {
            sourceCache.put(text, sourceData)
        }
        return OrderedTextSourceLookup(sourceData, false)
    }

    fun buildOrderedTextPlan(
        runs: List<StyledRun>,
        plain: String,
        kind: TransformKind,
        candidatesForKind: (TransformKind) -> List<PlayerCustomizationRegistry.NameCandidate>,
        resolveAnimatedGradientStyle: (PlayerCustomizationRegistry.PlayerCustomization) -> AnimatedGradientStyle?,
    ): OrderedTextTransformPlan? {
        val candidates = candidatesForKind(kind)
        if (plain.isEmpty() || candidates.isEmpty()) {
            return null
        }

        val includeBadges = kind != TransformKind.GRADIENT_TEXT && kind != TransformKind.CHAT_HEADER_TEXT
        val terminalBadgesOnly = kind == TransformKind.SCOREBOARD_TEXT
        val matchBoundary = if (kind == TransformKind.CHAT_HEADER_TEXT) chatHeaderBoundary(plain) else plain.length
        val orderedMatches = mutableListOf<ResolvedOrderedMatch>()
        var hasAnimatedGradient = false
        var searchIndex = 0

        while (searchIndex < matchBoundary) {
            val matchedCustomization = NameStyleMatcher.findFirstNameMatch(plain, candidates, searchIndex) ?: break
            val customization = matchedCustomization.customization
            val startIndex = matchedCustomization.nameMatch.index
            val endIndex = startIndex + matchedCustomization.nameMatch.matchedName.length
            if (startIndex >= matchBoundary || endIndex > matchBoundary) {
                break
            }

            val usesAnimatedGradient = customization.nameAnimated &&
                customization.nameColors?.let { gradientColors -> gradientColors.left != gradientColors.right } == true
            val badgeText = customization.nameBadge?.text
            orderedMatches.add(
                ResolvedOrderedMatch(
                    start = startIndex,
                    end = endIndex,
                    content = plain.substring(startIndex, endIndex),
                    baseStyle = styleAt(runs, startIndex),
                    customization = customization,
                    animatedStyle = resolveAnimatedGradientStyle(customization),
                    isAnimatedGradient = usesAnimatedGradient,
                    hasBadge = customization.hasBadge,
                    hasDecorations = customization.hasDecorations,
                    hasExplicitNameColors = customization.explicitNameColors,
                    hasBadgeAlready = includeBadges &&
                        badgeText != null &&
                        LegacyMinecraftTextStyler.hasPlainBadgeImmediatelyAfter(plain, endIndex, badgeText),
                    hasTrailingContent = terminalBadgesOnly &&
                        LegacyMinecraftTextStyler.hasPlainVisibleContentAfter(plain, endIndex),
                ),
            )
            hasAnimatedGradient = hasAnimatedGradient || usesAnimatedGradient
            searchIndex = endIndex
        }

        if (orderedMatches.isEmpty()) {
            return null
        }
        return OrderedTextTransformPlan(matches = orderedMatches, hasAnimatedGradient = hasAnimatedGradient)
    }

    fun rebuildOrderedTextFromPlan(
        source: OrderedTextSourceData,
        plan: OrderedTextTransformPlan,
        includeBadges: Boolean,
        animationTime: Double,
        replaceMatchedName: Boolean,
        cachedGradient: (String, PlayerCustomizationRegistry.PlayerCustomization, Style) -> Text,
        styledMatchText: (ResolvedOrderedMatch, Double) -> Text,
        appendBadge: (Text, PlayerCustomizationRegistry.PlayerCustomization, Style) -> Text,
    ): Text {
        val rebuiltText = Component.empty()
        var currentIndex = 0

        plan.matches.forEach { orderedMatch ->
            if (orderedMatch.start > currentIndex) {
                appendOriginalRange(rebuiltText, source.runs, currentIndex, orderedMatch.start)
            }

            val styledName = when {
                replaceMatchedName -> cachedGradient(
                    orderedMatch.customization.displayName(orderedMatch.content),
                    orderedMatch.customization,
                    orderedMatch.baseStyle,
                )
                orderedMatch.hasExplicitNameColors -> styledMatchText(orderedMatch, animationTime)
                else -> buildOriginalRangeText(source.runs, orderedMatch.start, orderedMatch.end)
            }

            if (includeBadges && orderedMatch.hasBadge && !orderedMatch.hasBadgeAlready && !orderedMatch.hasTrailingContent) {
                rebuiltText.append(appendBadge(styledName, orderedMatch.customization, orderedMatch.baseStyle))
            } else {
                rebuiltText.append(styledName)
            }
            currentIndex = orderedMatch.end
        }

        if (currentIndex < source.characterCount) {
            appendOriginalRange(rebuiltText, source.runs, currentIndex, source.characterCount)
        }
        return rebuiltText
    }

    fun collectRuns(message: Text): List<StyledRun> {
        val styledRuns = mutableListOf<StyledRun>()
        var startIndex = 0
        message.visit({ style, segment ->
            if (segment.isNotEmpty()) {
                styledRuns.add(
                    StyledRun(
                        start = startIndex,
                        end = startIndex + segment.length,
                        text = segment,
                        style = style,
                    ),
                )
                startIndex += segment.length
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return styledRuns
    }

    fun collectRuns(message: FormattedCharSequence): List<StyledRun> {
        val styledRuns = mutableListOf<StyledRun>()
        var currentStyle: Style? = null
        var currentTextBuilder = StringBuilder()
        var runStartIndex = 0
        var visibleIndex = 0

        fun flushRun() {
            val activeStyle = currentStyle ?: return
            if (currentTextBuilder.isNotEmpty()) {
                val runText = currentTextBuilder.toString()
                styledRuns.add(
                    StyledRun(
                        start = runStartIndex,
                        end = runStartIndex + runText.length,
                        text = runText,
                        style = activeStyle,
                    ),
                )
                runStartIndex += runText.length
                currentTextBuilder = StringBuilder()
            }
        }

        message.accept { _, style, codePoint ->
            if (currentStyle != null && currentStyle != style) {
                flushRun()
            }
            if (currentStyle == null) {
                runStartIndex = visibleIndex
            }
            currentStyle = style
            currentTextBuilder.appendCodePoint(codePoint)
            visibleIndex += Character.charCount(codePoint)
            true
        }
        flushRun()

        return styledRuns
    }

    fun appendOriginalRange(
        target: MutableComponent,
        runs: List<StyledRun>,
        start: Int,
        end: Int,
    ) {
        if (start >= end) {
            return
        }

        runs.forEach { styledRun ->
            if (styledRun.end <= start || styledRun.start >= end) {
                return@forEach
            }

            val localStart = (start - styledRun.start).coerceAtLeast(0)
            val localEnd = (end - styledRun.start).coerceAtMost(styledRun.text.length)
            if (localStart < localEnd) {
                target.append(Component.literal(styledRun.text.substring(localStart, localEnd)).setStyle(styledRun.style))
            }
        }
    }

    fun buildOriginalRangeText(runs: List<StyledRun>, start: Int, end: Int): Text {
        val output = Component.empty()
        appendOriginalRange(output, runs, start, end)
        return output
    }

    fun styleAt(runs: List<StyledRun>, index: Int): Style =
        runs.firstOrNull { styledRun -> index in styledRun.start until styledRun.end }?.style ?: Style.EMPTY

    fun plainText(message: FormattedText): String = buildString {
        message.visit({ _, segment ->
            append(segment)
            Optional.empty<Unit>()
        }, Style.EMPTY)
    }

    fun chatHeaderBoundary(plain: String): Int {
        val delimiterIndex = plain.indexOf(": ")
        return if (delimiterIndex == -1) Int.MAX_VALUE else delimiterIndex
    }
}
