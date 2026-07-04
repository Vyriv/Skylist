package dev.ryan.playerlist

import com.google.gson.JsonParser
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture

object GitHubUpdateChecker {
    private const val requestTimeoutSeconds = 10L
    private const val cacheDurationMillis = 15 * 60 * 1000L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var cachedUpdate: UpdateInfo? = null

    @Volatile
    private var lastCheckedAt = 0L

    @Volatile
    private var hasNotifiedThisSession = false

    fun register() {
        ClientPlayConnectionEvents.JOIN.register(::onJoin)
    }

    fun latestKnownVersionForCurrentMinecraft(): String? {
        val minecraftVersion = RuntimeVersion.minecraftVersion()
        return cachedUpdate?.takeIf { it.minecraftVersion == minecraftVersion }?.latestVersion
    }

    fun showUpdateStatus(source: FabricClientCommandSource) {
        latestUpdateAsync(forceRefresh = true)
            .whenComplete { update, throwable ->
                PlayerListMod.client.execute {
                    if (throwable != null) {
                        PlayerListMod.logger.warn("Failed to fetch latest update info", throwable)
                        source.sendError(tlMessage("Could not check for updates right now."))
                        return@execute
                    }

                    if (update == null) {
                        source.sendError(tlMessage("No compatible update was found for this Minecraft version."))
                        return@execute
                    }

                    if (compareVersions(RuntimeVersion.featureVersion(), update.releaseTag) >= 0) {
                        source.sendFeedback(
                            tlMessage(
                                Component.literal("You already have the latest version installed.")
                                    .formatted(ChatFormatting.GREEN),
                            ),
                        )
                        return@execute
                    }

                    source.sendFeedback(
                        buildManualUpdateMessage(
                            currentVersion = RuntimeVersion.currentVersion(),
                            latestVersion = update.latestVersion,
                            releaseUrl = update.releaseUrl,
                        ),
                    )
                }
            }
    }

    private fun onJoin(
        handler: net.minecraft.client.multiplayer.ClientPacketListener,
        sender: net.fabricmc.fabric.api.networking.v1.PacketSender,
        client: Minecraft,
    ) {
        val now = System.currentTimeMillis()
        val cached = cachedUpdate
        if (cached != null && now - lastCheckedAt < cacheDurationMillis) {
            notifyIfOutdated(client, cached)
            return
        }

        latestUpdateAsync(forceRefresh = false)
            .thenAccept { latest ->
                if (latest == null) {
                    return@thenAccept
                }

                client.execute {
                    notifyIfOutdated(client, latest)
                }
            }
    }

    private fun notifyIfOutdated(client: Minecraft, update: UpdateInfo) {
        if (hasNotifiedThisSession) {
            return
        }

        if (update.minecraftVersion != RuntimeVersion.minecraftVersion()) {
            PlayerListMod.logger.info(
                "Minecraft version mismatch during update check: current={}, release={}",
                RuntimeVersion.minecraftVersion(),
                update.minecraftVersion,
            )
            return
        }

        if (compareVersions(RuntimeVersion.featureVersion(), update.releaseTag) >= 0) {
            return
        }

        client.player?.sendMessage(
            buildWarningMessage(
                currentVersion = RuntimeVersion.currentVersion(),
                latestVersion = update.latestVersion,
                releaseUrl = update.releaseUrl,
            ),
            false,
        )
        hasNotifiedThisSession = true
    }

    private fun latestUpdateAsync(forceRefresh: Boolean): CompletableFuture<UpdateInfo?> =
        CompletableFuture.supplyAsync {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = cachedUpdate
                if (cached != null && now - lastCheckedAt < cacheDurationMillis) {
                    return@supplyAsync cached
                }
            }

            fetchUpdateInfo()?.also {
                cachedUpdate = it
                lastCheckedAt = System.currentTimeMillis()
            }
        }

    private fun fetchUpdateInfo(): UpdateInfo? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(PlayerListLinks.githubLatestReleaseApi))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Skylist")
            .GET()
            .build()

        return runCatching {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                error("Unexpected response ${response.statusCode()}")
            }

            parseLatestRelease(response.body())
        }.onFailure {
            PlayerListMod.logger.warn("Failed to check Skylist GitHub releases", it)
        }.getOrNull()
    }

    private fun parseLatestRelease(body: String): UpdateInfo? {
        val parsed = JsonParser.parseString(body).asJsonObject
        val releaseTag = parsed.get("tag_name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val releaseUrl = parsed.get("html_url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (releaseTag.isEmpty()) {
            return null
        }

        val cleanTag = releaseTag.removePrefix("v").trim()
        val minecraftVersion = RuntimeVersion.minecraftVersion()
        val matchingAssetName = parsed.getAsJsonArray("assets")
            ?.mapNotNull { entry -> entry.takeIf { it.isJsonObject }?.asJsonObject }
            ?.mapNotNull { entry ->
                entry.get("name")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
            }
            ?.firstOrNull { assetName ->
                (assetName.startsWith("skylist-$cleanTag-") || assetName.startsWith("skylist-$releaseTag-")) &&
                    assetName.endsWith("-$minecraftVersion.jar")
            }
            ?: return null

        return UpdateInfo(
            releaseTag = cleanTag,
            latestVersion = matchingAssetName.removeSuffix(".jar").removePrefix("skylist-"),
            minecraftVersion = minecraftVersion,
            releaseUrl = releaseUrl.ifEmpty { PlayerListLinks.githubLatestReleaseUrl },
        )
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = Regex("""\d+""").findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rightParts = Regex("""\d+""").findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftValue = leftParts.getOrElse(index) { 0 }
            val rightValue = rightParts.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return left.compareTo(right)
    }

    private fun buildWarningMessage(
        currentVersion: String,
        latestVersion: String,
        releaseUrl: String,
    ): MutableComponent =
        Component.empty()
            .append(Component.literal("[SL] ").formatted(ChatFormatting.AQUA))
            .append(Component.literal("New version available. ").formatted(ChatFormatting.GREEN))
            .append(Component.literal("Current: ").formatted(ChatFormatting.GREEN))
            .append(Component.literal(currentVersion).formatted(ChatFormatting.YELLOW))
            .append(Component.literal(" Latest: ").formatted(ChatFormatting.GREEN))
            .append(Component.literal(latestVersion).formatted(ChatFormatting.YELLOW))
            .append(Component.literal(" "))
            .append(releasePageButton(releaseUrl))

    private fun buildManualUpdateMessage(
        currentVersion: String,
        latestVersion: String,
        releaseUrl: String,
    ): MutableComponent =
        Component.empty()
            .append(Component.literal("[SL] ").formatted(ChatFormatting.AQUA))
            .append(Component.literal("Skylist update available. ").formatted(ChatFormatting.GREEN))
            .append(Component.literal("Current: ").formatted(ChatFormatting.GREEN))
            .append(Component.literal(currentVersion).formatted(ChatFormatting.YELLOW))
            .append(Component.literal(" Latest: ").formatted(ChatFormatting.GREEN))
            .append(Component.literal(latestVersion).formatted(ChatFormatting.YELLOW))
            .append(Component.literal(" "))
            .append(releasePageButton(releaseUrl))

    private fun releasePageButton(releaseUrl: String): MutableComponent =
        Component.literal("[Open Release Page]")
            .formatted(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
            .styled {
                it.withClickEvent(ClickEvent.OpenUrl(URI.create(releaseUrl)))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Open the official Skylist release page in your browser")))
            }

    private fun tlMessage(message: String): MutableComponent =
        Component.empty()
            .append(Component.literal("[SL] ").formatted(ChatFormatting.AQUA))
            .append(Component.literal(message))

    private fun tlMessage(message: Text): MutableComponent =
        Component.empty()
            .append(Component.literal("[SL] ").formatted(ChatFormatting.AQUA))
            .append(message)

    private data class UpdateInfo(
        val releaseTag: String,
        val latestVersion: String,
        val minecraftVersion: String,
        val releaseUrl: String,
    )
}
