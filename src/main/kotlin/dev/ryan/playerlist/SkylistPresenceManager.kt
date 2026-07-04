package dev.ryan.playerlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object SkylistPresenceManager {
    // Legacy-formatted heart prefix strings using Minecraft's standard section-sign format code.
    // `§c` = red, `§d` = light purple/pink, `§r` = reset. These are display-only chat prefixes.
    private const val redPrefixLegacy = "§c<3 §r"
    private const val pinkPrefixLegacy = "§d<3 §r"

    private data class PresenceEntry(
        val uuid: String,
        val username: String?,
        val hasSkylist: Boolean,
        val hasSkylistPlus: Boolean,
    )

    private val uuidEntries = ConcurrentHashMap<String, PresenceEntry>()
    private val usernameEntries = ConcurrentHashMap<String, PresenceEntry>()
    private val redPrefix: Text = Component.literal("<3 ").formatted(ChatFormatting.RED)
    private val pinkPrefix: Text = Component.literal("<3 ").formatted(ChatFormatting.LIGHT_PURPLE)

    fun initialize() {
        refreshAsync()
        publishStartupPresence()
    }

    fun refreshAsync(): CompletableFuture<Unit> =
        CompletableFuture.runAsync {
            runCatching { SkylistApiClient.fetchJson("/v1/skylist/presence") }
                .onSuccess { response ->
                    rebuildLookups(response?.getAsJsonArray("entries") ?: JsonArray())
                }
                .onFailure {
                    PlayerListMod.logger.warn("Failed to refresh Skylist presence entries", it)
                }
        }.thenApply { Unit }

    fun publishStartupPresence() {
        val client = PlayerListMod.client
        val uuid = client.session.uuidOrNull?.toString()?.replace("-", "")?.lowercase(Locale.ROOT) ?: return
        val username = client.session.username.takeIf { it.isNotBlank() } ?: return
        val hasSkylistPlus = FabricLoader.getInstance().isModLoaded("skylistplus")
        cacheLocalEntry(
            PresenceEntry(
                uuid = uuid,
                username = username,
                hasSkylist = true,
                hasSkylistPlus = hasSkylistPlus,
            ),
        )

        CompletableFuture.runAsync {
            runCatching {
                // Presence publishing is opt-in mod metadata only. The payload contains the
                // current player's UUID, username, and whether Skylist/SkylistPlus is loaded
                // so other clients can display a small identifier. No tokens, credentials,
                // chat logs, Discord data, or local files are read or transmitted here.
                val payload = JsonObject().apply {
                    addProperty("uuid", uuid)
                    addProperty("username", username)
                    addProperty("hasSkylist", true)
                    addProperty("hasSkylistPlus", hasSkylistPlus)
                }
                SkylistApiClient.postJson("/v1/skylist/presence", payload.toString())
            }.onFailure {
                PlayerListMod.logger.warn("Failed to publish Skylist startup presence", it)
            }
        }
    }

    fun isIdentifierEnabled(): Boolean = ConfigManager.isDeveloperIdentifierEnabled()

    fun toggleIdentifierEnabled(): Boolean = ConfigManager.toggleDeveloperIdentifierEnabled()

    fun applyIdentifier(text: Text, profile: GameProfile?): Text {
        if (!isIdentifierEnabled() || text.string.startsWith("<3 ")) {
            return text
        }

        val entry = resolveEntry(profile) ?: return text
        if (!entry.hasSkylist) {
            return text
        }

        return Component.empty()
            .append(prefixForEntry(entry).copy())
            .append(text.copy())
    }

    fun applyIdentifier(text: Text, username: String?): Text {
        if (!isIdentifierEnabled() || text.string.startsWith("<3 ")) {
            return text
        }

        val entry = normalizeUsername(username)?.let(usernameEntries::get) ?: return text
        if (!entry.hasSkylist) {
            return text
        }

        return Component.empty()
            .append(prefixForEntry(entry).copy())
            .append(text.copy())
    }

    fun applyIdentifierToString(text: String?, username: String?): String? {
        if (text.isNullOrEmpty() || !isIdentifierEnabled() || text.startsWith("<3 ") || text.startsWith(redPrefixLegacy) || text.startsWith(pinkPrefixLegacy)) {
            return text
        }

        val entry = normalizeUsername(username)?.let(usernameEntries::get) ?: return text
        if (!entry.hasSkylist) {
            return text
        }

        return legacyPrefixForEntry(entry) + text
    }

    private fun resolveEntry(profile: GameProfile?): PresenceEntry? {
        if (profile == null) {
            return null
        }

        localPresenceEntry(profile.id?.toString(), profile.name)?.let { return it }
        normalizeUuid(profile.id?.toString())?.let(uuidEntries::get)?.let { return it }
        return normalizeUsername(profile.name)?.let(usernameEntries::get)
    }

    private fun localPresenceEntry(uuid: String?, username: String?): PresenceEntry? {
        val client = PlayerListMod.client
        val currentPlayerUuid = client.session.uuidOrNull?.toString()
        val currentPlayerUsername = client.session.username
        val matchesCurrentPlayer = normalizeUuid(uuid) == normalizeUuid(currentPlayerUuid) ||
            (
                normalizeUsername(username) != null &&
                    normalizeUsername(username) == normalizeUsername(currentPlayerUsername)
                )
        if (!matchesCurrentPlayer) {
            return null
        }

        return PresenceEntry(
            uuid = normalizeUuid(currentPlayerUuid) ?: return null,
            username = currentPlayerUsername.takeIf { it.isNotBlank() },
            hasSkylist = true,
            hasSkylistPlus = FabricLoader.getInstance().isModLoaded("skylistplus"),
        )
    }

    private fun prefixForEntry(entry: PresenceEntry): Text =
        if (entry.hasSkylistPlus) pinkPrefix else redPrefix

    private fun legacyPrefixForEntry(entry: PresenceEntry): String =
        if (entry.hasSkylistPlus) pinkPrefixLegacy else redPrefixLegacy

    private fun rebuildLookups(entries: JsonArray) {
        val byUuid = linkedMapOf<String, PresenceEntry>()
        val byUsername = linkedMapOf<String, PresenceEntry>()

        entries.forEach { element ->
            val json = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val parsed = parseEntry(json) ?: return@forEach
            byUuid[parsed.uuid] = parsed
            normalizeUsername(parsed.username)?.let { byUsername[it] = parsed }
        }

        uuidEntries.clear()
        uuidEntries.putAll(byUuid)
        usernameEntries.clear()
        usernameEntries.putAll(byUsername)
    }

    private fun parseEntry(json: JsonObject): PresenceEntry? {
        val uuid = normalizeUuid(json.get("uuid")?.asString) ?: return null
        val username = json.get("username")
            ?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val hasSkylist = json.get("hasSkylist")?.takeIf { !it.isJsonNull }?.let {
            runCatching { it.asBoolean }.getOrDefault(false)
        } ?: false
        val hasSkylistPlus = json.get("hasSkylistPlus")?.takeIf { !it.isJsonNull }?.let {
            runCatching { it.asBoolean }.getOrDefault(false)
        } ?: false

        return PresenceEntry(
            uuid = uuid,
            username = username,
            hasSkylist = hasSkylist,
            hasSkylistPlus = hasSkylistPlus,
        )
    }

    private fun normalizeUuid(value: String?): String? =
        value?.replace("-", "")
            ?.trim()
            ?.takeIf { it.matches(Regex("^[0-9a-fA-F]{32}$")) }
            ?.lowercase(Locale.ROOT)

    private fun normalizeUsername(value: String?): String? =
        value?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)

    private fun cacheLocalEntry(entry: PresenceEntry) {
        uuidEntries[entry.uuid] = entry
        normalizeUsername(entry.username)?.let { usernameEntries[it] = entry }
    }
}
