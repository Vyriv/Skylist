package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import net.minecraft.client.MinecraftClient
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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.floor

object NameStyler {
    private const val legacyFormat = '\u00A7'
    private const val animatedGradientSteps = 64
    private const val animatedGradientSpeed = 1.0f
    private const val textCacheLimit = 512
    private const val stringCacheLimit = 1024
    private const val matchCacheLimit = 2048
    private const val identityCacheSize = 2048
    private const val debugLogIntervalMillis = 750L
    private const val largeLobbyAnimationThreshold = 40
    private const val throttledAnimationFps = 15.0

    private enum class TransformKind {
        GRADIENT_TEXT,
        NAMEPLATE_TEXT,
        NAMEPLATE_DISPLAY_TEXT,
        SCOREBOARD_TEXT,
        SCOREBOARD_DISPLAY_TEXT,
        SIDEBAR_TEXT,
        CHAT_HEADER_TEXT,
        GRADIENT_STRING,
        DECORATED_STRING,
        DECORATED_DISPLAY_STRING,
        SCOREBOARD_STRING,
        SCOREBOARD_DISPLAY_STRING,
    }

    private data class ColorizedCacheKey(
        val content: String,
        val kind: String,
        val colors: List<Int>,
    )

    private data class AnimatedGradientCacheKey(
        val leftColor: Int,
        val rightColor: Int,
        val stepsCount: Int,
        val speedBits: Int,
        val spacingBits: Int,
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

    private data class ResolvedOrderedMatch(
        val start: Int,
        val end: Int,
        val content: String,
        val baseStyle: Style,
        val customization: PlayerCustomizationRegistry.PlayerCustomization,
        val animatedStyle: AnimatedGradientStyle?,
        val isAnimatedGradient: Boolean,
        val hasBadge: Boolean,
        val hasDecorations: Boolean,
        val hasExplicitNameColors: Boolean,
        val hasBadgeAlready: Boolean,
        val hasTrailingContent: Boolean,
    )

    private data class OrderedTextTransformPlan(
        val matches: List<ResolvedOrderedMatch>,
        val hasAnimatedGradient: Boolean,
    )

    private data class OrderedTextSourceData(
        val plain: String,
        val characterCount: Int,
        val runs: List<StyledRun>,
        val styleHash: Int,
        val gradientPlan: OrderedTextTransformPlan?,
        val nameplatePlan: OrderedTextTransformPlan?,
        val scoreboardPlan: OrderedTextTransformPlan?,
        val chatHeaderPlan: OrderedTextTransformPlan?,
    ) {
        fun planFor(kind: TransformKind): OrderedTextTransformPlan? =
            when (kind) {
                TransformKind.GRADIENT_TEXT -> gradientPlan
                TransformKind.NAMEPLATE_TEXT -> nameplatePlan
                TransformKind.SCOREBOARD_TEXT -> scoreboardPlan
                TransformKind.CHAT_HEADER_TEXT -> chatHeaderPlan
                else -> null
            }
    }

    private data class OrderedTextSourceLookup(
        val data: OrderedTextSourceData,
        val cacheUsed: Boolean,
    )

    private data class OrderedTextPlanCacheKey(
        val version: Long,
        val kind: TransformKind,
        val plain: String,
        val styleHash: Int,
    )

    private data class OrderedTextPlanCacheValue(
        val plan: OrderedTextTransformPlan?,
    )

    private data class OrderedTextResultCacheKey(
        val version: Long,
        val kind: TransformKind,
        val plain: String,
        val styleHash: Int,
    )

    private data class OrderedTextAnimatedFrameCacheKey(
        val base: OrderedTextResultCacheKey,
        val frameIndex: Long,
    )

    data class DebugTransformMetadata(
        val kind: String,
        val renderPath: String,
        val matchedPlayer: String?,
        val styleMode: String,
        val animated: Boolean,
        val worldTime: Long,
        val animationTime: Double,
        val animationOffset: Float,
        val finalOrderedTextCacheUsed: Boolean,
        val sourceDataCacheUsed: Boolean,
        val resultIdentityHash: Int,
        val resultHash: Int,
        val resultText: String,
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

    private class DebugCounters {
        val orderedTextCalls = LongAdder()
        val planCacheHits = LongAdder()
        val planCacheMisses = LongAdder()
        val finalStaticCacheHits = LongAdder()
        val finalStaticCacheMisses = LongAdder()
        val animatedFrameCacheHits = LongAdder()
        val animatedFrameCacheMisses = LongAdder()
        val sourceCacheHits = LongAdder()
        val sourceCacheMisses = LongAdder()
        val asOrderedTextCalls = LongAdder()
        val buildPlanCalls = LongAdder()
        val hudCacheHits = LongAdder()
        val hudCacheMisses = LongAdder()
    }

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
    private val componentCache = ConcurrentHashMap<ColorizedCacheKey, Text>()
    private val animatedGradientCache = ConcurrentHashMap<AnimatedGradientCacheKey, AnimatedGradientStyle>()
    // Name/text hooks run from HUD, scoreboard, nametag, tab, and TextRenderer paths.
    // These caches are bounded or fixed-size and explicitly cleared whenever
    // PlayerCustomizationRegistry.version changes.
    private val selfNameCache = LruCache<SelfNameCacheKey, Text>(stringCacheLimit)
    private val textTransformCache = LruCache<TextCacheKey, Text>(textCacheLimit)
    private val stringTransformCache = LruCache<StringCacheKey, String>(stringCacheLimit)
    private val matchCache = LruCache<MatchCacheKey, Boolean>(matchCacheLimit)
    private val orderedTextPlanCache = LruCache<OrderedTextPlanCacheKey, OrderedTextPlanCacheValue>(matchCacheLimit)
    private val orderedTextStaticResultCache = LruCache<OrderedTextResultCacheKey, OrderedText>(textCacheLimit)
    private val orderedTextAnimatedFrameCache = LruCache<OrderedTextAnimatedFrameCacheKey, OrderedText>(matchCacheLimit)
    private val loggedAnimatedPaths = ConcurrentHashMap.newKeySet<String>()
    private val gradientTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val nameplateTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val scoreboardTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val sidebarTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val chatHeaderTextIdentityCache = IdentityCache<Text, Text>(identityCacheSize)
    private val gradientOrderedTextIdentityCache = IdentityCache<OrderedText, OrderedText>(identityCacheSize)
    private val nameplateOrderedTextIdentityCache = IdentityCache<OrderedText, OrderedText>(identityCacheSize)
    private val scoreboardOrderedTextIdentityCache = IdentityCache<OrderedText, OrderedText>(identityCacheSize)
    private val orderedTextSourceCache = IdentityCache<OrderedText, OrderedTextSourceData>(identityCacheSize)
    private val debugLogTimes = ConcurrentHashMap<String, Long>()
    private val debugCounters = DebugCounters()
    private val debugCounterLastLog = AtomicLong(0L)

    @Volatile
    private var observedRegistryVersion = Long.MIN_VALUE
    private val activeRenderPath = ThreadLocal<TransformKind?>()
    private val lastDebugTransform = ThreadLocal<DebugTransformMetadata?>()

    @Volatile
    private var animatedNameDebugEnabled = false

    @Volatile
    private var forceAnimatedNameUncached = false

    fun clearCaches() {
        synchronized(cacheLock) {
            componentCache.clear()
            animatedGradientCache.clear()
            selfNameCache.clearCache()
            textTransformCache.clearCache()
            stringTransformCache.clearCache()
            matchCache.clearCache()
            orderedTextPlanCache.clearCache()
            orderedTextStaticResultCache.clearCache()
            orderedTextAnimatedFrameCache.clearCache()
            loggedAnimatedPaths.clear()
            gradientTextIdentityCache.clear()
            nameplateTextIdentityCache.clear()
            scoreboardTextIdentityCache.clear()
            sidebarTextIdentityCache.clear()
            chatHeaderTextIdentityCache.clear()
            gradientOrderedTextIdentityCache.clear()
            nameplateOrderedTextIdentityCache.clear()
            scoreboardOrderedTextIdentityCache.clear()
            orderedTextSourceCache.clear()
            debugLogTimes.clear()
            observedRegistryVersion = PlayerCustomizationRegistry.version
        }
    }

    private fun currentRegistryVersion(): Long {
        val version = PlayerCustomizationRegistry.version
        if (observedRegistryVersion != version) {
            synchronized(cacheLock) {
                if (observedRegistryVersion != version) {
                    componentCache.clear()
                    animatedGradientCache.clear()
                    selfNameCache.clearCache()
                    textTransformCache.clearCache()
                    stringTransformCache.clearCache()
                    matchCache.clearCache()
                    orderedTextPlanCache.clearCache()
                    orderedTextStaticResultCache.clearCache()
                    orderedTextAnimatedFrameCache.clearCache()
                    loggedAnimatedPaths.clear()
                    gradientTextIdentityCache.clear()
                    nameplateTextIdentityCache.clear()
                    scoreboardTextIdentityCache.clear()
                    sidebarTextIdentityCache.clear()
                    chatHeaderTextIdentityCache.clear()
                    gradientOrderedTextIdentityCache.clear()
                    nameplateOrderedTextIdentityCache.clear()
                    scoreboardOrderedTextIdentityCache.clear()
                    orderedTextSourceCache.clear()
                    debugLogTimes.clear()
                    observedRegistryVersion = version
                }
            }
        }
        return version
    }

    fun isTargetProfile(profile: GameProfile?): Boolean = PlayerCustomizationRegistry.find(profile) != null

    fun isTargetName(name: String?): Boolean = PlayerCustomizationRegistry.findByName(name) != null

    fun hasGradientStyles(): Boolean = PlayerCustomizationRegistry.gradientNameCandidates.isNotEmpty()

    fun hasChatHeaderStyles(): Boolean = PlayerCustomizationRegistry.chatHeaderNameCandidates.isNotEmpty()

    fun hasStyledProfile(profile: GameProfile?): Boolean =
        PlayerCustomizationRegistry.find(profile)?.hasNameCustomization() == true

    fun hasDisplayProfile(profile: GameProfile?): Boolean =
        PlayerCustomizationRegistry.find(profile)?.hasChatDisplayOverride() == true

    fun hasAnimatedStyledProfile(profile: GameProfile?): Boolean =
        PlayerCustomizationRegistry.find(profile)?.hasAnimatedGradient() == true

    fun hasStyledName(name: String?): Boolean =
        PlayerCustomizationRegistry.findByName(name)?.hasNameCustomization() == true

    fun containsAnimatedStyledTargetName(text: String?): Boolean =
        !text.isNullOrEmpty() && hasAnimatedGradientMatch(text, PlayerCustomizationRegistry.styledNameCandidates)

    fun setAnimatedNameDebugEnabled(enabled: Boolean): Boolean {
        animatedNameDebugEnabled = enabled
        if (!enabled) {
            lastDebugTransform.remove()
            debugLogTimes.clear()
        }
        return enabled
    }

    fun isAnimatedNameDebugEnabled(): Boolean = animatedNameDebugEnabled

    fun setForceAnimatedNameUncached(enabled: Boolean): Boolean {
        forceAnimatedNameUncached = enabled
        return enabled
    }

    fun isForceAnimatedNameUncached(): Boolean = forceAnimatedNameUncached

    fun hasAnimatedGradientInOrderedText(text: OrderedText): Boolean {
        val version = currentRegistryVersion()
        return orderedTextSource(text, version).data.gradientPlan?.hasAnimatedGradient == true
    }

    fun currentOrderedTextAnimationFrameIndex(): Long =
        currentAnimationFrameIndex(currentAnimationTime()) ?: Long.MIN_VALUE

    fun recordHudOrderedTextCacheHit() {
        incrementDebugCounter(debugCounters.hudCacheHits)
    }

    fun recordHudOrderedTextCacheMiss() {
        incrementDebugCounter(debugCounters.hudCacheMisses)
    }

    fun hasExplicitNameColors(name: String?): Boolean =
        PlayerCustomizationRegistry.findByName(name)?.hasExplicitNameColors() == true

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
        if (PlayerCustomizationRegistry.findByName(rawName)?.hasNameCustomization() != true) {
            return current
        }
        return styledSelfName(rawName)
    }

    fun applyNameplateDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.NAMEPLATE_TEXT) {
            rebuildVisitable(normalizeLegacyText(it), includeBadges = true)
        }

    fun applyNameplateDecorations(raw: String): Text = rebuildVisitable(parseLegacyFormattedText(raw), includeBadges = true)

    fun applyNameplateDisplayDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.NAMEPLATE_DISPLAY_TEXT) {
            rebuildDecorationsAcrossSegments(
                normalizeLegacyText(it),
                includeBadges = true,
                replaceMatchedName = true,
            ) ?: it
        }

    fun applyNameplateDisplayDecorations(raw: String): Text {
        val parsed = parseLegacyFormattedText(raw)
        return rebuildDecorationsAcrossSegments(
            parsed,
            includeBadges = true,
            replaceMatchedName = true,
        ) ?: parsed
    }

    fun applyNameplateDisplayDecorationsToString(raw: String?): String? =
        applyDisplayDecorationsToString(raw, terminalBadgesOnly = false)

    fun applyScoreboardDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.SCOREBOARD_TEXT) {
            rebuildDecorationsAcrossSegments(
                normalizeLegacyText(it),
                includeBadges = true,
                terminalBadgesOnly = true,
                allowTruncatedPrefix = true,
            ) ?: it
        }

    fun applyScoreboardDisplayDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.SCOREBOARD_DISPLAY_TEXT) {
            rebuildDecorationsAcrossSegments(
                normalizeLegacyText(it),
                includeBadges = true,
                terminalBadgesOnly = true,
                allowTruncatedPrefix = true,
                replaceMatchedName = true,
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

    fun applyScoreboardDisplayDecorations(raw: String): Text {
        val parsed = parseLegacyFormattedText(raw)
        return rebuildDecorationsAcrossSegments(
            parsed,
            includeBadges = true,
            terminalBadgesOnly = true,
            allowTruncatedPrefix = true,
            replaceMatchedName = true,
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
            rebuildVisitable(normalizeLegacyText(it), chatHeaderOnly = true, replaceMatchedName = true)
        }

    fun applyGradientToVisitable(message: StringVisitable): StringVisitable = rebuildVisitable(message)

    fun applyGradientToOrderedText(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.GRADIENT_TEXT) { source, animationTime ->
            rebuildOrderedTextFromPlan(source, source.gradientPlan ?: return@applyCachedOrderedTextTransform null, animationTime = animationTime)
        }

    fun applyChatHeaderToOrderedText(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.CHAT_HEADER_TEXT) { source, animationTime ->
            rebuildOrderedTextFromPlan(
                source,
                source.chatHeaderPlan ?: return@applyCachedOrderedTextTransform null,
                animationTime = animationTime,
                replaceMatchedName = true,
            )
        }

    fun applyNameplateDecorations(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.NAMEPLATE_TEXT) { source, animationTime ->
            rebuildOrderedTextFromPlan(
                source,
                source.nameplatePlan ?: return@applyCachedOrderedTextTransform null,
                includeBadges = true,
                animationTime = animationTime,
            )
        }

    fun applyScoreboardDecorations(text: OrderedText): OrderedText =
        applyCachedOrderedTextTransform(text, TransformKind.SCOREBOARD_TEXT) { source, animationTime ->
            rebuildOrderedTextFromPlan(
                source,
                source.scoreboardPlan ?: return@applyCachedOrderedTextTransform null,
                includeBadges = true,
                animationTime = animationTime,
            )
        }

    fun applyGradientToString(raw: String?): String? {
        if (raw.isNullOrEmpty()) {
            return raw
        }

        val version = currentRegistryVersion()
        if (!containsForKind(raw, TransformKind.GRADIENT_STRING, version)) {
            return raw
        }

        if (!shouldCacheFinalTransform(TransformKind.GRADIENT_STRING, raw)) {
            return applyGradientToStringUncached(raw)
        }

        val cacheKey = StringCacheKey(version, TransformKind.GRADIENT_STRING, raw)
        stringTransformCache.getCached(cacheKey)?.let { return it }
        val output = applyGradientToStringUncached(raw)

        stringTransformCache.putCached(cacheKey, output)
        return output
    }

    fun applyDecorationsToString(raw: String?): String? {
        return applyDecorationsToString(raw, terminalBadgesOnly = false)
    }

    fun applyScoreboardDecorationsToString(raw: String?): String? {
        return applyDecorationsToString(raw, terminalBadgesOnly = true)
    }

    fun applyScoreboardDisplayDecorationsToString(raw: String?): String? {
        return applyDisplayDecorationsToString(raw, terminalBadgesOnly = true)
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

        if (!shouldCacheFinalTransform(kind, raw)) {
            return applyDecorationsToStringUncached(raw, terminalBadgesOnly)
        }

        val cacheKey = StringCacheKey(version, kind, raw)
        stringTransformCache.getCached(cacheKey)?.let { return it }
        val output = applyDecorationsToStringUncached(raw, terminalBadgesOnly)

        stringTransformCache.putCached(cacheKey, output)
        return output
    }

    private fun applyDisplayDecorationsToString(raw: String?, terminalBadgesOnly: Boolean): String? {
        if (raw.isNullOrEmpty()) {
            return raw
        }

        val kind = if (terminalBadgesOnly) TransformKind.SCOREBOARD_DISPLAY_STRING else TransformKind.DECORATED_DISPLAY_STRING
        val version = currentRegistryVersion()
        if (!containsForKind(raw, kind, version)) {
            return raw
        }

        if (!shouldCacheFinalTransform(kind, raw)) {
            return applyDisplayDecorationsToStringUncached(raw, terminalBadgesOnly)
        }

        val cacheKey = StringCacheKey(version, kind, raw)
        stringTransformCache.getCached(cacheKey)?.let { return it }
        val output = applyDisplayDecorationsToStringUncached(raw, terminalBadgesOnly)

        stringTransformCache.putCached(cacheKey, output)
        return output
    }

    private fun applyGradientToStringUncached(raw: String): String {
        var output: String = raw
        PlayerCustomizationRegistry.entries.forEach { customization ->
            if (!customization.hasNameCustomization() || !customization.hasExplicitNameColors()) {
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
                rebuilt.append(toLegacyStyledName(matchedName, customization))
                index = matchIndex + match.matchedName.length
            }

            output = rebuilt.toString()
        }
        return output
    }

    private fun applyDecorationsToStringUncached(raw: String, terminalBadgesOnly: Boolean): String {
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
                        customization.hasExplicitNameColors() || customization.nameBold -> toLegacyStyledName(matchedName, customization, inheritedRankCodes)
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
        return output
    }

    private fun applyDisplayDecorationsToStringUncached(raw: String, terminalBadgesOnly: Boolean): String {
        var output: String = raw
        PlayerCustomizationRegistry.entries.forEach { customization ->
            if (!customization.hasChatDisplayOverride() || findNameMatch(output, customization, allowTruncatedPrefix = terminalBadgesOnly) == null) {
                return@forEach
            }

            val rebuilt = StringBuilder()
            var index = 0
            while (index < output.length) {
                val match = findNameMatch(output, customization, index, allowTruncatedPrefix = terminalBadgesOnly)
                if (match == null) {
                    rebuilt.append(output.substring(index))
                    break
                }

                val matchIndex = match.index
                if (matchIndex > index) {
                    rebuilt.append(output.substring(index, matchIndex))
                }

                val matchedName = output.substring(matchIndex, matchIndex + match.matchedName.length)
                val displayName = customization.displayName(matchedName)
                val inheritedRankCodes = inheritedLegacyRankCodes(output, matchIndex, customization)
                rebuilt.append(
                    when {
                        displayName != matchedName || customization.hasNameCustomization() ->
                            toLegacyStyledName(displayName, customization, inheritedRankCodes)
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
        return output
    }

    private fun styledSelfName(
        version: Long,
        matchedName: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): Text {
        val displayName = customization.displayName(matchedName)
        if (customization.hasAnimatedGradient()) {
            return appendBadge(cachedGradient(displayName, customization), customization)
        }

        val cacheKey = SelfNameCacheKey(version, displayName.lowercase(Locale.ROOT))
        selfNameCache.getCached(cacheKey)?.let { return it }

        val styled = appendBadge(cachedGradient(displayName, customization), customization)
        selfNameCache.putCached(cacheKey, styled)
        return styled
    }

    private fun applyCachedTextTransform(
        message: Text,
        kind: TransformKind,
        transform: (Text) -> Text,
    ): Text {
        textIdentityCache(kind).get(message)?.let { return it }

        val version = currentRegistryVersion()
        val plain = message.string
        if (!containsForKind(plain, kind, version)) {
            cacheTextIdentity(kind, message, message)
            return message
        }

        val animated = hasAnimatedGradientMatch(plain, candidatesForKind(kind))
        if (animated || forceAnimatedNameUncached) {
            val transformed = withRenderPath(kind) { transform(message) }
            cacheTextIdentity(kind, transformed, transformed)
            publishDebugTransform(
                kind = kind,
                sourceText = plain,
                resultText = transformed.string,
                resultIdentityHash = System.identityHashCode(transformed),
                resultHash = transformed.string.hashCode(),
                finalOrderedTextCacheUsed = false,
                sourceDataCacheUsed = false,
            )
            return transformed
        }

        textIdentityCache(kind).get(message)?.let { return it }

        val runs = collectRuns(message)
        val cacheKey = TextCacheKey(version, kind, runsToPlain(runs), styleHash(runs))
        textTransformCache.getCached(cacheKey)?.let { cached ->
            cacheTextIdentity(kind, message, cached)
            return cached
        }

        val transformed = withRenderPath(kind) { transform(message) }
        if (transformed !== message) {
            textTransformCache.putCached(cacheKey, transformed)
            cacheTextIdentity(kind, transformed, transformed)
        }
        cacheTextIdentity(kind, message, transformed)
        clearDebugTransform()
        return transformed
    }

    private fun applyCachedOrderedTextTransform(
        text: OrderedText,
        kind: TransformKind,
        transform: (OrderedTextSourceData, Double) -> Text?,
    ): OrderedText {
        incrementDebugCounter(debugCounters.orderedTextCalls)
        orderedTextIdentityCache(kind).get(text)?.let { return it }

        val version = currentRegistryVersion()
        val bypassSourceCache = forceAnimatedNameUncached
        val sourceLookup = orderedTextSource(text, version, bypassSourceCache)
        val source = sourceLookup.data
        val plan = source.planFor(kind)
        if (plan == null) {
            cacheOrderedTextIdentity(kind, text, text)
            return text
        }

        val resultCacheKey = OrderedTextResultCacheKey(version, kind, source.plain, source.styleHash)
        val animationTime = if (plan.hasAnimatedGradient) currentAnimationTime() else 0.0
        val frameIndex = if (plan.hasAnimatedGradient && !forceAnimatedNameUncached) {
            currentAnimationFrameIndex(animationTime)
        } else {
            null
        }

        if (!plan.hasAnimatedGradient && !forceAnimatedNameUncached) {
            orderedTextStaticResultCache.getCached(resultCacheKey)?.let { cached ->
                incrementDebugCounter(debugCounters.finalStaticCacheHits)
                cacheOrderedTextIdentity(kind, text, cached)
                cacheOrderedTextIdentity(kind, cached, cached)
                return cached
            }
            incrementDebugCounter(debugCounters.finalStaticCacheMisses)
        } else if (frameIndex != null) {
            orderedTextAnimatedFrameCache.getCached(OrderedTextAnimatedFrameCacheKey(resultCacheKey, frameIndex))?.let { cached ->
                incrementDebugCounter(debugCounters.animatedFrameCacheHits)
                cacheOrderedTextIdentity(kind, cached, cached)
                return cached
            }
            incrementDebugCounter(debugCounters.animatedFrameCacheMisses)
        }

        val transformed = withRenderPath(kind) { transform(source, animationTime) }
        if (transformed == null) {
            return text
        }

        incrementDebugCounter(debugCounters.asOrderedTextCalls)
        val ordered = transformed.asOrderedText()
        if (!bypassSourceCache) {
            orderedTextSourceCache.put(ordered, source)
        }
        if (!plan.hasAnimatedGradient && !forceAnimatedNameUncached) {
            orderedTextStaticResultCache.putCached(resultCacheKey, ordered)
            cacheOrderedTextIdentity(kind, text, ordered)
        } else if (frameIndex != null) {
            orderedTextAnimatedFrameCache.putCached(OrderedTextAnimatedFrameCacheKey(resultCacheKey, frameIndex), ordered)
        }
        cacheOrderedTextIdentity(kind, ordered, ordered)
        publishDebugTransform(
            kind = kind,
            sourceText = source.plain,
            resultText = transformed.string,
            resultIdentityHash = System.identityHashCode(ordered),
            resultHash = source.styleHash,
            finalOrderedTextCacheUsed = false,
            sourceDataCacheUsed = sourceLookup.cacheUsed,
            plan = plan,
        )
        return ordered
    }

    private fun textIdentityCache(kind: TransformKind): IdentityCache<Text, Text> =
        when (kind) {
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING -> gradientTextIdentityCache
            TransformKind.NAMEPLATE_TEXT, TransformKind.NAMEPLATE_DISPLAY_TEXT, TransformKind.DECORATED_STRING, TransformKind.DECORATED_DISPLAY_STRING -> nameplateTextIdentityCache
            TransformKind.SCOREBOARD_TEXT, TransformKind.SCOREBOARD_DISPLAY_TEXT, TransformKind.SCOREBOARD_STRING, TransformKind.SCOREBOARD_DISPLAY_STRING -> scoreboardTextIdentityCache
            TransformKind.SIDEBAR_TEXT -> sidebarTextIdentityCache
            TransformKind.CHAT_HEADER_TEXT -> chatHeaderTextIdentityCache
        }

    private fun orderedTextIdentityCache(kind: TransformKind): IdentityCache<OrderedText, OrderedText> =
        when (kind) {
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING, TransformKind.CHAT_HEADER_TEXT -> gradientOrderedTextIdentityCache
            TransformKind.NAMEPLATE_TEXT, TransformKind.NAMEPLATE_DISPLAY_TEXT, TransformKind.DECORATED_STRING, TransformKind.DECORATED_DISPLAY_STRING, TransformKind.SIDEBAR_TEXT -> nameplateOrderedTextIdentityCache
            TransformKind.SCOREBOARD_TEXT, TransformKind.SCOREBOARD_DISPLAY_TEXT, TransformKind.SCOREBOARD_STRING, TransformKind.SCOREBOARD_DISPLAY_STRING -> scoreboardOrderedTextIdentityCache
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
            TransformKind.GRADIENT_STRING -> containsCandidate(text, PlayerCustomizationRegistry.gradientNameCandidates)

            TransformKind.NAMEPLATE_DISPLAY_TEXT,
            TransformKind.CHAT_HEADER_TEXT -> containsCandidate(text, PlayerCustomizationRegistry.chatHeaderNameCandidates)

            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_STRING -> containsCandidate(text, PlayerCustomizationRegistry.scoreboardDisplayNameCandidates)

            TransformKind.NAMEPLATE_TEXT,
            TransformKind.DECORATED_STRING -> containsCandidate(text, PlayerCustomizationRegistry.styledNameCandidates)

            TransformKind.DECORATED_DISPLAY_STRING -> containsCandidate(text, PlayerCustomizationRegistry.nameplateDisplayCandidates)

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

    private fun orderedTextSource(
        text: OrderedText,
        version: Long,
        bypassCache: Boolean = false,
    ): OrderedTextSourceLookup {
        if (!bypassCache) {
            orderedTextSourceCache.get(text)?.let {
                incrementDebugCounter(debugCounters.sourceCacheHits)
                return OrderedTextSourceLookup(it, true)
            }
        }
        incrementDebugCounter(debugCounters.sourceCacheMisses)

        val runs = collectRuns(text)
        val plain = runsToPlain(runs)
        val styleHash = styleHash(runs)
        val source = OrderedTextSourceData(
            plain = plain,
            characterCount = plain.length,
            runs = runs,
            styleHash = styleHash,
            gradientPlan = orderedTextPlan(version, runs, plain, styleHash, TransformKind.GRADIENT_TEXT),
            nameplatePlan = orderedTextPlan(version, runs, plain, styleHash, TransformKind.NAMEPLATE_TEXT),
            scoreboardPlan = orderedTextPlan(version, runs, plain, styleHash, TransformKind.SCOREBOARD_TEXT),
            chatHeaderPlan = orderedTextPlan(version, runs, plain, styleHash, TransformKind.CHAT_HEADER_TEXT),
        )
        if (!bypassCache) {
            orderedTextSourceCache.put(text, source)
        }
        return OrderedTextSourceLookup(source, false)
    }

    private fun orderedTextPlan(
        version: Long,
        runs: List<StyledRun>,
        plain: String,
        styleHash: Int,
        kind: TransformKind,
    ): OrderedTextTransformPlan? {
        val cacheKey = OrderedTextPlanCacheKey(version, kind, plain, styleHash)
        orderedTextPlanCache.getCached(cacheKey)?.let {
            incrementDebugCounter(debugCounters.planCacheHits)
            return it.plan
        }
        incrementDebugCounter(debugCounters.planCacheMisses)
        incrementDebugCounter(debugCounters.buildPlanCalls)
        val plan = buildOrderedTextPlan(runs, plain, kind)
        orderedTextPlanCache.putCached(cacheKey, OrderedTextPlanCacheValue(plan))
        return plan
    }

    private fun buildOrderedTextPlan(
        runs: List<StyledRun>,
        plain: String,
        kind: TransformKind,
    ): OrderedTextTransformPlan? {
        val candidates = candidatesForKind(kind)
        if (plain.isEmpty() || candidates.isEmpty()) {
            return null
        }

        val includeBadges = kind != TransformKind.GRADIENT_TEXT && kind != TransformKind.CHAT_HEADER_TEXT
        val terminalBadgesOnly = kind == TransformKind.SCOREBOARD_TEXT
        val matchBoundary = if (kind == TransformKind.CHAT_HEADER_TEXT) chatHeaderBoundary(plain) else plain.length
        val matches = mutableListOf<ResolvedOrderedMatch>()
        var hasAnimatedGradient = false
        var index = 0

        while (index < matchBoundary) {
            val match = findFirstNameMatch(plain, candidates, index) ?: break
            val customization = match.customization
            val start = match.nameMatch.index
            val end = start + match.nameMatch.matchedName.length
            if (start >= matchBoundary || end > matchBoundary) {
                break
            }
            val isAnimatedGradient = customization.hasAnimatedGradient()
            val badgeText = customization.nameBadge?.text
            matches.add(
                ResolvedOrderedMatch(
                    start = start,
                    end = end,
                    content = plain.substring(start, end),
                    baseStyle = styleAt(runs, start),
                    customization = customization,
                    animatedStyle = resolveAnimatedGradientStyle(customization),
                    isAnimatedGradient = isAnimatedGradient,
                    hasBadge = customization.hasBadge,
                    hasDecorations = customization.hasDecorations,
                    hasExplicitNameColors = customization.explicitNameColors,
                    hasBadgeAlready = includeBadges && badgeText != null && hasPlainBadgeImmediatelyAfter(plain, end, badgeText),
                    hasTrailingContent = terminalBadgesOnly && hasPlainVisibleContentAfter(plain, end),
                ),
            )
            hasAnimatedGradient = hasAnimatedGradient || isAnimatedGradient
            index = end
        }

        return matches.takeIf { it.isNotEmpty() }?.let {
            OrderedTextTransformPlan(matches = it, hasAnimatedGradient = hasAnimatedGradient)
        }
    }

    private fun rebuildOrderedTextFromPlan(
        source: OrderedTextSourceData,
        plan: OrderedTextTransformPlan,
        includeBadges: Boolean = false,
        animationTime: Double = 0.0,
        replaceMatchedName: Boolean = false,
    ): Text {
        val rebuilt = Text.empty()
        var index = 0

        plan.matches.forEach { match ->
            if (match.start > index) {
                appendOriginalRange(rebuilt, source.runs, index, match.start)
            }

            val styledName = when {
                replaceMatchedName -> cachedGradient(match.customization.displayName(match.content), match.customization, match.baseStyle)
                match.hasExplicitNameColors -> styledMatchText(match, animationTime)
                else -> buildOriginalRangeText(source.runs, match.start, match.end)
            }

            if (includeBadges && match.hasBadge && !match.hasBadgeAlready && !match.hasTrailingContent) {
                rebuilt.append(appendBadge(styledName, match.customization, match.baseStyle))
            } else {
                rebuilt.append(styledName)
            }
            index = match.end
        }

        if (index < source.characterCount) {
            appendOriginalRange(rebuilt, source.runs, index, source.characterCount)
        }

        return rebuilt
    }

    private fun styledMatchText(match: ResolvedOrderedMatch, animationTime: Double): Text {
        val effectiveBaseStyle = applyCustomNameStyle(match.baseStyle, match.customization)
        if (match.isAnimatedGradient) {
            val animatedStyle = match.animatedStyle ?: return Text.literal(match.content).setStyle(effectiveBaseStyle)
            return gradientText(match.content, animatedStyle, effectiveBaseStyle, animationTime)
        }
        return cachedGradient(match.content, match.customization, match.baseStyle)
    }

    private fun rebuildVisitable(
        message: StringVisitable,
        includeBadges: Boolean = false,
        chatHeaderOnly: Boolean = false,
        terminalBadgesOnly: Boolean = false,
        replaceMatchedName: Boolean = false,
    ): Text {
        val rebuilt = Text.empty()
        var changed = false
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = plainText(message)
        val headerBoundary = if (chatHeaderOnly) chatHeaderBoundary(plain) else Int.MAX_VALUE
        val candidates = candidatesForVisitableTransform(includeBadges, replaceMatchedName)
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
                    replaceMatchedName,
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

        val candidates = candidatesForVisitableTransform(includeBadges, replaceMatchedName = false)
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
        replaceMatchedName: Boolean = false,
    ): Text? {
        val runs = collectRuns(message)
        return rebuildDecorationsAcrossRuns(runs, includeBadges, terminalBadgesOnly, allowTruncatedPrefix, replaceMatchedName)
    }

    private fun rebuildDecorationsAcrossSegments(
        message: OrderedText,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean = false,
        allowTruncatedPrefix: Boolean = false,
        replaceMatchedName: Boolean = false,
    ): Text? {
        val runs = collectRuns(message)
        return rebuildDecorationsAcrossRuns(runs, includeBadges, terminalBadgesOnly, allowTruncatedPrefix, replaceMatchedName)
    }

    private fun rebuildDecorationsAcrossRuns(
        runs: List<StyledRun>,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean,
        allowTruncatedPrefix: Boolean,
        replaceMatchedName: Boolean,
    ): Text? {
        val plain = runsToPlain(runs)
        val candidates = when {
            allowTruncatedPrefix && replaceMatchedName -> PlayerCustomizationRegistry.scoreboardDisplayNameCandidates
            allowTruncatedPrefix && includeBadges -> PlayerCustomizationRegistry.scoreboardStyledNameCandidates
            allowTruncatedPrefix -> PlayerCustomizationRegistry.scoreboardGradientNameCandidates
            replaceMatchedName -> PlayerCustomizationRegistry.nameplateDisplayCandidates
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
            val displayName = if (replaceMatchedName) customization.displayName(resolvedMatchedName) else resolvedMatchedName
            val styledName = when {
                replaceMatchedName || customization.hasNameCustomization() -> {
                    changed = true
                    cachedGradient(displayName, customization, baseStyle)
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

    private fun candidatesForVisitableTransform(
        includeBadges: Boolean,
        replaceMatchedName: Boolean,
    ): List<PlayerCustomizationRegistry.NameCandidate> =
        when {
            replaceMatchedName -> PlayerCustomizationRegistry.chatHeaderNameCandidates
            includeBadges -> PlayerCustomizationRegistry.styledNameCandidates
            else -> PlayerCustomizationRegistry.gradientNameCandidates
        }

    private fun appendStyledSegment(
        target: MutableText,
        segment: String,
        style: Style,
        recentSegments: ArrayDeque<StyledSegment>,
        includeBadges: Boolean = false,
        plainText: String = segment,
        segmentStart: Int = 0,
        terminalBadgesOnly: Boolean = false,
        replaceMatchedName: Boolean = false,
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
            val displayName = if (replaceMatchedName) customization.displayName(resolvedMatchedName) else resolvedMatchedName
            val baseNameStyle = inheritedRankStyle(
                prefix = remaining.substring(0, matchIndex),
                customization = customization,
                defaultStyle = style,
                recentSegments = recentSegments,
            )
            val styledName = cachedGradient(displayName, customization, baseNameStyle)
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
        if (customization.hasExplicitNameColors()) {
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
        if (customization.hasExplicitNameColors()) {
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

    private fun PlayerCustomizationRegistry.PlayerCustomization.hasAnimatedGradient(): Boolean =
        nameAnimated && nameColors?.let { it.left != it.right } == true

    private inline fun <T> withRenderPath(kind: TransformKind, action: () -> T): T {
        val previous = activeRenderPath.get()
        activeRenderPath.set(kind)
        return try {
            action()
        } finally {
            if (previous == null) {
                activeRenderPath.remove()
            } else {
                activeRenderPath.set(previous)
            }
        }
    }

    private fun shouldCacheFinalTransform(kind: TransformKind, text: String): Boolean =
        !hasAnimatedGradientMatch(text, candidatesForKind(kind))

    private fun candidatesForKind(kind: TransformKind): List<PlayerCustomizationRegistry.NameCandidate> =
        when (kind) {
            TransformKind.GRADIENT_TEXT,
            TransformKind.GRADIENT_STRING -> PlayerCustomizationRegistry.gradientNameCandidates

            TransformKind.NAMEPLATE_DISPLAY_TEXT,
            TransformKind.CHAT_HEADER_TEXT -> PlayerCustomizationRegistry.chatHeaderNameCandidates

            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_STRING -> PlayerCustomizationRegistry.scoreboardDisplayNameCandidates

            TransformKind.NAMEPLATE_TEXT,
            TransformKind.DECORATED_STRING -> PlayerCustomizationRegistry.styledNameCandidates

            TransformKind.DECORATED_DISPLAY_STRING -> PlayerCustomizationRegistry.nameplateDisplayCandidates

            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_STRING -> PlayerCustomizationRegistry.scoreboardStyledNameCandidates

            TransformKind.SIDEBAR_TEXT -> PlayerCustomizationRegistry.scoreboardGradientNameCandidates
        }

    private fun hasAnimatedGradientMatch(
        text: String,
        candidates: List<PlayerCustomizationRegistry.NameCandidate>,
    ): Boolean {
        if (text.isEmpty() || candidates.isEmpty()) {
            return false
        }

        var index = 0
        while (index < text.length) {
            val match = findFirstNameMatch(text, candidates, index) ?: return false
            if (match.customization.hasAnimatedGradient()) {
                return true
            }
            index = match.nameMatch.index + match.nameMatch.matchedName.length
        }
        return false
    }

    private fun resolveAnimatedGradientStyle(
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): AnimatedGradientStyle? {
        val colors = customization.nameColors ?: return null
        if (!customization.hasAnimatedGradient()) {
            return null
        }

        val stepsCount = customization.nameAnimationSteps ?: animatedGradientSteps
        val speed = customization.nameAnimationSpeed ?: animatedGradientSpeed
        val spacing = colors.spacing.coerceIn(1.0f, 10.0f)
        return animatedGradientCache.computeIfAbsent(
            AnimatedGradientCacheKey(colors.left, colors.right, stepsCount, speed.toRawBits(), spacing.toRawBits()),
        ) {
            AnimatedGradientStyle(
                steps = AnimatedGradientStyle.buildLoopGradient(colors.left, colors.right, stepsCount),
                speed = speed,
                spacing = spacing,
            )
        }
    }

    private fun cachedGradient(
        content: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        baseStyle: Style = Style.EMPTY,
    ): Text {
        val effectiveBaseStyle = applyCustomNameStyle(baseStyle, customization)
        customization.nameColors?.let { colors ->
            if (!customization.hasAnimatedGradient()) {
                return staticGradientText(content, colors, effectiveBaseStyle)
            }

            val animatedStyle = resolveAnimatedGradientStyle(customization)
                ?: return Text.literal(content).setStyle(effectiveBaseStyle)
            logAnimatedRender(content, customization)
            return gradientText(content, animatedStyle, effectiveBaseStyle, currentAnimationTime())
        }

        val key = when {
            !customization.nameLetterColors.isNullOrEmpty() -> ColorizedCacheKey(
                content = content.lowercase(Locale.ROOT),
                kind = "multicolor",
                colors = customization.nameLetterColors,
            )

            else -> return Text.literal(content).setStyle(effectiveBaseStyle)
        }

        val cached = componentCache.computeIfAbsent(key) { multiColorText(content, key.colors, Style.EMPTY) }

        if (effectiveBaseStyle.isEmpty) {
            return cached.copy()
        }

        val rebuilt = Text.empty()
        cached.visit({ style, segment ->
            rebuilt.append(Text.literal(segment).setStyle(style.withParent(effectiveBaseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return rebuilt
    }

    private fun multiColorText(content: String, colors: List<Int>, baseStyle: Style): Text {
        val text = Text.empty()
        val fallback = colors.lastOrNull() ?: return Text.literal(content).setStyle(baseStyle)

        content.forEachIndexed { index, character ->
            val color = colors.getOrElse(index) { fallback }
            text.append(Text.literal(character.toString()).setStyle(baseStyle.withColor(color)))
        }

        return text
    }

    private fun staticGradientText(
        content: String,
        colors: PlayerCustomizationRegistry.NameColors,
        baseStyle: Style,
    ): Text {
        val key = ColorizedCacheKey(
            content = content.lowercase(Locale.ROOT),
            kind = "gradient:${colors.left}:${colors.right}:${colors.spacing}",
            colors = listOf(colors.left, colors.right),
        )
        val cached = componentCache.computeIfAbsent(key) {
            val text = Text.empty()
            val lastIndex = (content.length - 1).coerceAtLeast(1)
            val spacing = colors.spacing.coerceIn(1.0f, 10.0f)
            content.forEachIndexed { index, character ->
                val progress = gradientFrequencyProgress(index, content.length, spacing)
                val color = gradientLoopColor(colors.left, colors.right, progress)
                text.append(Text.literal(character.toString()).setStyle(Style.EMPTY.withColor(color)))
            }
            text
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

    private fun gradientText(content: String, animatedStyle: AnimatedGradientStyle, baseStyle: Style, time: Double): Text {
        val text = Text.empty()

        content.forEachIndexed { index, character ->
            val color = animatedStyle.getColor(index, content.length, time)
            text.append(Text.literal(character.toString()).setStyle(baseStyle.withColor(color)))
        }

        return text
    }

    private fun toLegacyStyledName(
        content: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        inheritedRankCodes: String = "",
    ): String {
        val gradientColors = customization.nameColors
        val letterColors = customization.nameLetterColors
        if (gradientColors == null && letterColors.isNullOrEmpty()) {
            return buildString {
                if (inheritedRankCodes.isNotEmpty()) {
                    append(inheritedRankCodes)
                }
                if (customization.nameBold) {
                    append(legacyFormat).append('l')
                }
                append(content)
            }
        }

        val output = StringBuilder()
        val fallbackLetterColor = letterColors?.lastOrNull()
        val animatedStyle = gradientColors
            ?.takeIf { customization.hasAnimatedGradient() }
            ?.let { resolveAnimatedGradientStyle(customization) }
        val animationTime = if (animatedStyle != null) currentAnimationTime() else 0.0

        content.forEachIndexed { index, character ->
            val color = if (!letterColors.isNullOrEmpty()) {
                letterColors.getOrElse(index) { fallbackLetterColor ?: letterColors.last() }
            } else if (gradientColors != null && animatedStyle == null) {
                val frequency = gradientColors.spacing.coerceIn(1.0f, 10.0f)
                gradientLoopColor(gradientColors.left, gradientColors.right, gradientFrequencyProgress(index, content.length, frequency))
            } else {
                animatedStyle!!.getColor(index, content.length, animationTime)
            }
            val hex = "%06X".format(color)
            output.append(legacyFormat).append('x')
            hex.forEach { digit ->
                output.append(legacyFormat).append(digit)
            }
            if (customization.nameBold) {
                output.append(legacyFormat).append('l')
            }
            output.append(character)
        }

        return output.toString()
    }

    private fun applyCustomNameStyle(
        style: Style,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ): Style =
        if (customization.nameBold) style.withBold(true) else style

    private fun currentAnimationTime(): Double {
        val client = MinecraftClient.getInstance()
        val world = client?.world ?: return 0.0
        return world.time.toDouble() + client.renderTickCounter.getTickProgress(true).toDouble()
    }

    private fun currentAnimationFrameIndex(animationTime: Double): Long? {
        if (forceAnimatedNameUncached) {
            return null
        }

        val client = MinecraftClient.getInstance()
        val visiblePlayers = client?.world?.players?.size ?: 0
        if (visiblePlayers < largeLobbyAnimationThreshold) {
            return null
        }
        return floor(animationTime * throttledAnimationFps).toLong()
    }

    private fun incrementDebugCounter(counter: LongAdder) {
        if (!animatedNameDebugEnabled) {
            return
        }
        counter.increment()
        maybeLogDebugCounters()
    }

    private fun maybeLogDebugCounters() {
        val now = System.currentTimeMillis()
        val previous = debugCounterLastLog.get()
        if (now - previous < 1_000L || !debugCounterLastLog.compareAndSet(previous, now)) {
            return
        }

        ThrowerListMod.logger.info(
            "AnimatedNameProfiler orderedTextCalls={} planHits={} planMisses={} staticHits={} staticMisses={} frameHits={} frameMisses={} sourceHits={} sourceMisses={} asOrderedText={} buildPlans={} hudHits={} hudMisses={}",
            debugCounters.orderedTextCalls.sumThenReset(),
            debugCounters.planCacheHits.sumThenReset(),
            debugCounters.planCacheMisses.sumThenReset(),
            debugCounters.finalStaticCacheHits.sumThenReset(),
            debugCounters.finalStaticCacheMisses.sumThenReset(),
            debugCounters.animatedFrameCacheHits.sumThenReset(),
            debugCounters.animatedFrameCacheMisses.sumThenReset(),
            debugCounters.sourceCacheHits.sumThenReset(),
            debugCounters.sourceCacheMisses.sumThenReset(),
            debugCounters.asOrderedTextCalls.sumThenReset(),
            debugCounters.buildPlanCalls.sumThenReset(),
            debugCounters.hudCacheHits.sumThenReset(),
            debugCounters.hudCacheMisses.sumThenReset(),
        )
    }

    private fun interpolateColor(start: Int, end: Int, progress: Float): Int {
        val clamped = progress.coerceIn(0f, 1f)
        val startR = (start shr 16) and 0xFF
        val startG = (start shr 8) and 0xFF
        val startB = start and 0xFF

        val endR = (end shr 16) and 0xFF
        val endG = (end shr 8) and 0xFF
        val endB = end and 0xFF

        val r = (startR + ((endR - startR) * clamped)).toInt().coerceIn(0, 255)
        val g = (startG + ((endG - startG) * clamped)).toInt().coerceIn(0, 255)
        val b = (startB + ((endB - startB) * clamped)).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun gradientFrequencyProgress(index: Int, length: Int, frequency: Float): Float {
        val normalizedPosition = if (length <= 1) 0f else index.toFloat() / (length - 1).toFloat()
        return positiveModulo(normalizedPosition * frequency, 1f)
    }

    private fun gradientLoopColor(left: Int, right: Int, progress: Float): Int {
        val loopProgress = if (progress <= 0.5f) progress * 2f else (1f - progress) * 2f
        return interpolateColor(left, right, loopProgress)
    }

    private fun positiveModulo(value: Float, modulus: Float): Float {
        val remainder = value % modulus
        return if (remainder < 0f) remainder + modulus else remainder
    }

    private fun logAnimatedRender(
        matchedName: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ) {
        val renderPath = when (activeRenderPath.get()) {
            TransformKind.NAMEPLATE_TEXT, TransformKind.NAMEPLATE_DISPLAY_TEXT -> "nameplate"
            TransformKind.SCOREBOARD_TEXT, TransformKind.SCOREBOARD_DISPLAY_TEXT, TransformKind.SCOREBOARD_STRING, TransformKind.SCOREBOARD_DISPLAY_STRING, TransformKind.SIDEBAR_TEXT -> "scoreboard"
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING -> "text-renderer"
            TransformKind.CHAT_HEADER_TEXT -> "chat-header"
            TransformKind.DECORATED_STRING, TransformKind.DECORATED_DISPLAY_STRING -> "decorated-string"
            null -> "unknown"
        }
        val client = MinecraftClient.getInstance()
        val publicLobby = client != null && client.currentServerEntry != null && !client.isIntegratedServerRunning
        val speed = customization.nameAnimationSpeed ?: animatedGradientSpeed
        val logKey = "${customization.username.lowercase(Locale.ROOT)}|$renderPath|$publicLobby"
        if (!loggedAnimatedPaths.add(logKey)) {
            return
        }

        ThrowerListMod.logger.info(
            "Animated name render: player='{}' mode='animated_gradient' speed={} renderPath='{}' publicLobby={} dynamicAnimationEnabled={}",
            matchedName,
            speed,
            renderPath,
            publicLobby,
            true,
        )
    }

    private fun clearDebugTransform() {
        if (animatedNameDebugEnabled) {
            lastDebugTransform.remove()
        }
    }

    private fun publishDebugTransform(
        kind: TransformKind,
        sourceText: String,
        resultText: String,
        resultIdentityHash: Int,
        resultHash: Int,
        finalOrderedTextCacheUsed: Boolean,
        sourceDataCacheUsed: Boolean,
        plan: OrderedTextTransformPlan? = null,
    ) {
        if (!animatedNameDebugEnabled) {
            return
        }

        val match = plan?.matches?.firstOrNull { it.isAnimatedGradient }
            ?: findFirstNameMatch(sourceText, candidatesForKind(kind))
                ?.takeIf { it.customization.hasAnimatedGradient() }
                ?.let { matched ->
                    ResolvedOrderedMatch(
                        start = matched.nameMatch.index,
                        end = matched.nameMatch.index + matched.nameMatch.matchedName.length,
                        content = matched.nameMatch.matchedName,
                        baseStyle = Style.EMPTY,
                        customization = matched.customization,
                        animatedStyle = resolveAnimatedGradientStyle(matched.customization),
                        isAnimatedGradient = true,
                        hasBadge = matched.customization.hasBadge,
                        hasDecorations = matched.customization.hasDecorations,
                        hasExplicitNameColors = matched.customization.explicitNameColors,
                        hasBadgeAlready = false,
                        hasTrailingContent = false,
                    )
                }
            ?: run {
                lastDebugTransform.remove()
                return
            }

        val animatedStyle = match.animatedStyle
        val world = MinecraftClient.getInstance().world
        val animationTime = currentAnimationTime()
        lastDebugTransform.set(
            DebugTransformMetadata(
                kind = kind.name,
                renderPath = currentRenderPathName(),
                matchedPlayer = match.customization.username,
                styleMode = if (match.isAnimatedGradient) "animated_gradient" else "static",
                animated = match.isAnimatedGradient,
                worldTime = world?.time ?: -1L,
                animationTime = animationTime,
                animationOffset = animatedStyle?.offsetAt(animationTime) ?: 0f,
                finalOrderedTextCacheUsed = finalOrderedTextCacheUsed,
                sourceDataCacheUsed = sourceDataCacheUsed,
                resultIdentityHash = resultIdentityHash,
                resultHash = resultHash,
                resultText = resultText,
            ),
        )
    }

    private fun currentRenderPathName(): String =
        when (activeRenderPath.get()) {
            TransformKind.NAMEPLATE_TEXT, TransformKind.NAMEPLATE_DISPLAY_TEXT -> "nameplate"
            TransformKind.SCOREBOARD_TEXT, TransformKind.SCOREBOARD_DISPLAY_TEXT, TransformKind.SCOREBOARD_STRING, TransformKind.SCOREBOARD_DISPLAY_STRING, TransformKind.SIDEBAR_TEXT -> "scoreboard"
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING -> "text-renderer"
            TransformKind.CHAT_HEADER_TEXT -> "chat-header"
            TransformKind.DECORATED_STRING, TransformKind.DECORATED_DISPLAY_STRING -> "decorated-string"
            null -> "unknown"
        }

    fun debugRenderReceipt(
        stage: String,
        renderMethod: String,
        incomingText: String?,
        outgoingText: String?,
        outgoingIdentityHash: Int,
        receivedCachedText: Boolean,
    ) {
        if (!animatedNameDebugEnabled) {
            return
        }

        val debug = lastDebugTransform.get() ?: return
        val playerName = debug.matchedPlayer ?: return
        if (!shouldDebugPlayer(playerName)) {
            return
        }

        val client = MinecraftClient.getInstance()
        val world = client.world
        val visiblePlayers = world?.players?.size ?: 0
        val lobbyType = when {
            client.isIntegratedServerRunning -> "singleplayer"
            client.currentServerEntry != null -> "multiplayer-public"
            else -> "unknown"
        }
        val logKey = "$stage|$renderMethod|${playerName.lowercase(Locale.ROOT)}"
        val now = System.currentTimeMillis()
        val previous = debugLogTimes.put(logKey, now)
        if (previous != null && now - previous < debugLogIntervalMillis) {
            return
        }

        ThrowerListMod.logger.info(
            "AnimatedNameDebug stage={} player='{}' lobbyType={} visiblePlayers={} renderMethod={} styleMode={} animated={} worldTime={} animationTime={} animationOffset={} finalOrderedTextCacheUsed={} sourceDataCacheUsed={} receivedCachedText={} incomingHash={} outgoingHash={} outgoingIdentity={} incoming='{}' outgoing='{}'",
            stage,
            playerName,
            lobbyType,
            visiblePlayers,
            renderMethod,
            debug.styleMode,
            debug.animated,
            debug.worldTime,
            debug.animationTime,
            debug.animationOffset,
            debug.finalOrderedTextCacheUsed,
            debug.sourceDataCacheUsed,
            receivedCachedText,
            incomingText?.hashCode() ?: 0,
            outgoingText?.hashCode() ?: debug.resultHash,
            outgoingIdentityHash,
            incomingText.orEmpty().take(80),
            outgoingText.orEmpty().take(80),
        )
    }

    private fun shouldDebugPlayer(playerName: String): Boolean {
        val client = MinecraftClient.getInstance()
        val self = client.player?.gameProfile?.name?.lowercase(Locale.ROOT)
        if (playerName.lowercase(Locale.ROOT) == self) {
            return true
        }

        val otherAnimated = client.world?.players
            ?.asSequence()
            ?.mapNotNull { player -> PlayerCustomizationRegistry.find(player.gameProfile)?.takeIf { it.hasAnimatedGradient() }?.username }
            ?.firstOrNull { !it.equals(self, ignoreCase = true) }
            ?.lowercase(Locale.ROOT)
        return playerName.lowercase(Locale.ROOT) == otherAnimated
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
