package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PlayerCustomizationRegistry {
    data class NameBadge(
        val text: String,
        val color: Int,
        val bold: Boolean = false,
    )

    data class NameColors(
        val left: Int,
        val right: Int,
    ) {
        companion object {
            fun solid(color: Int) = NameColors(color, color)
        }
    }

    data class PlayerCustomization(
        val username: String,
        val uuid: UUID? = null,
        val aliases: List<String> = emptyList(),
        val nameColors: NameColors? = null,
        val nameBadge: NameBadge? = null,
        val capeResourcePath: String? = null,
        val capeUrl: String? = null,
        val scale: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val scaleZ: Float? = null,
    ) {
        @Volatile
        var syncedUuid: UUID? = uuid
            private set

        @Volatile
        var syncedUsername: String? = username.trim().takeIf { it.isNotEmpty() }
            private set

        fun matches(profile: GameProfile?): Boolean {
            if (profile == null) {
                return false
            }

            return matches(profile.name, profile.id)
        }

        fun matches(name: String?, id: UUID? = null): Boolean {
            val effectiveUsername = syncedUsername
            val usernameMatches =
                name.equals(effectiveUsername, ignoreCase = true) ||
                    name.equals(username, ignoreCase = true) ||
                    aliases.any { alias -> name.equals(alias, ignoreCase = true) }
            val effectiveUuid = syncedUuid
            val uuidMatches = effectiveUuid != null && id == effectiveUuid
            return usernameMatches || uuidMatches
        }

        fun matchNames(): List<String> = buildList {
            syncedUsername?.let(::add)
            if (username.isNotBlank() && none { it.equals(username, ignoreCase = true) }) {
                add(username)
            }
            aliases.forEach { alias ->
                if (none { it.equals(alias, ignoreCase = true) }) {
                    add(alias)
                }
            }
        }

        fun updateSyncedUuid(uuid: UUID) {
            syncedUuid = uuid
        }

        fun updateSyncedUsername(username: String) {
            syncedUsername = username.trim().takeIf { it.isNotEmpty() }
        }
    }

    @Volatile
    private var loadedEntries: List<PlayerCustomization> = emptyList()

    val entries: List<PlayerCustomization>
        get() = loadedEntries

    private val requestedUsernames = ConcurrentHashMap.newKeySet<String>()

    fun initialize() {
        loadedEntries = buildEntries()
        requestedUsernames.clear()

        loadedEntries.forEach { entry ->
            val effectiveUuid = entry.syncedUuid
            if (effectiveUuid != null) {
                UsernameResolver.resolveUuid(effectiveUuid.toString()).thenAccept { resolvedName ->
                    val normalizedName = resolvedName?.trim()?.takeIf { it.isNotEmpty() } ?: return@thenAccept
                    entry.updateSyncedUsername(normalizedName)
                    ThrowerListMod.logger.info("Synced custom player username: {} -> {}", effectiveUuid, normalizedName)
                }
                return@forEach
            }

            val requestedUsername = entry.username.trim().takeIf { it.isNotEmpty() } ?: return@forEach
            if (!requestedUsernames.add(requestedUsername.lowercase(Locale.ROOT))) {
                return@forEach
            }

            UsernameResolver.resolve(requestedUsername).thenAccept { resolved ->
                val resolvedUuid = resolved?.uuid?.let(UUID::fromString) ?: return@thenAccept
                entry.updateSyncedUuid(resolvedUuid)
                resolved.username.trim().takeIf { it.isNotEmpty() }?.let(entry::updateSyncedUsername)
                ThrowerListMod.logger.info("Synced custom player UUID: {} -> {}", requestedUsername, resolvedUuid)
            }
        }
    }

    private fun buildEntries(): List<PlayerCustomization> {
        val mergedEntries = linkedMapOf<String, PlayerCustomization>()
        ContentManager.playerCustomizations().forEach { entry ->
            val customization = toCustomization(entry) ?: return@forEach
            val key = customization.uuid?.toString()?.lowercase(Locale.ROOT)
                ?: customization.username.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT)
                ?: return@forEach
            mergedEntries.putIfAbsent(key, customization)
        }
        return mergedEntries.values.toList()
    }

    fun find(profile: GameProfile?): PlayerCustomization? = loadedEntries.firstOrNull { it.matches(profile) }

    fun findByName(name: String?): PlayerCustomization? = loadedEntries.firstOrNull { it.matches(name) }

    private fun toCustomization(entry: ContentManager.LoadedPlayerCustomization): PlayerCustomization? {
        val uuid = entry.uuid?.let { value ->
            runCatching { UUID.fromString(value) }
                .getOrElse {
                    ThrowerListMod.logger.warn("Skipping customization '{}' because uuid '{}' is invalid", entry.username, value)
                    return null
                }
        }
        val normalizedUsername = entry.username.trim()
        if (normalizedUsername.isEmpty() && uuid == null) {
            ThrowerListMod.logger.warn("Skipping customization because both username and uuid are missing")
            return null
        }

        val nameColors = when (entry.style?.mode) {
            ContentManager.LoadedNameStyle.Mode.SOLID,
            ContentManager.LoadedNameStyle.Mode.GRADIENT -> {
                val left = entry.style.leftColor ?: return null
                val right = entry.style.rightColor ?: left
                NameColors(left = left, right = right)
            }

            else -> null
        }

        val badge = entry.badge?.let {
            NameBadge(
                text = it.text,
                color = it.color,
                bold = it.bold,
            )
        }

        return PlayerCustomization(
            username = normalizedUsername,
            uuid = uuid,
            aliases = entry.aliases,
            nameColors = nameColors,
            nameBadge = badge,
            capeResourcePath = entry.capeResourcePath,
            capeUrl = entry.capeUrl,
            scale = entry.scale,
            scaleX = entry.scaleX,
            scaleY = entry.scaleY,
            scaleZ = entry.scaleZ,
        )
    }
}
