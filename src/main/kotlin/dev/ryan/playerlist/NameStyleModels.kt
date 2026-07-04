package dev.ryan.playerlist

import net.minecraft.network.chat.Style
import java.util.concurrent.atomic.LongAdder

internal enum class TransformKind {
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

internal data class ColorizedCacheKey(
    val content: String,
    val kind: String,
    val colors: List<Int>,
)

internal data class AnimatedGradientCacheKey(
    val leftColor: Int,
    val rightColor: Int,
    val stepsCount: Int,
    val speedBits: Int,
    val spacingBits: Int,
)

internal data class StyledSegment(
    val text: String,
    val style: Style,
)

internal data class StyledRun(
    val start: Int,
    val end: Int,
    val text: String,
    val style: Style,
)

internal data class ResolvedOrderedMatch(
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

internal data class OrderedTextTransformPlan(
    val matches: List<ResolvedOrderedMatch>,
    val hasAnimatedGradient: Boolean,
)

internal data class OrderedTextSourceData(
    val plain: String,
    val characterCount: Int,
    val runs: List<StyledRun>,
    val styleHash: Int,
)

internal data class OrderedTextSourceLookup(
    val data: OrderedTextSourceData,
    val cacheUsed: Boolean,
)

internal data class OrderedTextPlanCacheKey(
    val version: Long,
    val kind: TransformKind,
    val plain: String,
    val styleHash: Int,
)

internal data class OrderedTextPlanCacheValue(
    val plan: OrderedTextTransformPlan?,
)

internal data class OrderedTextResultCacheKey(
    val version: Long,
    val kind: TransformKind,
    val plain: String,
    val styleHash: Int,
)

internal data class OrderedTextAnimatedFrameCacheKey(
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

internal data class NameMatch(
    val index: Int,
    val matchedName: String,
)

internal data class MatchedCustomization(
    val nameMatch: NameMatch,
    val customization: PlayerCustomizationRegistry.PlayerCustomization,
)

internal data class TextCacheKey(
    val version: Long,
    val kind: TransformKind,
    val plain: String,
    val styleHash: Int,
)

internal data class StringCacheKey(
    val version: Long,
    val kind: TransformKind,
    val raw: String,
)

internal data class MatchCacheKey(
    val version: Long,
    val kind: TransformKind,
    val raw: String,
)

internal data class SelfNameCacheKey(
    val version: Long,
    val name: String,
)

internal class DebugCounters {
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
