package dev.ryan.throwerlist

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mojang.authlib.GameProfile
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object SkylistPresenceManager {
    private data class PresenceEntry(
        val uuid: String,
        val username: String?,
        val hasSkylist: Boolean,
        val hasSkylistPlus: Boolean,
    )

    private val uuidEntries = ConcurrentHashMap<String, PresenceEntry>()
    private val usernameEntries = ConcurrentHashMap<String, PresenceEntry>()
    private val redPrefix: Text = Text.literal("<3 ").formatted(Formatting.RED)
    private val pinkPrefix: Text = Text.literal("<3 ").formatted(Formatting.LIGHT_PURPLE)

    fun initialize() {
        refreshAsync()
        publishStartupPresence()
    }

    fun refreshAsync(): CompletableFuture<Unit> =
        CompletableFuture.runAsync {
            runCatching { WorkerRelay.fetchJson("/v1/skylist/presence") }
                .onSuccess { response ->
                    rebuildLookups(response?.getAsJsonArray("entries") ?: JsonArray())
                }
                .onFailure {
                    ThrowerListMod.logger.warn("Failed to refresh Skylist presence entries", it)
                }
        }.thenApply { Unit }

    fun publishStartupPresence() {
        val client = ThrowerListMod.client
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
                val payload = JsonObject().apply {
                    addProperty("uuid", uuid)
                    addProperty("username", username)
                    addProperty("hasSkylist", true)
                    addProperty("hasSkylistPlus", hasSkylistPlus)
                }
                WorkerRelay.postJson("/v1/skylist/presence", payload.toString())
            }.onFailure {
                ThrowerListMod.logger.warn("Failed to publish Skylist startup presence", it)
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

        return Text.empty()
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

        return Text.empty()
            .append(prefixForEntry(entry).copy())
            .append(text.copy())
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
        val client = ThrowerListMod.client
        val sessionUuid = client.session.uuidOrNull?.toString()
        val sessionName = client.session.username
        val matchesSelf = normalizeUuid(uuid) == normalizeUuid(sessionUuid) ||
            (normalizeUsername(username) != null && normalizeUsername(username) == normalizeUsername(sessionName))
        if (!matchesSelf) {
            return null
        }

        return PresenceEntry(
            uuid = normalizeUuid(sessionUuid) ?: return null,
            username = sessionName.takeIf { it.isNotBlank() },
            hasSkylist = true,
            hasSkylistPlus = FabricLoader.getInstance().isModLoaded("skylistplus"),
        )
    }

    private fun prefixForEntry(entry: PresenceEntry): Text =
        if (entry.hasSkylistPlus) pinkPrefix else redPrefix

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
