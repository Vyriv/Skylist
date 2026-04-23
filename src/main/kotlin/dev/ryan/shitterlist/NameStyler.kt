package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import net.minecraft.text.MutableText
import net.minecraft.text.OrderedText
import net.minecraft.text.StringVisitable
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.LinkedHashMap
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

object NameStyler {
    private const val legacyFormat = '\u00A7'
    private const val textCacheLimit = 512
    private const val stringCacheLimit = 1024
    private const val matchCacheLimit = 2048
    private const val identityCacheSize = 2048

    private enum class TransformKind {
        GRADIENT_TEXT,
        NAMEPLATE_TEXT,
        SCOREBOARD_TEXT,
        SIDEBAR_TEXT,
        CHAT_HEADER_TEXT,
        GRADIENT_STRING,
        DECORATED_STRING,
        SCOREBOARD_STRING,
    }

    private data class GradientCacheKey(
        val content: String,
        val leftColor: Int,
        val rightColor: Int,
    )

    private data class StyledSegment(
        val text: String,
        val style: Style,
    )

    private data class StyledRun(
        val start: Int,
        val end: Int,
        val text: String,
        val style: Style,
    )

    private data class NameMatch(
        val index: Int,
        val matchedName: String,
    )

    private data class MatchedCustomization(
        val nameMatch: NameMatch,
        val customization: PlayerCustomizationRegistry.PlayerCustomization,
    )

    private data class TextCacheKey(
        val version: Long,
        val kind: TransformKind,
        val plain: String,
        val styleHash: Int,
    )

    private data class StringCacheKey(
        val version: Long,
        val kind: TransformKind,
        val raw: String,
    )

    private data class MatchCacheKey(
        val version: Long,
        val kind: TransformKind,
        val raw: String,
    )

    private data class SelfNameCacheKey(
        val version: Long,
        val name: String,
    )

    private class LruCache<K, V>(private val maxEntries: Int) : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        @Synchronized
        fun getCached(key: K): V? = super.get(key)

        @Synchronized
        fun putCached(key: K, value: V) {
            super.put(key, value)
        }

        @Synchronized
        fun clearCache() {
            super.clear()
        }

        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > maxEntries
    }

    private class IdentityCache<K : Any, V : Any>(requestedSize: Int) {
        private val size = requestedSize.coerceAtLeast(2).nextPowerOfTwo()
        private val mask = size - 1
        private val primaryKeys = arrayOfNulls<Any>(size)
        private val primaryValues = arrayOfNulls<Any>(size)
        private val secondaryKeys = arrayOfNulls<Any>(size)
        private val secondaryValues = arrayOfNulls<Any>(size)

        fun get(key: K): V? {
            val primaryIndex = primaryIndex(key)
            if (primaryKeys[primaryIndex] === key) {
                @Suppress("UNCHECKED_CAST")
                return primaryValues[primaryIndex] as V
            }

            val secondaryIndex = secondaryIndex(key)
            if (secondaryKeys[secondaryIndex] === key) {
                @Suppress("UNCHECKED_CAST")
                return secondaryValues[secondaryIndex] as V
            }

            return null
        }

        fun put(key: K, value: V) {
            val primaryIndex = primaryIndex(key)
            if (primaryKeys[primaryIndex] === key || primaryKeys[primaryIndex] == null) {
                primaryKeys[primaryIndex] = key
                primaryValues[primaryIndex] = value
                return
            }

            val secondaryIndex = secondaryIndex(key)
            secondaryKeys[secondaryIndex] = key
            secondaryValues[secondaryIndex] = value
        }

        fun clear() {
            primaryKeys.fill(null)
            primaryValues.fill(null)
            secondaryKeys.fill(null)
            secondaryValues.fill(null)
        }

        private fun primaryIndex(key: K): Int = System.identityHashCode(key) and mask

        private fun secondaryIndex(key: K): Int {
            val hash = System.identityHashCode(key)
            return (hash xor (hash ushr 16) xor 0x9E3779B9.toInt()) and mask
        }

        private fun Int.nextPowerOfTwo(): Int {
            var value = this - 1
            value = value or (value ushr 1)
            value = value or (value ushr 2)
            value = value or (value ushr 4)
            value = value or (value ushr 8)
            value = value or (value ushr 16)
            return value + 1
        }
    }

    private val cacheLock = Any()
    private val componentCache = ConcurrentHashMap<GradientCacheKey, Text>()
    // Name/text hooks run from HUD, scoreboard, nametag, tab, and TextRenderer paths.
    // These caches are bounded or fixed-size and explicitly cleared whenever
    // PlayerCustomizationRegistry.version changes.
    private val selfNameCache = LruCache<SelfNameCacheKey, Text>(stringCacheLimit)
    private val textTransformCache = LruCache<TextCacheKey, Text>(textCacheLimit)
    private val stringTransformCache = LruCache<StringCacheKey, String>(stringCacheLimit)
    private val matchCache = LruCache<MatchCacheKey, Boolean>(matchCacheLimit)
    private val gradientTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val nameplateTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val scoreboardTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val sidebarTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val chatHeaderTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val gradientOrderedTextIdentityCache = IdentityCache<OrderedText, OrderedText>(identityCacheSize)
    private val nameplateOrderedTextIdentityCache = IdentityCache<OrderedText, OrderedText>(identityCacheSize)
    private val scoreboardOrderedTextIdentityCache = IdentityCache<OrderedText, OrderedText>(identityCacheSize)

    @Volatile
    private var observedRegistryVersion = Long.MIN_VALUE

    fun clearCaches() {
        synchronized(cacheLock) {
            componentCache.clear()
            selfNameCache.clearCache()
            textTransformCache.clearCache()
            stringTransformCache.clearCache()
            matchCache.clearCache()
            gradientTextIdentityCache.clear()
            nameplateTextIdentityCache.clear()
            scoreboardTextIdentityCache.clear()
            sidebarTextIdentityCache.clear()
            chatHeaderTextIdentityCache.clear()
            gradientOrderedTextIdentityCache.clear()
            nameplateOrderedTextIdentityCache.clear()
            scoreboardOrderedTextIdentityCache.clear()
            observedRegistryVersion = PlayerCustomizationRegistry.version
        }
    }

    private fun currentRegistryVersion(): Long {
        val version = PlayerCustomizationRegistry.version
        if (observedRegistryVersion != version) {
            synchronized(cacheLock) {
                if (observedRegistryVersion != version) {
                    componentCache.clear()
                    selfNameCache.clearCache()
                    textTransformCache.clearCache()
                    stringTransformCache.clearCache()
                    matchCache.clearCache()
                    gradientTextIdentityCache.clear()
                    nameplateTextIdentityCache.clear()
                    scoreboardTextIdentityCache.clear()
                    sidebarTextIdentityCache.clear()
                    chatHeaderTextIdentityCache.clear()
                    gradientOrderedTextIdentityCache.clear()
                    nameplateOrderedTextIdentityCache.clear()
                    scoreboardOrderedTextIdentityCache.clear()
                    observedRegistryVersion = version
                }
            }
        }
        return version
    }

    fun isTargetProfile(profile: GameProfile?): Boolean = PlayerCustomizationRegistry.find(profile) != null

    fun isTargetName(name: String?): Boolean = PlayerCustomizationRegistry.findByName(name) != null

    fun hasGradientStyles(): Boolean = PlayerCustomizationRegistry.gradientNameCandidates.isNotEmpty()

    fun hasStyledProfile(profile: GameProfile?): Boolean =
        PlayerCustomizationRegistry.find(profile)?.hasNameCustomization() == true

    fun hasStyledName(name: String?): Boolean =
        PlayerCustomizationRegistry.findByName(name)?.hasNameCustomization() == true

    fun hasExplicitNameColors(name: String?): Boolean =
        PlayerCustomizationRegistry.findByName(name)?.nameColors != null

    fun containsTargetName(text: String?): Boolean =
        containsCandidate(text, PlayerCustomizationRegistry.allNameCandidates)

    fun containsStyledTargetName(text: String?): Boolean =
        containsCandidate(text, PlayerCustomizationRegistry.styledNameCandidates)

    fun containsStyledScoreboardTargetName(text: String?): Boolean =
        containsCandidate(text, PlayerCustomizationRegistry.scoreboardStyledNameCandidates)

    fun styledSelfName(profile: GameProfile?): Text {
        val version = currentRegistryVersion()
        val customization = PlayerCustomizationRegistry.find(profile)
            ?: return Text.literal(profile?.name ?: "")
        val matchedName = profile?.name ?: customization.username
        return styledSelfName(version, matchedName, customization)
    }

    fun styledSelfName(name: String?): Text {
        val version = currentRegistryVersion()
        val customization = PlayerCustomizationRegistry.findByName(name)
            ?: return Text.literal(name ?: "")
        val matchedName = name ?: customization.username
        return styledSelfName(version, matchedName, customization)
    }

    fun styleEntityName(current: Text?): Text? {
        if (current == null) {
            return null
        }

        val rawName = current.string
        if (PlayerCustomizationRegistry.findByName(rawName)?.nameColors == null) {
            return current
        }
        return styledSelfName(rawName)
    }

    fun applyNameplateDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.NAMEPLATE_TEXT) {
            rebuildVisitable(normalizeLegacyText(it), includeBadges = true)
        }

    fun applyNameplateDecorations(raw: String): Text = rebuildVisitable(parseLegacyFormattedText(raw), includeBadges = true)

    fun applyScoreboardDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.SCOREBOARD_TEXT) {
            rebuildDecorationsAcrossSegments(
                normalizeLegacyText(it),
                includeBadges = true,
                terminalBadgesOnly = true,
                allowTruncatedPrefix = true,
            ) ?: it
        }

    fun applyScoreboardDecorations(raw: String): Text {
        val parsed = parseLegacyFormattedText(raw)
        return rebuildDecorationsAcrossSegments(
            parsed,
            includeBadges = true,
            terminalBadgesOnly = true,
            allowTruncatedPrefix = true,
        ) ?: parsed
    }

    fun applySidebarDecorations(message: Text, includeBadges: Boolean): Text =
        applyCachedTextTransform(
            message,
            if (includeBadges) TransformKind.SCOREBOARD_TEXT else TransformKind.SIDEBAR_TEXT,
        ) {
            rebuildDecorationsAcrossSegments(
                normalizeLegacyText(it),
                includeBadges = includeBadges,
                allowTruncatedPrefix = true,
            ) ?: it
        }

    fun applyGradientToName(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.GRADIENT_TEXT) {
            rebuildGradientAcrossSegments(normalizeLegacyText(it)) ?: it
        }

    fun applyGradientToChatHeader(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.CHAT_HEADER_TEXT) {
            rebuildVisitable(normalizeLegacyText(it), chatHeaderOnly = true)
        }

    fun applyGradientToVisitable(message: StringVisitable): StringVisitable = rebuildVisitable(message)

    fun applyGradientToOrderedText(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.GRADIENT_TEXT) {
            rebuildGradientAcrossSegments(it)?.asOrderedText() ?: it
        }

    fun applyNameplateDecorations(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.NAMEPLATE_TEXT) {
            rebuildOrderedText(it, includeBadges = true)?.asOrderedText() ?: it
        }

    fun applyScoreboardDecorations(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.SCOREBOARD_TEXT) {
            rebuildDecorationsAcrossSegments(
                it,
                includeBadges = true,
                terminalBadgesOnly = true,
                allowTruncatedPrefix = true,
            )?.asOrderedText() ?: it
        }

    fun applyGradientToString(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return raw
        }

        val version = currentRegistryVersion()
        if (!containsForKind(raw, TransformKind.GRADIENT_STRING, version)) {
            return raw
        }

        val cacheKey = StringCacheKey(version, TransformKind.GRADIENT_STRING, raw)
        stringTransformCache.getCached(cacheKey)?.let { return it }

        var output: String = raw
        PlayerCustomizationRegistry.entries.forEach { customization ->
            if (!customization.hasNameCustomization() || customization.nameColors == null) {
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

        stringTransformCache.putCached(cacheKey, output)
        return output
    }

    fun applyDecorationsToString(raw: String?): String? {
        return applyDecorationsToString(raw, terminalBadgesOnly = false)
    }

    fun applyScoreboardDecorationsToString(raw: String?): String? {
        return applyDecorationsToString(raw, terminalBadgesOnly = true)
    }

    private fun applyDecorationsToString(raw: String?, terminalBadgesOnly: Boolean): String? {
        if (raw.isNullOrEmpty()) {
            return raw
        }

        val kind = if (terminalBadgesOnly) TransformKind.SCOREBOARD_STRING else TransformKind.DECORATED_STRING
        val version = currentRegistryVersion()
        if (!containsForKind(raw, kind, version)) {
            return raw
        }

        val cacheKey = StringCacheKey(version, kind, raw)
        stringTransformCache.getCached(cacheKey)?.let { return it }

        var output: String = raw
        PlayerCustomizationRegistry.entries.forEach { customization ->
            if (!customization.hasNameCustomization() || findNameMatch(output, customization) == null) {
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
                    if (terminalBadgesOnly && hasVisibleContentAfter(output, matchIndex + match.matchedName.length)) {
                        return@let
                    }
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

        stringTransformCache.putCached(cacheKey, output)
        return output
    }

    private fun styledSelfName(
        version: Long,
        matchedName: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): Text {
        val cacheKey = SelfNameCacheKey(version, matchedName.lowercase(Locale.ROOT))
        selfNameCache.getCached(cacheKey)?.let { return it }

        val styled = appendBadge(cachedGradient(matchedName, customization), customization)
        selfNameCache.putCached(cacheKey, styled)
        return styled
    }

    private fun applyCachedTextTransform(
        message: Text,
        kind: TransformKind,
        transform: (Text) -> Text,
    ): Text {
        val version = currentRegistryVersion()
        textIdentityCache(kind).get(message)?.let { return it }

        val plain = message.string
        if (!containsForKind(plain, kind, version)) {
            cacheTextIdentity(kind, message, message)
            return message
        }

        val runs = collectRuns(message)
        val cacheKey = TextCacheKey(version, kind, runsToPlain(runs), styleHash(runs))
        textTransformCache.getCached(cacheKey)?.let { cached ->
            cacheTextIdentity(kind, message, cached)
            return cached
        }

        val transformed = transform(message)
        if (transformed !== message) {
            textTransformCache.putCached(cacheKey, transformed)
            cacheTextIdentity(kind, transformed, transformed)
        }
        cacheTextIdentity(kind, message, transformed)
        return transformed
    }

    private fun applyCachedOrderedTextTransform(
        text: OrderedText,
        kind: TransformKind,
        transform: (OrderedText) -> OrderedText,
    ): OrderedText {
        val version = currentRegistryVersion()
        orderedTextIdentityCache(kind).get(text)?.let { return it }

        val runs = collectRuns(text)
        val plain = runsToPlain(runs)
        if (!containsForKind(plain, kind, version)) {
            cacheOrderedTextIdentity(kind, text, text)
            return text
        }

        val cacheKey = TextCacheKey(version, kind, plain, styleHash(runs))
        textTransformCache.getCached(cacheKey)?.let { cached ->
            val ordered = cached.asOrderedText()
            cacheOrderedTextIdentity(kind, text, ordered)
            cacheOrderedTextIdentity(kind, ordered, ordered)
            return ordered
        }

        val transformed = transform(text)
        if (transformed !== text) {
            val transformedRuns = collectRuns(transformed)
            textTransformCache.putCached(cacheKey, runsToText(transformedRuns))
            cacheOrderedTextIdentity(kind, transformed, transformed)
        }
        cacheOrderedTextIdentity(kind, text, transformed)
        return transformed
    }

    private fun textIdentityCache(kind: TransformKind): IdentityCache<Text, Text> =
        when (kind) {
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING -> gradientTextIdentityCache
            TransformKind.NAMEPLATE_TEXT, TransformKind.DECORATED_STRING -> nameplateTextIdentityCache
            TransformKind.SCOREBOARD_TEXT, TransformKind.SCOREBOARD_STRING -> scoreboardTextIdentityCache
            TransformKind.SIDEBAR_TEXT -> sidebarTextIdentityCache
            TransformKind.CHAT_HEADER_TEXT -> chatHeaderTextIdentityCache
        }

    private fun orderedTextIdentityCache(kind: TransformKind): IdentityCache<OrderedText, OrderedText> =
        when (kind) {
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING, TransformKind.CHAT_HEADER_TEXT -> gradientOrderedTextIdentityCache
            TransformKind.NAMEPLATE_TEXT, TransformKind.DECORATED_STRING, TransformKind.SIDEBAR_TEXT -> nameplateOrderedTextIdentityCache
            TransformKind.SCOREBOARD_TEXT, TransformKind.SCOREBOARD_STRING -> scoreboardOrderedTextIdentityCache
        }

    private fun cacheTextIdentity(kind: TransformKind, source: Text, result: Text) {
        textIdentityCache(kind).put(source, result)
    }

    private fun cacheOrderedTextIdentity(kind: TransformKind, source: OrderedText, result: OrderedText) {
        orderedTextIdentityCache(kind).put(source, result)
    }

    private fun containsForKind(text: String, kind: TransformKind, version: Long): Boolean {
        if (text.isEmpty()) {
            return false
        }

        val cacheKey = MatchCacheKey(version, kind, text)
        matchCache.getCached(cacheKey)?.let { return it }

        val result = when (kind) {
            TransformKind.GRADIENT_TEXT,
            TransformKind.GRADIENT_STRING,
            TransformKind.CHAT_HEADER_TEXT -> containsCandidate(text, PlayerCustomizationRegistry.gradientNameCandidates)

            TransformKind.NAMEPLATE_TEXT,
            TransformKind.DECORATED_STRING -> containsCandidate(text, PlayerCustomizationRegistry.styledNameCandidates)

            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_STRING -> containsCandidate(text, PlayerCustomizationRegistry.scoreboardStyledNameCandidates)

            TransformKind.SIDEBAR_TEXT -> containsCandidate(text, PlayerCustomizationRegistry.scoreboardGradientNameCandidates)
        }

        matchCache.putCached(cacheKey, result)
        return result
    }

    private fun containsCandidate(text: String?, candidates: List<PlayerCustomizationRegistry.NameCandidate>): Boolean =
        !text.isNullOrEmpty() && candidates.isNotEmpty() && findFirstNameMatch(text, candidates) != null

    private fun findFirstNameMatch(
        text: String,
        candidates: List<PlayerCustomizationRegistry.NameCandidate>,
        startIndex: Int = 0,
    ): MatchedCustomization? {
        var bestIndex = Int.MAX_VALUE
        var bestName: String? = null
        var bestCustomization: PlayerCustomizationRegistry.PlayerCustomization? = null

        candidates.forEach { candidate ->
            var searchIndex = startIndex
            while (searchIndex < text.length) {
                val index = text.indexOf(candidate.text, searchIndex, ignoreCase = true)
                if (index == -1) {
                    break
                }

                if (index > bestIndex) {
                    break
                }

                if (!candidate.requiresBoundary || isNameBoundary(text, index, index + candidate.text.length)) {
                    if (index < bestIndex || (index == bestIndex && candidate.text.length > (bestName?.length ?: -1))) {
                        bestIndex = index
                        bestName = candidate.text
                        bestCustomization = candidate.customization
                    }
                    break
                }

                searchIndex = index + 1
            }
        }

        val matchedName = bestName ?: return null
        val customization = bestCustomization ?: return null
        return MatchedCustomization(NameMatch(bestIndex, matchedName), customization)
    }

    private fun runsToPlain(runs: List<StyledRun>): String = buildString {
        runs.forEach { append(it.text) }
    }

    private fun styleHash(runs: List<StyledRun>): Int {
        var result = 1
        runs.forEach { run ->
            result = 31 * result + run.start
            result = 31 * result + run.end
            result = 31 * result + run.style.hashCode()
        }
        return result
    }

    private fun runsToText(runs: List<StyledRun>): Text {
        val output = Text.empty()
        runs.forEach { run ->
            output.append(Text.literal(run.text).setStyle(run.style))
        }
        return output
    }

    private fun rebuildVisitable(
        message: StringVisitable,
        includeBadges: Boolean = false,
        chatHeaderOnly: Boolean = false,
        terminalBadgesOnly: Boolean = false,
    ): Text {
        val rebuilt = Text.empty()
        var changed = false
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = plainText(message)
        val headerBoundary = if (chatHeaderOnly) chatHeaderBoundary(plain) else Int.MAX_VALUE
        val candidates = if (includeBadges) {
            PlayerCustomizationRegistry.styledNameCandidates
        } else {
            PlayerCustomizationRegistry.gradientNameCandidates
        }
        var visibleIndex = 0

        message.visit({ style, segment ->
            val segmentStart = visibleIndex
            val segmentEnd = segmentStart + segment.length
            visibleIndex = segmentEnd

            val decoratedLength = (headerBoundary - segmentStart).coerceIn(0, segment.length)
            val decoratedSegment = segment.substring(0, decoratedLength)
            val untouchedSegment = segment.substring(decoratedLength)

            if (decoratedSegment.isNotEmpty() && containsCandidate(decoratedSegment, candidates)) {
                changed = true
                appendStyledSegment(
                    rebuilt,
                    decoratedSegment,
                    style,
                    recentSegments,
                    includeBadges,
                    plain,
                    segmentStart,
                    terminalBadgesOnly,
                )
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

    private fun rebuildOrderedText(
        message: OrderedText,
        includeBadges: Boolean = false,
        terminalBadgesOnly: Boolean = false,
    ): Text? {
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

        val candidates = if (includeBadges) {
            PlayerCustomizationRegistry.styledNameCandidates
        } else {
            PlayerCustomizationRegistry.gradientNameCandidates
        }
        if (segments.none { containsCandidate(it.second.toString(), candidates) }) {
            return null
        }

        val rebuilt = Text.empty()
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = segments.joinToString(separator = "") { it.second.toString() }
        var visibleIndex = 0
        segments.forEach { (style, builder) ->
            val segment = builder.toString()
            if (containsCandidate(segment, candidates)) {
                changed = true
                appendStyledSegment(
                    rebuilt,
                    segment,
                    style,
                    recentSegments,
                    includeBadges,
                    plain,
                    visibleIndex,
                    terminalBadgesOnly,
                )
            } else {
                rebuilt.append(Text.literal(segment).setStyle(style))
            }
            rememberSegment(recentSegments, segment, style)
            visibleIndex += segment.length
        }

        return if (changed) rebuilt else null
    }

    private fun rebuildGradientAcrossSegments(message: Text): Text? {
        val runs = collectRuns(message)
        return rebuildGradientAcrossRuns(runs)
    }

    private fun rebuildGradientAcrossSegments(message: OrderedText): Text? {
        val runs = collectRuns(message)
        return rebuildGradientAcrossRuns(runs)
    }

    private fun rebuildGradientAcrossRuns(runs: List<StyledRun>): Text? {
        val plain = runsToPlain(runs)
        val candidates = PlayerCustomizationRegistry.gradientNameCandidates
        if (!containsCandidate(plain, candidates)) {
            return null
        }

        val rebuilt = Text.empty()
        var changed = false
        var index = 0

        while (index < plain.length) {
            val match = findFirstNameMatch(plain, candidates, index)

            if (match == null) {
                appendOriginalRange(rebuilt, runs, index, plain.length)
                break
            }

            val nameMatch = match.nameMatch
            val customization = match.customization
            val matchIndex = nameMatch.index
            val matchedName = nameMatch.matchedName

            if (matchIndex > index) {
                appendOriginalRange(rebuilt, runs, index, matchIndex)
            }

            val resolvedMatchedName = plain.substring(matchIndex, matchIndex + matchedName.length)
            rebuilt.append(cachedGradient(resolvedMatchedName, customization, styleAt(runs, matchIndex)))
            changed = true
            index = matchIndex + matchedName.length
        }

        return if (changed) rebuilt else null
    }

    private fun rebuildDecorationsAcrossSegments(
        message: Text,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean = false,
        allowTruncatedPrefix: Boolean = false,
    ): Text? {
        val runs = collectRuns(message)
        return rebuildDecorationsAcrossRuns(runs, includeBadges, terminalBadgesOnly, allowTruncatedPrefix)
    }

    private fun rebuildDecorationsAcrossSegments(
        message: OrderedText,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean = false,
        allowTruncatedPrefix: Boolean = false,
    ): Text? {
        val runs = collectRuns(message)
        return rebuildDecorationsAcrossRuns(runs, includeBadges, terminalBadgesOnly, allowTruncatedPrefix)
    }

    private fun rebuildDecorationsAcrossRuns(
        runs: List<StyledRun>,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean,
        allowTruncatedPrefix: Boolean,
    ): Text? {
        val plain = runsToPlain(runs)
        val candidates = when {
            allowTruncatedPrefix && includeBadges -> PlayerCustomizationRegistry.scoreboardStyledNameCandidates
            allowTruncatedPrefix -> PlayerCustomizationRegistry.scoreboardGradientNameCandidates
            includeBadges -> PlayerCustomizationRegistry.styledNameCandidates
            else -> PlayerCustomizationRegistry.gradientNameCandidates
        }
        if (!containsCandidate(plain, candidates)) {
            return null
        }

        val rebuilt = Text.empty()
        var changed = false
        var index = 0

        while (index < plain.length) {
            val match = findFirstNameMatch(plain, candidates, index)

            if (match == null) {
                appendOriginalRange(rebuilt, runs, index, plain.length)
                break
            }

            val nameMatch = match.nameMatch
            val customization = match.customization
            val matchIndex = nameMatch.index
            val matchedName = nameMatch.matchedName

            if (matchIndex > index) {
                appendOriginalRange(rebuilt, runs, index, matchIndex)
            }

            val matchEnd = matchIndex + matchedName.length
            val resolvedMatchedName = plain.substring(matchIndex, matchEnd)
            val baseStyle = styleAt(runs, matchIndex)
            val styledName = when {
                customization.nameColors != null -> {
                    changed = true
                    cachedGradient(resolvedMatchedName, customization, baseStyle)
                }
                else -> buildOriginalRangeText(runs, matchIndex, matchEnd)
            }

            val hasBadgeAlready = includeBadges && customization.nameBadge != null &&
                hasPlainBadgeImmediatelyAfter(plain, matchEnd, customization.nameBadge.text)
            val hasTrailingContent = terminalBadgesOnly && hasPlainVisibleContentAfter(plain, matchEnd)
            if (includeBadges && customization.nameBadge != null && !hasBadgeAlready && !hasTrailingContent) {
                rebuilt.append(appendBadge(styledName, customization, baseStyle))
                changed = true
            } else {
                rebuilt.append(styledName)
            }

            index = matchEnd
        }

        return if (changed) rebuilt else null
    }

    private fun collectRuns(message: Text): List<StyledRun> {
        val runs = mutableListOf<StyledRun>()
        var index = 0
        message.visit({ style, segment ->
            if (segment.isNotEmpty()) {
                runs.add(
                    StyledRun(
                        start = index,
                        end = index + segment.length,
                        text = segment,
                        style = style,
                    ),
                )
                index += segment.length
            }
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return runs
    }

    private fun collectRuns(message: OrderedText): List<StyledRun> {
        val runs = mutableListOf<StyledRun>()
        var currentStyle: Style? = null
        var currentBuilder = StringBuilder()
        var start = 0
        var index = 0

        fun flush() {
            val style = currentStyle ?: return
            if (currentBuilder.isNotEmpty()) {
                val text = currentBuilder.toString()
                runs.add(
                    StyledRun(
                        start = start,
                        end = start + text.length,
                        text = text,
                        style = style,
                    ),
                )
                start += text.length
                currentBuilder = StringBuilder()
            }
        }

        message.accept { _, style, codePoint ->
            if (currentStyle != null && currentStyle != style) {
                flush()
            }
            if (currentStyle == null) {
                start = index
            }
            currentStyle = style
            currentBuilder.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
            true
        }
        flush()

        return runs
    }

    private fun appendOriginalRange(
        target: MutableText,
        runs: List<StyledRun>,
        start: Int,
        end: Int,
    ) {
        if (start >= end) {
            return
        }

        runs.forEach { run ->
            if (run.end <= start || run.start >= end) {
                return@forEach
            }

            val localStart = (start - run.start).coerceAtLeast(0)
            val localEnd = (end - run.start).coerceAtMost(run.text.length)
            if (localStart < localEnd) {
                target.append(Text.literal(run.text.substring(localStart, localEnd)).setStyle(run.style))
            }
        }
    }

    private fun buildOriginalRangeText(
        runs: List<StyledRun>,
        start: Int,
        end: Int,
    ): Text {
        val output = Text.empty()
        appendOriginalRange(output, runs, start, end)
        return output
    }

    private fun styleAt(runs: List<StyledRun>, index: Int): Style =
        runs.firstOrNull { index in it.start until it.end }?.style ?: Style.EMPTY

    private fun appendStyledSegment(
        target: MutableText,
        segment: String,
        style: Style,
        recentSegments: ArrayDeque<StyledSegment>,
        includeBadges: Boolean = false,
        plainText: String = segment,
        segmentStart: Int = 0,
        terminalBadgesOnly: Boolean = false,
    ) {
        var remaining = segment
        var localOffset = 0
        val candidates = if (includeBadges) {
            PlayerCustomizationRegistry.styledNameCandidates
        } else {
            PlayerCustomizationRegistry.gradientNameCandidates
        }

        while (remaining.isNotEmpty()) {
            val match = findFirstNameMatch(remaining, candidates)

            if (match == null) {
                target.append(Text.literal(remaining).setStyle(style))
                return
            }

            val nameMatch = match.nameMatch
            val customization = match.customization
            val matchIndex = nameMatch.index
            val matchedName = nameMatch.matchedName

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
            val hasTrailingContent = terminalBadgesOnly &&
                hasPlainVisibleContentAfter(plainText, segmentStart + localOffset + matchIndex + matchedName.length)
            target.append(
                if (includeBadges && !hasBadgeAlready && !hasTrailingContent) {
                    appendBadge(styledName, customization, style)
                } else {
                    styledName
                },
            )
            remaining = remaining.substring(matchIndex + matchedName.length)
            localOffset += matchIndex + matchedName.length
        }
    }

    private fun findNameMatch(
        text: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        startIndex: Int = 0,
        allowTruncatedPrefix: Boolean = false,
    ): NameMatch? {
        var bestIndex = Int.MAX_VALUE
        var bestName: String? = null
        customization.matchNames().forEach { candidate ->
            val index = text.indexOf(candidate, startIndex, ignoreCase = true)
            if (index != -1 && (index < bestIndex || (index == bestIndex && (bestName == null || candidate.length > bestName.length)))) {
                bestIndex = index
                bestName = candidate
            }

            if (!allowTruncatedPrefix || candidate.length < 8) {
                return@forEach
            }

            val minimumLength = maxOf(6, candidate.length - 4, (candidate.length * 0.7f).toInt())
            for (length in candidate.length - 1 downTo minimumLength) {
                val prefix = candidate.substring(0, length)
                var searchIndex = startIndex
                while (searchIndex < text.length) {
                    val prefixIndex = text.indexOf(prefix, searchIndex, ignoreCase = true)
                    if (prefixIndex == -1) {
                        break
                    }

                    if (isNameBoundary(text, prefixIndex, prefixIndex + prefix.length)) {
                        if (prefixIndex < bestIndex || (prefixIndex == bestIndex && (bestName == null || prefix.length > bestName.length))) {
                            bestIndex = prefixIndex
                            bestName = prefix
                        }
                    }

                    searchIndex = prefixIndex + 1
                }
            }
        }

        return bestName?.let { NameMatch(bestIndex, it) }
    }

    private fun isNameBoundary(text: String, start: Int, endExclusive: Int): Boolean {
        val before = text.getOrNull(start - 1)
        val after = text.getOrNull(endExclusive)
        return isNameBoundaryCharacter(before) && isNameBoundaryCharacter(after)
    }

    private fun isNameBoundaryCharacter(character: Char?): Boolean {
        if (character == null) {
            return true
        }

        return !character.isLetterOrDigit() && character != '_'
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
                val code = raw[index + 1].lowercaseChar()
                if (code == 'x') {
                    val hex = StringBuilder(6)
                    var cursor = index + 2
                    var valid = true
                    repeat(6) {
                        if (cursor + 1 >= raw.length || raw[cursor] != legacyFormat) {
                            valid = false
                            return@repeat
                        }

                        val digit = raw[cursor + 1]
                        if (!digit.isDigit() && digit.lowercaseChar() !in 'a'..'f') {
                            valid = false
                            return@repeat
                        }

                        hex.append(digit)
                        cursor += 2
                    }

                    if (valid) {
                        style = style.withColor(hex.toString().toInt(16))
                        index = cursor
                        continue
                    }
                }

                style = applyLegacyCode(style, code)
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
        customization.hasNameCustomization()

    private fun cachedGradient(
        content: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        baseStyle: Style = Style.EMPTY,
    ): Text {
        val colors = customization.nameColors ?: return Text.literal(content).setStyle(baseStyle)
        val key = GradientCacheKey(content.lowercase(Locale.ROOT), colors.left, colors.right)
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

    private fun hasVisibleContentAfter(text: String, startIndex: Int): Boolean {
        var index = startIndex
        while (index < text.length) {
            if (text[index] == legacyFormat && index + 1 < text.length) {
                index += 2
                continue
            }
            if (!text[index].isWhitespace()) {
                return true
            }
            index++
        }
        return false
    }

    private fun hasPlainBadgeImmediatelyAfter(text: String, startIndex: Int, badgeText: String): Boolean {
        var index = startIndex
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
        return index + badgeText.length <= text.length && text.regionMatches(index, badgeText, 0, badgeText.length)
    }

    private fun hasPlainVisibleContentAfter(text: String, startIndex: Int): Boolean {
        var index = startIndex
        while (index < text.length) {
            if (!text[index].isWhitespace()) {
                return true
            }
            index++
        }
        return false
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
    private val rankPrefixSuffixRegex = Regex("\\[[^\\]]+]\\s*$")
}
