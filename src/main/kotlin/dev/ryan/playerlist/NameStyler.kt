package dev.ryan.playerlist

import com.mojang.authlib.GameProfile
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.FormattedCharSequence
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.floor

object NameStyler {
    private const val animatedGradientSteps = 64
    private const val animatedGradientSpeed = 1.0f
    private const val textCacheLimit = 512
    private const val stringCacheLimit = 1024
    private const val matchCacheLimit = 2048
    private const val identityCacheSize = 2048
    private const val debugLogIntervalMillis = 750L
    private const val largeLobbyAnimationThreshold = 40
    private const val throttledAnimationFps = 15.0

    private val cacheStateLock = Any()
    // These two maps cache fully deterministic text styling artifacts.
    // They store only text/color data derived from player customization rules.
    private val styledTextComponentCache = ConcurrentHashMap<ColorizedCacheKey, Text>()
    private val animatedGradientStyleCache = ConcurrentHashMap<AnimatedGradientCacheKey, AnimatedGradientStyle>()
    // Name/text hooks run from HUD, scoreboard, nametag, tab, and TextRenderer paths.
    // The caches below are bounded or fixed-size and are cleared whenever the
    // customization registry version changes.
    private val selfNameCache = NameStylerLruCache<SelfNameCacheKey, Text>(stringCacheLimit)
    private val textTransformCache = NameStylerLruCache<TextCacheKey, Text>(textCacheLimit)
    private val stringTransformCache = NameStylerLruCache<StringCacheKey, String>(stringCacheLimit)
    private val matchCache = NameStylerLruCache<MatchCacheKey, Boolean>(matchCacheLimit)
    private val orderedTextPlanCache = NameStylerLruCache<OrderedTextPlanCacheKey, OrderedTextPlanCacheValue>(matchCacheLimit)
    private val orderedTextStaticResultCache = NameStylerLruCache<OrderedTextResultCacheKey, FormattedCharSequence>(textCacheLimit)
    private val orderedTextAnimatedFrameCache = NameStylerLruCache<OrderedTextAnimatedFrameCacheKey, FormattedCharSequence>(matchCacheLimit)
    private val loggedAnimatedRenderPaths = ConcurrentHashMap.newKeySet<String>()
    private val gradientTextIdentityCache = NameStylerIdentityCache<Text, Text>(identityCacheSize)
    private val nameplateTextIdentityCache = NameStylerIdentityCache<Text, Text>(identityCacheSize)
    private val scoreboardTextIdentityCache = NameStylerIdentityCache<Text, Text>(identityCacheSize)
    private val sidebarTextIdentityCache = NameStylerIdentityCache<Text, Text>(identityCacheSize)
    private val chatHeaderTextIdentityCache = NameStylerIdentityCache<Text, Text>(identityCacheSize)
    private val gradientOrderedTextIdentityCache = NameStylerIdentityCache<FormattedCharSequence, FormattedCharSequence>(identityCacheSize)
    private val nameplateOrderedTextIdentityCache = NameStylerIdentityCache<FormattedCharSequence, FormattedCharSequence>(identityCacheSize)
    private val scoreboardOrderedTextIdentityCache = NameStylerIdentityCache<FormattedCharSequence, FormattedCharSequence>(identityCacheSize)
    private val orderedTextSourceCache = NameStylerIdentityCache<FormattedCharSequence, OrderedTextSourceData>(identityCacheSize)
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
        synchronized(cacheStateLock) {
            styledTextComponentCache.clear()
            animatedGradientStyleCache.clear()
            selfNameCache.clearCache()
            textTransformCache.clearCache()
            stringTransformCache.clearCache()
            matchCache.clearCache()
            orderedTextPlanCache.clearCache()
            orderedTextStaticResultCache.clearCache()
            orderedTextAnimatedFrameCache.clearCache()
            loggedAnimatedRenderPaths.clear()
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
            synchronized(cacheStateLock) {
                if (observedRegistryVersion != version) {
                    // Registry changes mean the set of styled players changed. Every cache
                    // below is derived from registry content, so the safe behavior is to
                    // invalidate all cached styling artifacts and rebuild on demand.
                    styledTextComponentCache.clear()
                    animatedGradientStyleCache.clear()
                    selfNameCache.clearCache()
                    textTransformCache.clearCache()
                    stringTransformCache.clearCache()
                    matchCache.clearCache()
                    orderedTextPlanCache.clearCache()
                    orderedTextStaticResultCache.clearCache()
                    orderedTextAnimatedFrameCache.clearCache()
                    loggedAnimatedRenderPaths.clear()
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

    fun hasAnimatedGradientInOrderedText(text: FormattedCharSequence): Boolean {
        val version = currentRegistryVersion()
        val source = orderedTextSource(text, version).data
        val gradientPlan = orderedTextPlan(version, source.runs, source.plain, source.styleHash, TransformKind.GRADIENT_TEXT)
        return gradientPlan?.hasAnimatedGradient == true
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
        NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.allNameCandidates)

    fun containsStyledTargetName(text: String?): Boolean =
        NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.styledNameCandidates)

    fun containsStyledScoreboardTargetName(text: String?): Boolean =
        NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.scoreboardStyledNameCandidates)

    fun styledSelfName(profile: GameProfile?): Text {
        val version = currentRegistryVersion()
        val customization = PlayerCustomizationRegistry.find(profile)
            ?: return Component.literal(profile?.name ?: "")
        val matchedName = profile?.name ?: customization.username
        return styledSelfName(version, matchedName, customization)
    }

    fun styledSelfName(name: String?): Text {
        val version = currentRegistryVersion()
        val customization = PlayerCustomizationRegistry.findByName(name)
            ?: return Component.literal(name ?: "")
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
        applyCachedTextTransform(message, TransformKind.NAMEPLATE_TEXT) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildVisitable(normalizedMessage, includeBadges = true)
        }

    fun applyNameplateDecorations(raw: String): Text =
        rebuildVisitable(LegacyMinecraftTextStyler.parseLegacyFormattedText(raw), includeBadges = true)

    fun applyNameplateDisplayDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.NAMEPLATE_DISPLAY_TEXT) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildDecorationsAcrossSegments(
                normalizedMessage,
                includeBadges = true,
                replaceMatchedName = true,
            ) ?: sourceText
        }

    fun applyNameplateDisplayDecorations(raw: String): Text {
        val parsed = LegacyMinecraftTextStyler.parseLegacyFormattedText(raw)
        return rebuildDecorationsAcrossSegments(
            parsed,
            includeBadges = true,
            replaceMatchedName = true,
        ) ?: parsed
    }

    fun applyNameplateDisplayDecorationsToString(raw: String?): String? =
        applyDisplayDecorationsToString(raw, terminalBadgesOnly = false)

    fun applyScoreboardDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.SCOREBOARD_TEXT) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildDecorationsAcrossSegments(
                normalizedMessage,
                includeBadges = true,
                terminalBadgesOnly = true,
                allowTruncatedPrefix = true,
            ) ?: sourceText
        }

    fun applyScoreboardDisplayDecorations(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.SCOREBOARD_DISPLAY_TEXT) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildDecorationsAcrossSegments(
                normalizedMessage,
                includeBadges = true,
                terminalBadgesOnly = true,
                allowTruncatedPrefix = true,
                replaceMatchedName = true,
            ) ?: sourceText
        }

    fun applyScoreboardDecorations(raw: String): Text {
        val parsed = LegacyMinecraftTextStyler.parseLegacyFormattedText(raw)
        return rebuildDecorationsAcrossSegments(
            parsed,
            includeBadges = true,
            terminalBadgesOnly = true,
            allowTruncatedPrefix = true,
        ) ?: parsed
    }

    fun applyScoreboardDisplayDecorations(raw: String): Text {
        val parsed = LegacyMinecraftTextStyler.parseLegacyFormattedText(raw)
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
        ) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildDecorationsAcrossSegments(
                normalizedMessage,
                includeBadges = includeBadges,
                allowTruncatedPrefix = true,
            ) ?: sourceText
        }

    fun applyGradientToName(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.GRADIENT_TEXT) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildGradientAcrossSegments(normalizedMessage) ?: sourceText
        }

    fun applyGradientToChatHeader(message: Text): Text =
        applyCachedTextTransform(message, TransformKind.CHAT_HEADER_TEXT) { sourceText ->
            val normalizedMessage = LegacyMinecraftTextStyler.normalizeLegacyText(sourceText)
            rebuildVisitable(normalizedMessage, chatHeaderOnly = true, replaceMatchedName = true)
        }

    fun applyGradientToVisitable(message: FormattedText): FormattedText = rebuildVisitable(message)

    fun applyGradientToOrderedText(text: FormattedCharSequence): FormattedCharSequence =
        applyCachedOrderedTextTransform(text, TransformKind.GRADIENT_TEXT) { source, plan, animationTime ->
            rebuildOrderedTextFromPlan(source, plan, animationTime = animationTime)
        }

    fun applyChatHeaderToOrderedText(text: FormattedCharSequence): FormattedCharSequence =
        applyCachedOrderedTextTransform(text, TransformKind.CHAT_HEADER_TEXT) { source, plan, animationTime ->
            rebuildOrderedTextFromPlan(
                source,
                plan,
                animationTime = animationTime,
                replaceMatchedName = true,
            )
        }

    fun applyNameplateDecorations(text: FormattedCharSequence): FormattedCharSequence =
        applyCachedOrderedTextTransform(text, TransformKind.NAMEPLATE_TEXT) { source, plan, animationTime ->
            rebuildOrderedTextFromPlan(
                source,
                plan,
                includeBadges = true,
                animationTime = animationTime,
            )
        }

    fun applyScoreboardDecorations(text: FormattedCharSequence): FormattedCharSequence =
        applyCachedOrderedTextTransform(text, TransformKind.SCOREBOARD_TEXT) { source, plan, animationTime ->
            rebuildOrderedTextFromPlan(
                source,
                plan,
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
        val cachedGradientString = stringTransformCache.getCached(cacheKey)
        if (cachedGradientString != null) {
            return cachedGradientString
        }
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
        val cachedDecoratedString = stringTransformCache.getCached(cacheKey)
        if (cachedDecoratedString != null) {
            return cachedDecoratedString
        }
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
        val cachedDisplayDecoratedString = stringTransformCache.getCached(cacheKey)
        if (cachedDisplayDecoratedString != null) {
            return cachedDisplayDecoratedString
        }
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

            if (NameStyleMatcher.findNameMatch(output, customization) == null) {
                return@forEach
            }

            val rebuilt = StringBuilder()
            var index = 0
            while (index < output.length) {
                val match = NameStyleMatcher.findNameMatch(output, customization, index)
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
            if (!customization.hasNameCustomization() || NameStyleMatcher.findNameMatch(output, customization) == null) {
                return@forEach
            }

            val rebuilt = StringBuilder()
            var index = 0
            while (index < output.length) {
                val match = NameStyleMatcher.findNameMatch(output, customization, index)
                if (match == null) {
                    rebuilt.append(output.substring(index))
                    break
                }

                val matchIndex = match.index
                if (matchIndex > index) {
                    rebuilt.append(output.substring(index, matchIndex))
                }

                val matchedName = output.substring(matchIndex, matchIndex + match.matchedName.length)
                val inheritedRankCodes = LegacyMinecraftTextStyler.inheritedLegacyRankCodes(output, matchIndex, customization)
                rebuilt.append(
                    when {
                        customization.hasExplicitNameColors() || customization.nameBold ->
                            toLegacyStyledName(matchedName, customization, inheritedRankCodes)
                        inheritedRankCodes.isNotEmpty() -> inheritedRankCodes + matchedName
                        else -> matchedName
                    },
                )

                customization.nameBadge?.takeUnless { badge ->
                    LegacyMinecraftTextStyler.hasBadgeImmediatelyAfter(output, matchIndex + match.matchedName.length, badge.text)
                }?.let { badge ->
                    if (terminalBadgesOnly &&
                        LegacyMinecraftTextStyler.hasVisibleContentAfter(output, matchIndex + match.matchedName.length)
                    ) {
                        return@let
                    }
                    rebuilt.append(' ')
                    rebuilt.append(GradientColorMath.toLegacyColorCode(badge.color))
                    if (badge.bold) {
                        rebuilt.append(LEGACY_MINECRAFT_FORMAT_CODE).append('l')
                    }
                    rebuilt.append(badge.text)
                    rebuilt.append(LEGACY_MINECRAFT_FORMAT_CODE).append('r')
                    rebuilt.append(LegacyMinecraftTextStyler.activeLegacyCodes(output, matchIndex))
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
            if (!customization.hasChatDisplayOverride() ||
                NameStyleMatcher.findNameMatch(output, customization, allowTruncatedPrefix = terminalBadgesOnly) == null
            ) {
                return@forEach
            }

            val rebuilt = StringBuilder()
            var index = 0
            while (index < output.length) {
                val match = NameStyleMatcher.findNameMatch(output, customization, index, allowTruncatedPrefix = terminalBadgesOnly)
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
                val inheritedRankCodes = LegacyMinecraftTextStyler.inheritedLegacyRankCodes(output, matchIndex, customization)
                rebuilt.append(
                    when {
                        displayName != matchedName || customization.hasNameCustomization() ->
                            toLegacyStyledName(displayName, customization, inheritedRankCodes)
                        inheritedRankCodes.isNotEmpty() -> inheritedRankCodes + matchedName
                        else -> matchedName
                    },
                )

                customization.nameBadge?.takeUnless { badge ->
                    LegacyMinecraftTextStyler.hasBadgeImmediatelyAfter(output, matchIndex + match.matchedName.length, badge.text)
                }?.let { badge ->
                    if (terminalBadgesOnly &&
                        LegacyMinecraftTextStyler.hasVisibleContentAfter(output, matchIndex + match.matchedName.length)
                    ) {
                        return@let
                    }
                    rebuilt.append(' ')
                    rebuilt.append(GradientColorMath.toLegacyColorCode(badge.color))
                    if (badge.bold) {
                        rebuilt.append(LEGACY_MINECRAFT_FORMAT_CODE).append('l')
                    }
                    rebuilt.append(badge.text)
                    rebuilt.append(LEGACY_MINECRAFT_FORMAT_CODE).append('r')
                    rebuilt.append(LegacyMinecraftTextStyler.activeLegacyCodes(output, matchIndex))
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
        val cachedSelfStyledName = selfNameCache.getCached(cacheKey)
        if (cachedSelfStyledName != null) {
            return cachedSelfStyledName
        }

        val styled = appendBadge(cachedGradient(displayName, customization), customization)
        selfNameCache.putCached(cacheKey, styled)
        return styled
    }

    private fun applyCachedTextTransform(
        message: Text,
        kind: TransformKind,
        transform: (Text) -> Text,
    ): Text {
        val firstCachedIdentityText = textIdentityCache(kind).get(message)
        if (firstCachedIdentityText != null) {
            return firstCachedIdentityText
        }

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

        val secondCachedIdentityText = textIdentityCache(kind).get(message)
        if (secondCachedIdentityText != null) {
            return secondCachedIdentityText
        }

        val runs = OrderedTextStyleSupport.collectRuns(message)
        val cacheKey = TextCacheKey(
            version,
            kind,
            OrderedTextStyleSupport.runsToPlain(runs),
            OrderedTextStyleSupport.styleHash(runs),
        )
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
        text: FormattedCharSequence,
        kind: TransformKind,
        transform: (OrderedTextSourceData, OrderedTextTransformPlan, Double) -> Text?,
    ): FormattedCharSequence {
        incrementDebugCounter(debugCounters.orderedTextCalls)
        val cachedOrderedTextIdentity = orderedTextIdentityCache(kind).get(text)
        if (cachedOrderedTextIdentity != null) {
            return cachedOrderedTextIdentity
        }

        val version = currentRegistryVersion()
        val bypassSourceCache = forceAnimatedNameUncached
        val sourceLookup = orderedTextSource(text, version, bypassSourceCache)
        val source = sourceLookup.data
        val plan = orderedTextPlan(version, source.runs, source.plain, source.styleHash, kind)
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

        val transformed = withRenderPath(kind) { transform(source, plan, animationTime) }
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

    private fun textIdentityCache(kind: TransformKind): NameStylerIdentityCache<Text, Text> =
        when (kind) {
            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING -> gradientTextIdentityCache
            TransformKind.NAMEPLATE_TEXT,
            TransformKind.NAMEPLATE_DISPLAY_TEXT,
            TransformKind.DECORATED_STRING,
            TransformKind.DECORATED_DISPLAY_STRING,
                -> nameplateTextIdentityCache

            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_STRING,
            TransformKind.SCOREBOARD_DISPLAY_STRING,
                -> scoreboardTextIdentityCache

            TransformKind.SIDEBAR_TEXT -> sidebarTextIdentityCache
            TransformKind.CHAT_HEADER_TEXT -> chatHeaderTextIdentityCache
        }

    private fun orderedTextIdentityCache(kind: TransformKind): NameStylerIdentityCache<FormattedCharSequence, FormattedCharSequence> =
        when (kind) {
            TransformKind.GRADIENT_TEXT,
            TransformKind.GRADIENT_STRING,
            TransformKind.CHAT_HEADER_TEXT,
                -> gradientOrderedTextIdentityCache

            TransformKind.NAMEPLATE_TEXT,
            TransformKind.NAMEPLATE_DISPLAY_TEXT,
            TransformKind.DECORATED_STRING,
            TransformKind.DECORATED_DISPLAY_STRING,
            TransformKind.SIDEBAR_TEXT,
                -> nameplateOrderedTextIdentityCache

            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_STRING,
            TransformKind.SCOREBOARD_DISPLAY_STRING,
                -> scoreboardOrderedTextIdentityCache
        }

    private fun cacheTextIdentity(kind: TransformKind, source: Text, result: Text) {
        textIdentityCache(kind).put(source, result)
    }

    private fun cacheOrderedTextIdentity(kind: TransformKind, source: FormattedCharSequence, result: FormattedCharSequence) {
        orderedTextIdentityCache(kind).put(source, result)
    }

    private fun containsForKind(text: String, kind: TransformKind, version: Long): Boolean {
        if (text.isEmpty()) {
            return false
        }

        val cacheKey = MatchCacheKey(version, kind, text)
        val cachedMatchResult = matchCache.getCached(cacheKey)
        if (cachedMatchResult != null) {
            return cachedMatchResult
        }

        val result = when (kind) {
            TransformKind.GRADIENT_TEXT,
            TransformKind.GRADIENT_STRING -> NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.gradientNameCandidates)

            TransformKind.NAMEPLATE_DISPLAY_TEXT,
            TransformKind.CHAT_HEADER_TEXT -> NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.chatHeaderNameCandidates)

            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_STRING ->
                NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.scoreboardDisplayNameCandidates)

            TransformKind.NAMEPLATE_TEXT,
            TransformKind.DECORATED_STRING -> NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.styledNameCandidates)

            TransformKind.DECORATED_DISPLAY_STRING ->
                NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.nameplateDisplayCandidates)

            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_STRING ->
                NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.scoreboardStyledNameCandidates)

            TransformKind.SIDEBAR_TEXT ->
                NameStyleMatcher.containsCandidate(text, PlayerCustomizationRegistry.scoreboardGradientNameCandidates)
        }

        matchCache.putCached(cacheKey, result)
        return result
    }

    private fun runsToText(runs: List<StyledRun>): Text {
        val output = Component.empty()
        runs.forEach { run ->
            output.append(Component.literal(run.text).setStyle(run.style))
        }
        return output
    }

    private fun orderedTextSource(
        text: FormattedCharSequence,
        version: Long,
        bypassCache: Boolean = false,
    ): OrderedTextSourceLookup =
        OrderedTextStyleSupport.orderedTextSource(
            text = text,
            bypassCache = bypassCache,
            sourceCache = orderedTextSourceCache,
            onCacheHit = { incrementDebugCounter(debugCounters.sourceCacheHits) },
            onCacheMiss = { incrementDebugCounter(debugCounters.sourceCacheMisses) },
        )

    private fun orderedTextPlan(
        version: Long,
        runs: List<StyledRun>,
        plain: String,
        styleHash: Int,
        kind: TransformKind,
    ): OrderedTextTransformPlan? {
        val cacheKey = OrderedTextPlanCacheKey(version, kind, plain, styleHash)
        val cachedOrderedTextPlan = orderedTextPlanCache.getCached(cacheKey)
        if (cachedOrderedTextPlan != null) {
            incrementDebugCounter(debugCounters.planCacheHits)
            return cachedOrderedTextPlan.plan
        }
        incrementDebugCounter(debugCounters.planCacheMisses)
        incrementDebugCounter(debugCounters.buildPlanCalls)
        val plan = OrderedTextStyleSupport.buildOrderedTextPlan(
            runs = runs,
            plain = plain,
            kind = kind,
            candidatesForKind = ::candidatesForKind,
            resolveAnimatedGradientStyle = ::resolveAnimatedGradientStyle,
        )
        orderedTextPlanCache.putCached(cacheKey, OrderedTextPlanCacheValue(plan))
        return plan
    }

    private fun rebuildOrderedTextFromPlan(
        source: OrderedTextSourceData,
        plan: OrderedTextTransformPlan,
        includeBadges: Boolean = false,
        animationTime: Double = 0.0,
        replaceMatchedName: Boolean = false,
    ): Text =
        OrderedTextStyleSupport.rebuildOrderedTextFromPlan(
            source = source,
            plan = plan,
            includeBadges = includeBadges,
            animationTime = animationTime,
            replaceMatchedName = replaceMatchedName,
            cachedGradient = ::cachedGradient,
            styledMatchText = ::styledMatchText,
            appendBadge = ::appendBadge,
        )

    private fun styledMatchText(match: ResolvedOrderedMatch, animationTime: Double): Text {
        val effectiveBaseStyle = applyCustomNameStyle(match.baseStyle, match.customization)
        if (match.isAnimatedGradient) {
            val animatedStyle = match.animatedStyle ?: return Component.literal(match.content).setStyle(effectiveBaseStyle)
            return gradientText(match.content, animatedStyle, effectiveBaseStyle, animationTime)
        }
        return cachedGradient(match.content, match.customization, match.baseStyle)
    }

    private fun rebuildVisitable(
        message: FormattedText,
        includeBadges: Boolean = false,
        chatHeaderOnly: Boolean = false,
        terminalBadgesOnly: Boolean = false,
        replaceMatchedName: Boolean = false,
    ): Text {
        val rebuilt = Component.empty()
        var changed = false
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = OrderedTextStyleSupport.plainText(message)
        val headerBoundary = if (chatHeaderOnly) OrderedTextStyleSupport.chatHeaderBoundary(plain) else Int.MAX_VALUE
        val candidates = candidatesForVisitableTransform(includeBadges, replaceMatchedName)
        var visibleIndex = 0

        message.visit({ style, segment ->
            val segmentStart = visibleIndex
            val segmentEnd = segmentStart + segment.length
            visibleIndex = segmentEnd

            val decoratedLength = (headerBoundary - segmentStart).coerceIn(0, segment.length)
            val decoratedSegment = segment.substring(0, decoratedLength)
            val untouchedSegment = segment.substring(decoratedLength)

            if (decoratedSegment.isNotEmpty() && NameStyleMatcher.containsCandidate(decoratedSegment, candidates)) {
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
                rebuilt.append(Component.literal(decoratedSegment).setStyle(style))
            }

            if (untouchedSegment.isNotEmpty()) {
                rebuilt.append(Component.literal(untouchedSegment).setStyle(style))
            }
            LegacyMinecraftTextStyler.rememberStyledSegment(recentSegments, segment, style)
            Optional.empty<Unit>()
        }, Style.EMPTY)

        return if (changed) rebuilt else if (message is Text) message else rebuilt
    }

    private fun rebuildOrderedText(
        message: FormattedCharSequence,
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
        if (segments.none { styledSegment -> NameStyleMatcher.containsCandidate(styledSegment.second.toString(), candidates) }) {
            return null
        }

        val rebuilt = Component.empty()
        val recentSegments = ArrayDeque<StyledSegment>()
        val plain = segments.joinToString(separator = "") { styledSegment -> styledSegment.second.toString() }
        var visibleIndex = 0
        segments.forEach { (style, builder) ->
            val segment = builder.toString()
            if (NameStyleMatcher.containsCandidate(segment, candidates)) {
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
                rebuilt.append(Component.literal(segment).setStyle(style))
            }
            LegacyMinecraftTextStyler.rememberStyledSegment(recentSegments, segment, style)
            visibleIndex += segment.length
        }

        return if (changed) rebuilt else null
    }

    private fun rebuildGradientAcrossSegments(message: Text): Text? {
        val runs = OrderedTextStyleSupport.collectRuns(message)
        return rebuildGradientAcrossRuns(runs)
    }

    private fun rebuildGradientAcrossSegments(message: FormattedCharSequence): Text? {
        val runs = OrderedTextStyleSupport.collectRuns(message)
        return rebuildGradientAcrossRuns(runs)
    }

    private fun rebuildGradientAcrossRuns(runs: List<StyledRun>): Text? {
        val plain = OrderedTextStyleSupport.runsToPlain(runs)
        val candidates = PlayerCustomizationRegistry.gradientNameCandidates
        if (!NameStyleMatcher.containsCandidate(plain, candidates)) {
            return null
        }

        val rebuilt = Component.empty()
        var changed = false
        var index = 0

        while (index < plain.length) {
            val match = NameStyleMatcher.findFirstNameMatch(plain, candidates, index)

            if (match == null) {
                OrderedTextStyleSupport.appendOriginalRange(rebuilt, runs, index, plain.length)
                break
            }

            val nameMatch = match.nameMatch
            val customization = match.customization
            val matchIndex = nameMatch.index
            val matchedName = nameMatch.matchedName

            if (matchIndex > index) {
                OrderedTextStyleSupport.appendOriginalRange(rebuilt, runs, index, matchIndex)
            }

            val resolvedMatchedName = plain.substring(matchIndex, matchIndex + matchedName.length)
            rebuilt.append(cachedGradient(resolvedMatchedName, customization, OrderedTextStyleSupport.styleAt(runs, matchIndex)))
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
        val runs = OrderedTextStyleSupport.collectRuns(message)
        return rebuildDecorationsAcrossRuns(runs, includeBadges, terminalBadgesOnly, allowTruncatedPrefix, replaceMatchedName)
    }

    private fun rebuildDecorationsAcrossSegments(
        message: FormattedCharSequence,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean = false,
        allowTruncatedPrefix: Boolean = false,
        replaceMatchedName: Boolean = false,
    ): Text? {
        val runs = OrderedTextStyleSupport.collectRuns(message)
        return rebuildDecorationsAcrossRuns(runs, includeBadges, terminalBadgesOnly, allowTruncatedPrefix, replaceMatchedName)
    }

    private fun rebuildDecorationsAcrossRuns(
        runs: List<StyledRun>,
        includeBadges: Boolean,
        terminalBadgesOnly: Boolean,
        allowTruncatedPrefix: Boolean,
        replaceMatchedName: Boolean,
    ): Text? {
        val plain = OrderedTextStyleSupport.runsToPlain(runs)
        val candidates = when {
            allowTruncatedPrefix && replaceMatchedName -> PlayerCustomizationRegistry.scoreboardDisplayNameCandidates
            allowTruncatedPrefix && includeBadges -> PlayerCustomizationRegistry.scoreboardStyledNameCandidates
            allowTruncatedPrefix -> PlayerCustomizationRegistry.scoreboardGradientNameCandidates
            replaceMatchedName -> PlayerCustomizationRegistry.nameplateDisplayCandidates
            includeBadges -> PlayerCustomizationRegistry.styledNameCandidates
            else -> PlayerCustomizationRegistry.gradientNameCandidates
        }
        if (!NameStyleMatcher.containsCandidate(plain, candidates)) {
            return null
        }

        val rebuilt = Component.empty()
        var changed = false
        var index = 0

        while (index < plain.length) {
            val match = NameStyleMatcher.findFirstNameMatch(plain, candidates, index)

            if (match == null) {
                OrderedTextStyleSupport.appendOriginalRange(rebuilt, runs, index, plain.length)
                break
            }

            val nameMatch = match.nameMatch
            val customization = match.customization
            val matchIndex = nameMatch.index
            val matchedName = nameMatch.matchedName

            if (matchIndex > index) {
                OrderedTextStyleSupport.appendOriginalRange(rebuilt, runs, index, matchIndex)
            }

            val matchEnd = matchIndex + matchedName.length
            val resolvedMatchedName = plain.substring(matchIndex, matchEnd)
            val baseStyle = OrderedTextStyleSupport.styleAt(runs, matchIndex)
            val displayName = if (replaceMatchedName) customization.displayName(resolvedMatchedName) else resolvedMatchedName
            val styledName = when {
                replaceMatchedName || customization.hasNameCustomization() -> {
                    changed = true
                    cachedGradient(displayName, customization, baseStyle)
                }
                else -> OrderedTextStyleSupport.buildOriginalRangeText(runs, matchIndex, matchEnd)
            }

            val hasBadgeAlready = includeBadges && customization.nameBadge != null &&
                LegacyMinecraftTextStyler.hasPlainBadgeImmediatelyAfter(plain, matchEnd, customization.nameBadge.text)
            val hasTrailingContent = terminalBadgesOnly &&
                LegacyMinecraftTextStyler.hasPlainVisibleContentAfter(plain, matchEnd)
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
        target: MutableComponent,
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
            val match = NameStyleMatcher.findFirstNameMatch(remaining, candidates)

            if (match == null) {
                target.append(Component.literal(remaining).setStyle(style))
                return
            }

            val nameMatch = match.nameMatch
            val customization = match.customization
            val matchIndex = nameMatch.index
            val matchedName = nameMatch.matchedName

            if (matchIndex > 0) {
                target.append(Component.literal(remaining.substring(0, matchIndex)).setStyle(style))
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
                LegacyMinecraftTextStyler.hasPlainBadgeImmediatelyAfter(
                    plainText,
                    segmentStart + localOffset + matchIndex + matchedName.length,
                    customization.nameBadge.text,
                )
            val hasTrailingContent = terminalBadgesOnly &&
                LegacyMinecraftTextStyler.hasPlainVisibleContentAfter(
                    plainText,
                    segmentStart + localOffset + matchIndex + matchedName.length,
                )
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
            recentSegments.forEach { recentSegment -> append(recentSegment.text) }
            append(prefix)
        }
        return LegacyMinecraftTextStyler.inheritedLegacyRankStyle(rawPrefix, defaultStyle)
    }

    private fun appendBadge(
        nameText: Text,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        baseStyle: Style = Style.EMPTY,
    ): Text {
        val badge = customization.nameBadge ?: return nameText
        val output = nameText.copy()
        output.append(Component.literal(" ").setStyle(baseStyle))
        output.append(
            Component.literal(badge.text).setStyle(
                baseStyle
                    .withColor(badge.color)
                    .withBold(badge.bold),
            ),
        )
        return output
    }

    private fun hasNameCustomization(customization: PlayerCustomizationRegistry.PlayerCustomization): Boolean =
        customization.hasNameCustomization()

    private fun PlayerCustomizationRegistry.PlayerCustomization.hasAnimatedGradient(): Boolean =
        nameAnimated && nameColors?.let { gradientColors -> gradientColors.left != gradientColors.right } == true

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
            val match = NameStyleMatcher.findFirstNameMatch(text, candidates, index) ?: return false
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
        val gradientColors = customization.nameColors ?: return null
        if (!customization.hasAnimatedGradient()) {
            return null
        }

        val stepsCount = customization.nameAnimationSteps ?: animatedGradientSteps
        val speed = customization.nameAnimationSpeed ?: animatedGradientSpeed
        val gradientSpacing = gradientColors.spacing.coerceIn(1.0f, 10.0f)
        val cacheKey = AnimatedGradientCacheKey(
            leftColor = gradientColors.left,
            rightColor = gradientColors.right,
            stepsCount = stepsCount,
            speedBits = speed.toRawBits(),
            spacingBits = gradientSpacing.toRawBits(),
        )

        // Animated gradients are pure visual data: a precomputed list of colors plus
        // timing settings. Caching avoids rebuilding the same gradient definition every frame.
        return animatedGradientStyleCache.computeIfAbsent(cacheKey) {
            AnimatedGradientStyle(
                steps = AnimatedGradientStyle.buildLoopGradient(
                    gradientColors.left,
                    gradientColors.right,
                    stepsCount,
                ),
                speed = speed,
                spacing = gradientSpacing,
            )
        }
    }

    private fun cachedGradient(
        content: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
        baseStyle: Style = Style.EMPTY,
    ): Text {
        val effectiveBaseStyle = applyCustomNameStyle(baseStyle, customization)
        customization.nameColors?.let { gradientColors ->
            if (!customization.hasAnimatedGradient()) {
                return staticGradientText(content, gradientColors, effectiveBaseStyle)
            }

            val animatedStyle = resolveAnimatedGradientStyle(customization)
                ?: return Component.literal(content).setStyle(effectiveBaseStyle)
            logAnimatedRender(content, customization)
            return gradientText(content, animatedStyle, effectiveBaseStyle, currentAnimationTime())
        }

        val cacheKey = when {
            !customization.nameLetterColors.isNullOrEmpty() -> ColorizedCacheKey(
                content = content.lowercase(Locale.ROOT),
                kind = "multicolor",
                colors = customization.nameLetterColors,
            )

            else -> return Component.literal(content).setStyle(effectiveBaseStyle)
        }

        val cachedComponent = styledTextComponentCache.computeIfAbsent(cacheKey) {
            multiColorText(content, cacheKey.colors, Style.EMPTY)
        }

        if (effectiveBaseStyle.isEmpty) {
            return cachedComponent.copy()
        }

        val rebuiltText = Component.empty()
        cachedComponent.visit({ style, segment ->
            rebuiltText.append(Component.literal(segment).setStyle(style.withParent(effectiveBaseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return rebuiltText
    }

    private fun multiColorText(content: String, colors: List<Int>, baseStyle: Style): Text {
        val text = Component.empty()
        val fallback = colors.lastOrNull() ?: return Component.literal(content).setStyle(baseStyle)

        content.forEachIndexed { index, character ->
            val color = colors.getOrElse(index) { fallback }
            text.append(Component.literal(character.toString()).setStyle(baseStyle.withColor(color)))
        }

        return text
    }

    private fun staticGradientText(
        content: String,
        colors: PlayerCustomizationRegistry.NameColors,
        baseStyle: Style,
    ): Text {
        val cacheKey = ColorizedCacheKey(
            content = content.lowercase(Locale.ROOT),
            kind = "gradient:${colors.left}:${colors.right}:${colors.spacing}",
            colors = listOf(colors.left, colors.right),
        )
        val cachedComponent = styledTextComponentCache.computeIfAbsent(cacheKey) {
            val gradientText = Component.empty()
            val gradientSpacing = colors.spacing.coerceIn(1.0f, 10.0f)
            content.forEachIndexed { index, character ->
                val progress = GradientColorMath.gradientFrequencyProgress(index, content.length, gradientSpacing)
                val color = GradientColorMath.gradientLoopColor(colors.left, colors.right, progress)
                gradientText.append(Component.literal(character.toString()).setStyle(Style.EMPTY.withColor(color)))
            }
            gradientText
        }

        if (baseStyle.isEmpty) {
            return cachedComponent.copy()
        }

        val rebuiltText = Component.empty()
        cachedComponent.visit({ style, segment ->
            rebuiltText.append(Component.literal(segment).setStyle(style.withParent(baseStyle)))
            Optional.empty<Unit>()
        }, Style.EMPTY)
        return rebuiltText
    }

    private fun gradientText(
        content: String,
        animatedStyle: AnimatedGradientStyle,
        baseStyle: Style,
        time: Double,
    ): Text {
        val animatedGradientText = Component.empty()

        content.forEachIndexed { index, character ->
            val color = animatedStyle.getColor(index, content.length, time)
            animatedGradientText.append(Component.literal(character.toString()).setStyle(baseStyle.withColor(color)))
        }

        return animatedGradientText
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
                    append(LEGACY_MINECRAFT_FORMAT_CODE).append('l')
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
                GradientColorMath.gradientLoopColor(
                    gradientColors.left,
                    gradientColors.right,
                    GradientColorMath.gradientFrequencyProgress(index, content.length, frequency),
                )
            } else {
                animatedStyle!!.getColor(index, content.length, animationTime)
            }
            output.append(GradientColorMath.buildLegacyHexColorCode(color))
            if (customization.nameBold) {
                output.append(LEGACY_MINECRAFT_FORMAT_CODE).append('l')
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
        val client = Minecraft.getInstance()
        val world = client?.world ?: return 0.0
        return world.defaultClockTime.toDouble() + client.renderTickCounter.getTickProgress(true).toDouble()
    }

    private fun currentAnimationFrameIndex(animationTime: Double): Long? {
        if (forceAnimatedNameUncached) {
            return null
        }

        val client = Minecraft.getInstance()
        val visiblePlayers = client?.world?.players()?.size ?: 0
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

        PlayerListMod.logger.info(
            "AnimatedNameProfiler orderedTextCalls={} planHits={} planMisses={} " +
                "staticHits={} staticMisses={} frameHits={} frameMisses={} " +
                "sourceHits={} sourceMisses={} asOrderedText={} buildPlans={} " +
                "hudHits={} hudMisses={}",
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

    private fun logAnimatedRender(
        matchedName: String,
        customization: PlayerCustomizationRegistry.PlayerCustomization,
    ) {
        val renderPath = when (activeRenderPath.get()) {
            TransformKind.NAMEPLATE_TEXT, TransformKind.NAMEPLATE_DISPLAY_TEXT -> "nameplate"
            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_STRING,
            TransformKind.SCOREBOARD_DISPLAY_STRING,
            TransformKind.SIDEBAR_TEXT,
                -> "scoreboard"

            TransformKind.GRADIENT_TEXT, TransformKind.GRADIENT_STRING -> "text-renderer"
            TransformKind.CHAT_HEADER_TEXT -> "chat-header"
            TransformKind.DECORATED_STRING, TransformKind.DECORATED_DISPLAY_STRING -> "decorated-string"
            null -> "unknown"
        }
        val client = Minecraft.getInstance()
        val publicLobby = client != null && client.currentServerEntry != null && !client.isIntegratedServerRunning
        val speed = customization.nameAnimationSpeed ?: animatedGradientSpeed
        val logKey = "${customization.username.lowercase(Locale.ROOT)}|$renderPath|$publicLobby"
        if (!loggedAnimatedRenderPaths.add(logKey)) {
            return
        }

        PlayerListMod.logger.info(
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

        val match = plan?.matches?.firstOrNull { orderedMatch -> orderedMatch.isAnimatedGradient }
            ?: NameStyleMatcher.findFirstNameMatch(sourceText, candidatesForKind(kind))
                ?.takeIf { matchedCustomization -> matchedCustomization.customization.hasAnimatedGradient() }
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
        val world = Minecraft.getInstance().world
        val animationTime = currentAnimationTime()
        lastDebugTransform.set(
            DebugTransformMetadata(
                kind = kind.name,
                renderPath = currentRenderPathName(),
                matchedPlayer = match.customization.username,
                styleMode = if (match.isAnimatedGradient) "animated_gradient" else "static",
                animated = match.isAnimatedGradient,
                worldTime = world?.defaultClockTime ?: -1L,
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
            TransformKind.SCOREBOARD_TEXT,
            TransformKind.SCOREBOARD_DISPLAY_TEXT,
            TransformKind.SCOREBOARD_STRING,
            TransformKind.SCOREBOARD_DISPLAY_STRING,
            TransformKind.SIDEBAR_TEXT,
                -> "scoreboard"

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

        val client = Minecraft.getInstance()
        val world = client.world
        val visiblePlayers = world?.players()?.size ?: 0
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

        PlayerListMod.logger.info(
            "AnimatedNameDebug stage={} player='{}' lobbyType={} visiblePlayers={} " +
                "renderMethod={} styleMode={} animated={} worldTime={} animationTime={} " +
                "animationOffset={} finalOrderedTextCacheUsed={} sourceDataCacheUsed={} " +
                "receivedCachedText={} incomingHash={} outgoingHash={} outgoingIdentity={} " +
                "incoming='{}' outgoing='{}'",
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
        val client = Minecraft.getInstance()
        val self = client.player?.gameProfile?.name?.lowercase(Locale.ROOT)
        if (playerName.lowercase(Locale.ROOT) == self) {
            return true
        }

        val otherAnimated = client.world?.players()
            ?.asSequence()
            ?.mapNotNull { player ->
                PlayerCustomizationRegistry.find(player.gameProfile)
                    ?.takeIf { customization -> customization.hasAnimatedGradient() }
                    ?.username
            }
            ?.firstOrNull { animatedUsername -> !animatedUsername.equals(self, ignoreCase = true) }
            ?.lowercase(Locale.ROOT)
        return playerName.lowercase(Locale.ROOT) == otherAnimated
    }

}

