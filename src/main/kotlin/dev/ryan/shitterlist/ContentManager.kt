package dev.ryan.throwerlist

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

    private const val reasonsResourcePath = "throwerlist/content/reasons.json"
    private const val uiTextResourcePath = "throwerlist/content/ui_text.json"
    private const val remotePeopleUrl = "https://jsonhosting.com/api/json/35baa423"
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
            linkUrl = "https://discord.com/users/714135340268519466",
            linkHover = "Open Vyriv's discord",
        ),
    )
    private val hardcodedBetaTesterEntries = listOf(
        betaTesterEntry("Tomato_XD", "9a3335fd-4b23-4618-af6c-decff24004f9", "Tomato"),
        betaTesterEntry("H3ro123", "a4931b9d-339c-4f70-8f89-4809606d315a", "Hero"),
        betaTesterEntry("JuniorWasTaken", "3126eab6-c532-486c-ba8a-4ef6f84f633c", "Junior"),
        betaTesterEntry("BulkyHyperion", "b1a1f254-d07a-4e8f-be27-56df51776e58", "Bulky"),
        betaTesterEntry("fifiboyuu", "1cd842b5-b804-46db-9728-2ce54f787f78", "fifi"),
        betaTesterEntry("Outfixes", "e0b84780-1553-40d6-a9ed-c43a510a5340", "Outfixed", aliases = listOf("Outfixed")),
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
        peopleContent = PeopleContent()
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
                    ThrowerListMod.logger.info(
                        "Loaded {} remote cosmetic player entries from {} API refresh",
                        remotePeopleContent.players.size + remotePeopleContent.awesomePeople.size,
                        logPrefix,
                    )
                    ThrowerListMod.client.execute {
                        CapeTextureManager.invalidateRemoteCapes()
                        PlayerCustomizationRegistry.initialize()
                    }
                }
                .onFailure {
                    ThrowerListMod.logger.warn("Failed to fetch {} cosmetic player API", logPrefix, it)
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
        hardcodedBetaTesterEntries.mapNotNull(::loadCreditEntry)

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
        return LoadedPlayerCustomization(
            username = entry.username,
            uuid = entry.uuid?.trim()?.takeIf { it.isNotEmpty() },
            aliases = entry.aliases,
            style = nameStyle,
            badge = badge,
            capeResourcePath = entry.capeResourcePath?.trim()?.takeIf { it.isNotEmpty() },
            capeUrl = entry.capeUrl?.trim()?.takeIf { it.isNotEmpty() },
            scale = entry.scale,
            scaleX = entry.scaleX,
            scaleY = entry.scaleY,
            scaleZ = entry.scaleZ,
        )
    }

    private fun loadCreditEntry(entry: PlayerCustomizationFile): LoadedCreditEntry? {
        val role = entry.creditRole?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return LoadedCreditEntry(
            username = entry.username,
            label = entry.creditLabel?.trim()?.takeIf { it.isNotEmpty() } ?: entry.username,
            role = role,
        )
    }

    private fun loadNameStyle(style: NameStyleFile?): LoadedNameStyle? {
        if (style == null) {
            return null
        }

        return when (style.mode.trim().lowercase()) {
            "inherit_rank", "inherit-rank", "inherit" ->
                LoadedNameStyle(LoadedNameStyle.Mode.INHERIT_RANK)

            "solid" -> {
                val color = parseColor(style.color ?: style.leftColor ?: style.rightColor) ?: return null
                LoadedNameStyle(LoadedNameStyle.Mode.SOLID, color, color)
            }

            "gradient" -> {
                val left = parseColor(style.leftColor ?: style.color) ?: return null
                val right = parseColor(style.rightColor ?: style.color) ?: return null
                LoadedNameStyle(LoadedNameStyle.Mode.GRADIENT, left, right)
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
            ThrowerListMod.logger.error("Missing bundled $label resource: {}", resourcePath)
            return fallback
        }

        return stream.use { input ->
            runCatching {
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    gson.fromJson(reader, clazz) ?: fallback
                }
            }.getOrElse {
                ThrowerListMod.logger.error("Failed to load bundled $label resource: {}", resourcePath, it)
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

        listOf(remotePeopleContent.players, remotePeopleContent.awesomePeople).forEach { section ->
            section.forEach { entry ->
                val key = entry.identityKey() ?: return@forEach
                orderedKeys.add(key)
                mergedEntries[key] = mergedEntries[key]?.mergedWith(entry) ?: entry
            }
        }

        return orderedKeys.mapNotNull(mergedEntries::get)
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
    ) {
        enum class Mode {
            INHERIT_RANK,
            SOLID,
            GRADIENT,
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
        val linkUrl: String?,
        val linkHover: String?,
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
    )

    data class NameStyleFile(
        var mode: String = "inherit_rank",
        var color: String? = null,
        var leftColor: String? = null,
        var rightColor: String? = null,
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
        val request = HttpRequest.newBuilder()
            .uri(URI.create(remotePeopleUrl))
            .timeout(Duration.ofSeconds(10))
            .header("accept", "application/json,*/*")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            error("Unexpected response ${response.statusCode()} from $remotePeopleUrl")
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

    private fun betaTesterEntry(
        username: String,
        uuid: String,
        label: String,
        aliases: List<String> = emptyList(),
    ) = PlayerCustomizationFile(
        username = username,
        uuid = uuid,
        aliases = aliases.toMutableList(),
        creditLabel = label,
        creditRole = "Beta tester",
        style = NameStyleFile(mode = "inherit_rank"),
        badge = BadgeFile(text = "\u263B", color = "#FFFF55"),
        capeResourcePath = "capes/beta_testers.png",
    )
}
