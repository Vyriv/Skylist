package dev.ryan.playerlist

import com.google.gson.GsonBuilder
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture

object ContentManager {
    private val gson = GsonBuilder().create()
    private const val playerWidthBlocks = 0.6f
    private const val playerHeightBlocks = 1.8f
    private const val playerDepthBlocks = 0.6f

    private const val peopleResourcePath = "playerlist/content/people.json"
    private const val reasonsResourcePath = "playerlist/content/reasons.json"
    private const val uiTextResourcePath = "playerlist/content/ui_text.json"
    private const val remotePeopleUrl = PlayerListLinks.skylistCosmeticsDataUrl
    private val fallbackTrollButtonMessages = listOf("Don't Press me")
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val hardcodedDeveloperCredits = listOf(
        LoadedDeveloperCredit(
            username = "ChaseCatlantic",
            label = "Vyriv",
            role = "Lead developer",
            leftColor = 0x9278C5,
            rightColor = 0xF3E6FD,
        ),
    )

    @Volatile
    private var peopleContent = PeopleContent()

    @Volatile
    private var remotePeopleContent = PeopleContent()

    @Volatile
    private var reasonContent = ReasonContent()

    @Volatile
    private var uiTextContent = UiTextContent(
        trollButtonMessages = fallbackTrollButtonMessages.toMutableList(),
    )

    @Synchronized
    fun load() {
        peopleContent = loadResource(
            resourcePath = peopleResourcePath,
            clazz = PeopleContent::class.java,
            label = "bundled player customization data",
            fallback = PeopleContent(),
        ).normalized()
        remotePeopleContent = PeopleContent()
        reasonContent = loadResource(
            resourcePath = reasonsResourcePath,
            clazz = ReasonContent::class.java,
            label = "reason suggestions",
            fallback = ReasonContent(),
        ).normalized()
        uiTextContent = loadResource(
            resourcePath = uiTextResourcePath,
            clazz = UiTextContent::class.java,
            label = "ui text",
            fallback = UiTextContent(
                trollButtonMessages = fallbackTrollButtonMessages.toMutableList(),
            ),
        ).normalized()
    }

    fun fetchRemotePeopleOnStartup(): CompletableFuture<Unit> =
        refreshRemotePeopleNow(logPrefix = "startup")

    fun refreshRemotePeopleNow(logPrefix: String = "manual"): CompletableFuture<Unit> =
        CompletableFuture.runAsync {
            runCatching { fetchRemotePeopleContent() }
                .onSuccess { remote ->
                    if (remote == null) {
                        return@runAsync
                    }

                    remotePeopleContent = remote.normalized()
                    PlayerListMod.logger.info(
                        "Loaded {} remote cosmetic player entries from {} refresh",
                        remotePeopleContent.players.size + remotePeopleContent.awesomePeople.size,
                        logPrefix,
                    )
                    PlayerListMod.client.execute {
                        CapeTextureManager.invalidateRemoteCapes()
                        PlayerCustomizationRegistry.initialize()
                    }
                }
                .onFailure {
                    PlayerListMod.logger.warn("Failed to fetch {} cosmetic player data", logPrefix, it)
                }
        }.thenApply { Unit }

    @Synchronized
    fun playerCustomizations(): List<LoadedPlayerCustomization> =
        mergedCustomizationEntries().mapNotNull(::loadPlayerCustomization)

    @Synchronized
    fun developerCredits(): List<LoadedDeveloperCredit> =
        hardcodedDeveloperCredits

    @Synchronized
    fun betaTesterCredits(): List<LoadedCreditEntry> =
        mergedCustomizationEntries().mapNotNull(::loadCreditEntry)

    @Synchronized
    fun isProtectedCreditUsername(username: String): Boolean {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isEmpty()) {
            return false
        }

        return developerCredits().any { it.username.equals(normalizedUsername, ignoreCase = true) } ||
            betaTesterCredits().any { it.username.equals(normalizedUsername, ignoreCase = true) }
    }

    @Synchronized
    fun commonReasons(): List<String> = reasonContent.commonReasons

    @Synchronized
    fun trollButtonMessages(): List<String> =
        uiTextContent.trollButtonMessages.ifEmpty { fallbackTrollButtonMessages }

    private fun loadPlayerCustomization(entry: PlayerCustomizationFile): LoadedPlayerCustomization? {
        val nameStyle = loadNameStyle(entry.style)
        val badge = entry.badge?.let(::loadBadge)
        val resolvedScale = entry.scale
        val resolvedWidthBlocks = entry.widthBlocks
        val resolvedDepthBlocks = entry.depthBlocks ?: resolvedWidthBlocks
        val resolvedScaleX = entry.scaleX ?: resolvedWidthBlocks?.div(playerWidthBlocks)
        val resolvedScaleY = entry.scaleY ?: entry.heightBlocks?.div(playerHeightBlocks)
        val resolvedScaleZ = entry.scaleZ ?: resolvedDepthBlocks?.div(playerDepthBlocks)
        return LoadedPlayerCustomization(
            username = entry.username,
            nickname = entry.nickname?.trim()?.takeIf { it.isNotEmpty() },
            uuid = entry.uuid?.trim()?.takeIf { it.isNotEmpty() },
            aliases = entry.aliases,
            style = nameStyle,
            badge = badge,
            capeResourcePath = entry.capeResourcePath?.trim()?.takeIf { it.isNotEmpty() },
            capeUrl = entry.capeUrl?.trim()?.takeIf { it.isNotEmpty() },
            scale = resolvedScale,
            scaleX = resolvedScaleX,
            scaleY = resolvedScaleY,
            scaleZ = resolvedScaleZ,
        )
    }

    private fun loadCreditEntry(entry: PlayerCustomizationFile): LoadedCreditEntry? {
        val role = entry.creditRole?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val resolvedUsername = resolvedCreditUsername(entry)
        return LoadedCreditEntry(
            username = resolvedUsername,
            label = entry.creditLabel?.trim()?.takeIf { it.isNotEmpty() } ?: resolvedCreditUsername(entry),
            role = role,
        )
    }

    private fun resolvedCreditUsername(entry: PlayerCustomizationFile): String =
        entry.username

    private fun loadNameStyle(style: NameStyleFile?): LoadedNameStyle? {
        if (style == null) {
            return null
        }

        val normalizedMode = style.mode.trim().lowercase()
        val animationSpeed = style.animationSpeed?.takeIf { it > 0f }
        val animationSteps = style.animationSteps?.coerceAtLeast(2)
        val gradientSpacing = (style.gradientFrequency ?: style.gradientSpacing)?.coerceIn(1.0f, 10.0f) ?: 1.0f
        val animated = style.animated == true

        return when (normalizedMode) {
            "inherit_rank", "inherit-rank", "inherit" ->
                LoadedNameStyle(LoadedNameStyle.Mode.INHERIT_RANK, bold = style.bold)

            "solid" -> {
                val color = parseColor(style.color ?: style.leftColor ?: style.rightColor) ?: return null
                LoadedNameStyle(LoadedNameStyle.Mode.SOLID, color, color, style.bold)
            }

            "gradient" -> {
                val left = parseColor(style.leftColor ?: style.color) ?: return null
                val right = parseColor(style.rightColor ?: style.color) ?: return null
                LoadedNameStyle(
                    mode = if (animated || animationSpeed != null) LoadedNameStyle.Mode.ANIMATED_GRADIENT else LoadedNameStyle.Mode.GRADIENT,
                    leftColor = left,
                    rightColor = right,
                    bold = style.bold,
                    animationSpeed = animationSpeed,
                    animationSteps = animationSteps,
                    gradientSpacing = gradientSpacing,
                )
            }

            "animated_gradient", "animated-gradient", "animatedgradient" -> {
                val left = parseColor(style.leftColor ?: style.color) ?: return null
                val right = parseColor(style.rightColor ?: style.color) ?: return null
                LoadedNameStyle(
                    mode = LoadedNameStyle.Mode.ANIMATED_GRADIENT,
                    leftColor = left,
                    rightColor = right,
                    bold = style.bold,
                    animationSpeed = animationSpeed,
                    animationSteps = animationSteps,
                    gradientSpacing = gradientSpacing,
                )
            }

            "multicolor" -> {
                val letterColors = style.letterColors
                    ?.mapNotNull(::parseColor)
                    ?.takeIf { it.isNotEmpty() }
                    ?: return null
                LoadedNameStyle(
                    mode = LoadedNameStyle.Mode.MULTICOLOR,
                    leftColor = letterColors.first(),
                    rightColor = letterColors.last(),
                    bold = style.bold,
                    letterColors = letterColors,
                )
            }

            else -> null
        }
    }

    private fun loadBadge(badge: BadgeFile): LoadedBadge? {
        val text = badge.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val color = parseColor(badge.color) ?: return null
        return LoadedBadge(text, color, badge.bold)
    }

    private fun parseColor(raw: String?): Int? {
        val normalized = raw?.trim()?.removePrefix("#") ?: return null
        return when (normalized.length) {
            6 -> normalized.toIntOrNull(16)?.and(0xFFFFFF)
            8 -> normalized.toLongOrNull(16)?.toInt()?.and(0xFFFFFF)
            else -> null
        }
    }

    private fun <T : Any> loadResource(
        resourcePath: String,
        clazz: Class<T>,
        label: String,
        fallback: T,
    ): T {
        val stream = ContentManager::class.java.classLoader.getResourceAsStream(resourcePath)
        if (stream == null) {
            PlayerListMod.logger.error("Missing bundled $label resource: {}", resourcePath)
            return fallback
        }

        return stream.use { input ->
            runCatching {
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    gson.fromJson(reader, clazz) ?: fallback
                }
            }.getOrElse {
                PlayerListMod.logger.error("Failed to load bundled $label resource: {}", resourcePath, it)
                fallback
            }
        }
    }

    private fun PeopleContent.normalized(): PeopleContent =
        copy(
            awesomePeople = awesomePeople.mapNotNull { it.normalized() }.toMutableList(),
            players = players.mapNotNull { it.normalized() }.toMutableList(),
        )

    private fun mergedCustomizationEntries(): List<PlayerCustomizationFile> {
        val orderedKeys = linkedSetOf<String>()
        val mergedEntries = linkedMapOf<String, PlayerCustomizationFile>()

        listOf(
            peopleContent.players,
            peopleContent.awesomePeople,
            remotePeopleContent.players,
            remotePeopleContent.awesomePeople,
        ).forEach { section ->
            section.forEach { entry ->
                val key = existingCustomizationKey(entry, mergedEntries) ?: entry.identityKey() ?: return@forEach
                orderedKeys.add(key)
                mergedEntries[key] = mergedEntries[key]?.mergedWith(entry) ?: entry
            }
        }

        return orderedKeys.mapNotNull(mergedEntries::get)
    }

    private fun existingCustomizationKey(
        entry: PlayerCustomizationFile,
        mergedEntries: Map<String, PlayerCustomizationFile>,
    ): String? {
        val entryUuid = entry.uuid?.trim()?.takeIf { it.isNotEmpty() }
        val entryUsername = entry.username.trim().takeIf { it.isNotEmpty() }

        return mergedEntries.entries.firstOrNull { (_, existing) ->
            val existingUuid = existing.uuid?.trim()?.takeIf { it.isNotEmpty() }
            val existingUsername = existing.username.trim().takeIf { it.isNotEmpty() }
            (entryUuid != null && existingUuid != null && entryUuid.equals(existingUuid, ignoreCase = true)) ||
                (entryUsername != null && existingUsername != null && entryUsername.equals(existingUsername, ignoreCase = true))
        }?.key
    }

    private fun PlayerCustomizationFile.mergedWith(overlay: PlayerCustomizationFile): PlayerCustomizationFile {
        val resolvedUsername = if (overlay.username.isNotBlank()) overlay.username else username
        val mergedAliases = buildList {
            aliases.forEach(::add)
            overlay.aliases.forEach(::add)
        }.map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(resolvedUsername, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .toMutableList()

        return copy(
            username = resolvedUsername,
            uuid = overlay.uuid ?: uuid,
            aliases = mergedAliases,
            creditLabel = overlay.creditLabel ?: creditLabel,
            creditRole = overlay.creditRole ?: creditRole,
            style = overlay.style ?: style,
            badge = overlay.badge ?: badge,
            capeResourcePath = overlay.capeResourcePath ?: capeResourcePath,
            capeUrl = overlay.capeUrl ?: capeUrl,
            scale = overlay.scale ?: scale,
            scaleX = overlay.scaleX ?: scaleX,
            scaleY = overlay.scaleY ?: scaleY,
            scaleZ = overlay.scaleZ ?: scaleZ,
            widthBlocks = overlay.widthBlocks ?: widthBlocks,
            heightBlocks = overlay.heightBlocks ?: heightBlocks,
            depthBlocks = overlay.depthBlocks ?: depthBlocks,
        )
    }

    private fun PlayerCustomizationFile.normalized(): PlayerCustomizationFile? {
        val normalizedUsername = username.trim()
        val normalizedUuid = uuid?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedUsername.isEmpty() && normalizedUuid == null) {
            return null
        }

        val normalizedAliases = aliases
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(normalizedUsername, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .toMutableList()

        return copy(
            username = normalizedUsername,
            uuid = normalizedUuid,
            aliases = normalizedAliases,
            creditLabel = creditLabel?.trim()?.takeIf { it.isNotEmpty() },
            creditRole = creditRole?.trim()?.takeIf { it.isNotEmpty() },
            style = style?.normalized(),
            badge = badge?.normalized(),
            capeResourcePath = capeResourcePath?.trim()?.takeIf { it.isNotEmpty() },
            capeUrl = capeUrl?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun PlayerCustomizationFile.identityKey(): String? =
        uuid?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: username.trim().takeIf { it.isNotEmpty() }?.lowercase()

    private fun NameStyleFile.normalized(): NameStyleFile =
        copy(
            mode = mode.trim().ifEmpty { "inherit_rank" },
            color = color?.trim()?.takeIf { it.isNotEmpty() },
            leftColor = leftColor?.trim()?.takeIf { it.isNotEmpty() },
            rightColor = rightColor?.trim()?.takeIf { it.isNotEmpty() },
            letterColors = letterColors
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toMutableList()
                ?.takeIf { it.isNotEmpty() },
        )

    private fun BadgeFile.normalized(): BadgeFile =
        copy(
            text = text?.trim()?.takeIf { it.isNotEmpty() },
            color = color?.trim()?.takeIf { it.isNotEmpty() },
        )

    private fun ReasonContent.normalized(): ReasonContent =
        copy(
            commonReasons = commonReasons
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .toMutableList(),
        )

    private fun UiTextContent.normalized(): UiTextContent =
        copy(
            trollButtonMessages = trollButtonMessages
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toMutableList(),
        )

    data class LoadedPlayerCustomization(
        val username: String,
        val nickname: String?,
        val uuid: String?,
        val aliases: List<String>,
        val style: LoadedNameStyle?,
        val badge: LoadedBadge?,
        val capeResourcePath: String?,
        val capeUrl: String?,
        val scale: Float?,
        val scaleX: Float?,
        val scaleY: Float?,
        val scaleZ: Float?,
    )

    data class LoadedNameStyle(
        val mode: Mode,
        val leftColor: Int? = null,
        val rightColor: Int? = null,
        val bold: Boolean = false,
        val letterColors: List<Int>? = null,
        val animationSpeed: Float? = null,
        val animationSteps: Int? = null,
        val gradientSpacing: Float = 1.0f,
    ) {
        enum class Mode {
            INHERIT_RANK,
            SOLID,
            GRADIENT,
            ANIMATED_GRADIENT,
            MULTICOLOR,
        }
    }

    data class LoadedBadge(
        val text: String,
        val color: Int,
        val bold: Boolean,
    )

    data class LoadedDeveloperCredit(
        val username: String,
        val label: String,
        val role: String,
        val leftColor: Int,
        val rightColor: Int,
    )

    data class LoadedCreditEntry(
        val username: String,
        val label: String,
        val role: String,
    )

    data class PeopleContent(
        var awesomePeople: MutableList<PlayerCustomizationFile> = mutableListOf(),
        var players: MutableList<PlayerCustomizationFile> = mutableListOf(),
    )

    data class PlayerCustomizationFile(
        var username: String = "",
        var nickname: String? = null,
        var uuid: String? = null,
        var aliases: MutableList<String> = mutableListOf(),
        var creditLabel: String? = null,
        var creditRole: String? = null,
        var style: NameStyleFile? = null,
        var badge: BadgeFile? = null,
        var capeResourcePath: String? = null,
        var capeUrl: String? = null,
        var scale: Float? = null,
        var scaleX: Float? = null,
        var scaleY: Float? = null,
        var scaleZ: Float? = null,
        var widthBlocks: Float? = null,
        var heightBlocks: Float? = null,
        var depthBlocks: Float? = null,
    )

    data class NameStyleFile(
        var mode: String = "inherit_rank",
        var color: String? = null,
        var leftColor: String? = null,
        var rightColor: String? = null,
        var letterColors: MutableList<String>? = null,
        var animated: Boolean? = null,
        var animationSpeed: Float? = null,
        var animationSteps: Int? = null,
        var gradientSpacing: Float? = null,
        var gradientFrequency: Float? = null,
        var bold: Boolean = false,
    )

    data class BadgeFile(
        var text: String? = null,
        var color: String? = null,
        var bold: Boolean = false,
    )

    data class ReasonContent(
        var commonReasons: MutableList<String> = mutableListOf(),
    )

    data class UiTextContent(
        var trollButtonMessages: MutableList<String> = mutableListOf(),
    )

    private fun fetchRemotePeopleContent(): PeopleContent? {
        val requestUri = URI.create(
            if (remotePeopleUrl.contains('?')) {
                "$remotePeopleUrl&ts=${System.currentTimeMillis()}"
            } else {
                "$remotePeopleUrl?ts=${System.currentTimeMillis()}"
            },
        )
        val request = HttpRequest.newBuilder()
            .uri(requestUri)
            .timeout(Duration.ofSeconds(10))
            .header("accept", "application/json,*/*")
            .header("cache-control", "no-cache, no-store, max-age=0")
            .header("pragma", "no-cache")
            .GET()
            .build()

        // This request downloads only the public cosmetics JSON payload used to style
        // names, capes, and credits. It does not read local files and it does not send
        // any player account, session, Discord, or token data.
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Unexpected response ${response.statusCode()} from $requestUri")
        }

        val body = response.body().trim()
        val root = runCatching { gson.fromJson(body, JsonHostingEnvelope::class.java) }.getOrNull()
        // content can be either a nested object or a JSON string
        val contentObj = root?.content
        return if (contentObj != null) {
            gson.fromJson(gson.toJson(contentObj), PeopleContent::class.java)
        } else {
            gson.fromJson(body, PeopleContent::class.java)
        }
    }

    data class JsonHostingEnvelope(
        val id: String? = null,
        val content: Any? = null,
        val size: Long? = null,
        val createdAt: String? = null,
        val updatedAt: String? = null,
    )
}
