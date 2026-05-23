package dev.ryan.playerlist

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

object PostUpdateChangelogManager {
    private val pendingFilePath: Path =
        FabricLoader.getInstance().gameDir.resolve(".skylist-updater").resolve("pending-changelog.json")

    @Volatile
    private var pendingNotice: PendingChangelogNotice? = null
    private var shownThisSession = false

    fun register() {
        pendingNotice = loadPendingNotice()
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (shownThisSession) {
                return@register
            }
            val pending = pendingNotice ?: return@register
            val currentScreen = client.currentScreen ?: return@register
            if (shownThisSession) {
                return@register
            }
            shownThisSession = true
            pendingNotice = null
            deletePendingNotice()
            client.setScreen(PostUpdateChangelogScreen(currentScreen, pending))
        }
    }

    fun stagePendingNotice(version: String, releaseUrl: String, releaseBody: String?) {
        val notice = PendingChangelogNotice(
            version = version,
            title = "Skylist Updated",
            releaseUrl = releaseUrl,
            body = releaseBody?.trim().takeUnless { it.isNullOrEmpty() } ?: "No release notes were provided for this release.",
        )
        Files.createDirectories(pendingFilePath.parent)
        Files.writeString(pendingFilePath, ConfigManager.gson.toJson(notice))
    }

    private fun loadPendingNotice(): PendingChangelogNotice? {
        if (Files.notExists(pendingFilePath)) {
            return null
        }
        return runCatching {
            val parsed = ConfigManager.gson.fromJson(Files.readString(pendingFilePath), PendingChangelogNotice::class.java)
            parsed?.takeIf { it.version == RuntimeVersion.currentVersion() }
        }.getOrNull()?.also {
            PlayerListMod.logger.info("Loaded pending Skylist changelog for {}", it.version)
        }
    }

    private fun deletePendingNotice() {
        runCatching {
            Files.deleteIfExists(pendingFilePath)
        }.onFailure {
            PlayerListMod.logger.warn("Failed to delete pending Skylist changelog marker", it)
        }
    }

    data class PendingChangelogNotice(
        val version: String,
        val title: String,
        val releaseUrl: String,
        val body: String,
    )
}
