package dev.ryan.throwerlist

import com.mojang.authlib.GameProfile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PlayerCustomizationRegistry {
    data class NameCandidate(
        val customization: PlayerCustomization,
        val text: String,
        val requiresBoundary: Boolean = false,
    )

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

        fun hasNameCustomization(): Boolean = nameColors != null || nameBadge != null

        fun hasCapeCustomization(): Boolean =
            !capeResourcePath.isNullOrBlank() || !capeUrl.isNullOrBlank()

        fun hasScaleCustomization(): Boolean =
            scale != null || scaleX != null || scaleY != null || scaleZ != null

        fun updateSyncedUuid(uuid: UUID) {
            syncedUuid = uuid
        }

        fun updateSyncedUsername(username: String) {
            syncedUsername = username.trim().takeIf { it.isNotEmpty() }
        }
    }

    private data class Snapshot(
        val version: Long,
        val entries: List<PlayerCustomization>,
        val byName: Map<String, PlayerCustomization>,
        val byUuid: Map<UUID, PlayerCustomization>,
        val allNameCandidates: List<NameCandidate>,
        val styledNameCandidates: List<NameCandidate>,
        val gradientNameCandidates: List<NameCandidate>,
        val scoreboardStyledNameCandidates: List<NameCandidate>,
        val scoreboardGradientNameCandidates: List<NameCandidate>,
        val hasCapeCustomizations: Boolean,
        val hasScaleCustomizations: Boolean,
    ) {
        companion object {
            fun empty(): Snapshot = Snapshot(
                version = 0L,
                entries = emptyList(),
                byName = emptyMap(),
                byUuid = emptyMap(),
                allNameCandidates = emptyList(),
                styledNameCandidates = emptyList(),
                gradientNameCandidates = emptyList(),
                scoreboardStyledNameCandidates = emptyList(),
                scoreboardGradientNameCandidates = emptyList(),
                hasCapeCustomizations = false,
                hasScaleCustomizations = false,
            )
        }
    }

    @Volatile
    private var loadedEntries: List<PlayerCustomization> = emptyList()

    @Volatile
    private var snapshot: Snapshot = Snapshot.empty()

    // Hot render hooks read this immutable snapshot instead of rebuilding merged entries
    // or scanning aliases on every drawn name.
    val entries: List<PlayerCustomization>
        get() = snapshot.entries

    val version: Long
        get() = snapshot.version

    val allNameCandidates: List<NameCandidate>
        get() = snapshot.allNameCandidates

    val styledNameCandidates: List<NameCandidate>
        get() = snapshot.styledNameCandidates

    val gradientNameCandidates: List<NameCandidate>
        get() = snapshot.gradientNameCandidates

    val scoreboardStyledNameCandidates: List<NameCandidate>
        get() = snapshot.scoreboardStyledNameCandidates

    val scoreboardGradientNameCandidates: List<NameCandidate>
        get() = snapshot.scoreboardGradientNameCandidates

    private val requestedUsernames = ConcurrentHashMap.newKeySet<String>()

    fun initialize() {
        loadedEntries = buildEntries()
        requestedUsernames.clear()
        rebuildSnapshot()

        loadedEntries.forEach { entry ->
            val effectiveUuid = entry.syncedUuid
            if (effectiveUuid != null) {
                UsernameResolver.resolveUuid(effectiveUuid.toString()).thenAccept { resolvedName ->
                    val normalizedName = resolvedName?.trim()?.takeIf { it.isNotEmpty() } ?: return@thenAccept
                    entry.updateSyncedUsername(normalizedName)
                    rebuildSnapshot()
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
                rebuildSnapshot()
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

    fun find(profile: GameProfile?): PlayerCustomization? {
        if (profile == null) {
            return null
        }

        val current = snapshot
        current.byUuid[profile.id]?.let { return it }
        return findByName(profile.name)
    }

    fun findByName(name: String?): PlayerCustomization? =
        normalizedNameKey(name)?.let { snapshot.byName[it] }

    fun findWithCape(profile: GameProfile?): PlayerCustomization? =
        find(profile)?.takeIf { it.hasCapeCustomization() }

    fun findWithScale(profile: GameProfile?): PlayerCustomization? =
        find(profile)?.takeIf { it.hasScaleCustomization() }

    fun hasCapeCustomizations(): Boolean = snapshot.hasCapeCustomizations

    fun hasScaleCustomizations(): Boolean = snapshot.hasScaleCustomizations

    @Synchronized
    private fun rebuildSnapshot() {
        val effectiveEntries = effectiveEntries()
        val byName = linkedMapOf<String, PlayerCustomization>()
        val byUuid = linkedMapOf<UUID, PlayerCustomization>()
        val allNameCandidates = mutableListOf<NameCandidate>()
        val styledNameCandidates = mutableListOf<NameCandidate>()
        val gradientNameCandidates = mutableListOf<NameCandidate>()
        val scoreboardStyledNameCandidates = mutableListOf<NameCandidate>()
        val scoreboardGradientNameCandidates = mutableListOf<NameCandidate>()

        effectiveEntries.forEach { customization ->
            customization.syncedUuid?.let { byUuid.putIfAbsent(it, customization) }
            customization.uuid?.let { byUuid.putIfAbsent(it, customization) }

            val names = customization.matchNames()
            names.forEach { name ->
                normalizedNameKey(name)?.let { byName.putIfAbsent(it, customization) }
            }

            val exactCandidates = names
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase(Locale.ROOT) }
                .map { NameCandidate(customization, it) }

            allNameCandidates.addAll(exactCandidates)
            if (customization.hasNameCustomization()) {
                styledNameCandidates.addAll(exactCandidates)
                scoreboardStyledNameCandidates.addAll(scoreboardCandidates(customization, names))
            }
            if (customization.nameColors != null) {
                gradientNameCandidates.addAll(exactCandidates)
                scoreboardGradientNameCandidates.addAll(scoreboardCandidates(customization, names))
            }
        }

        snapshot = Snapshot(
            version = snapshot.version + 1L,
            entries = effectiveEntries,
            byName = byName,
            byUuid = byUuid,
            allNameCandidates = allNameCandidates,
            styledNameCandidates = styledNameCandidates,
            gradientNameCandidates = gradientNameCandidates,
            scoreboardStyledNameCandidates = scoreboardStyledNameCandidates,
            scoreboardGradientNameCandidates = scoreboardGradientNameCandidates,
            hasCapeCustomizations = effectiveEntries.any { it.hasCapeCustomization() },
            hasScaleCustomizations = effectiveEntries.any { it.hasScaleCustomization() },
        )
    }

    private fun normalizedNameKey(name: String?): String? =
        name?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)

    private fun scoreboardCandidates(
        customization: PlayerCustomization,
        names: List<String>,
    ): List<NameCandidate> {
        val candidates = linkedMapOf<String, NameCandidate>()
        names.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { name ->
                candidates.putIfAbsent("exact:${name.lowercase(Locale.ROOT)}", NameCandidate(customization, name))

                if (name.length < 8) {
                    return@forEach
                }

                val minimumLength = maxOf(6, name.length - 4, (name.length * 0.7f).toInt())
                for (length in name.length - 1 downTo minimumLength) {
                    val prefix = name.substring(0, length)
                    candidates.putIfAbsent(
                        "prefix:${prefix.lowercase(Locale.ROOT)}",
                        NameCandidate(customization, prefix, requiresBoundary = true),
                    )
                }
            }
        return candidates.values.toList()
    }

    private fun effectiveEntries(): List<PlayerCustomization> {
        val mergedEntries = linkedMapOf<String, PlayerCustomization>()
        loadedEntries.forEach { customization ->
            val identities = customization.identityKeys()
            if (identities.isEmpty()) {
                return@forEach
            }

            val existingKey = mergedEntries.entries.firstOrNull { (_, existing) ->
                existing.identityKeys().any(identities::contains)
            }?.key

            val key = existingKey ?: identities.first()
            mergedEntries[key] = mergedEntries[key]?.mergedWith(customization) ?: customization
        }
        return mergedEntries.values.toList()
    }

    private fun PlayerCustomization.identityKeys(): Set<String> = buildSet {
        syncedUuid?.let { add("uuid:${it.toString().lowercase(Locale.ROOT)}") }
        uuid?.let { add("uuid:${it.toString().lowercase(Locale.ROOT)}") }
        syncedUsername?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)?.let { add("name:$it") }
        username.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)?.let { add("name:$it") }
        aliases.forEach { alias ->
            alias.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)?.let { add("name:$it") }
        }
    }

    private fun PlayerCustomization.mergedWith(overlay: PlayerCustomization): PlayerCustomization {
        val resolvedUsername = overlay.syncedUsername
            ?: overlay.username.takeIf { it.isNotBlank() }
            ?: syncedUsername
            ?: username
        val resolvedUuid = overlay.syncedUuid ?: overlay.uuid ?: syncedUuid ?: uuid
        val mergedAliases = buildList {
            aliases.forEach(::add)
            overlay.aliases.forEach(::add)
            syncedUsername?.let(::add)
            overlay.syncedUsername?.let(::add)
            add(username)
            add(overlay.username)
        }.map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals(resolvedUsername, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.ROOT) }

        val merged = PlayerCustomization(
            username = resolvedUsername,
            uuid = resolvedUuid,
            aliases = mergedAliases,
            nameColors = overlay.nameColors ?: nameColors,
            nameBadge = overlay.nameBadge ?: nameBadge,
            capeResourcePath = overlay.capeResourcePath ?: capeResourcePath,
            capeUrl = overlay.capeUrl ?: capeUrl,
            scale = overlay.scale ?: scale,
            scaleX = overlay.scaleX ?: scaleX,
            scaleY = overlay.scaleY ?: scaleY,
            scaleZ = overlay.scaleZ ?: scaleZ,
        )

        syncedUuid?.let(merged::updateSyncedUuid)
        overlay.syncedUuid?.let(merged::updateSyncedUuid)
        syncedUsername?.let(merged::updateSyncedUsername)
        overlay.syncedUsername?.let(merged::updateSyncedUsername)
        return merged
    }

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
