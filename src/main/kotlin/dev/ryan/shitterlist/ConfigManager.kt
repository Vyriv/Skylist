package dev.ryan.throwerlist

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.text.buildString

object ConfigManager {
    private const val defaultLocalAutokickTemplate = "[SL] <IGN> is on Vyriv's Skylist for <REASON>"
    private const val defaultRemoteAutokickTemplate = "[SL] <IGN> is on Vyriv's Skylist for <REASON>. Appeal at gg/4ZSFKWSY65"
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configDirectoryPath: Path = FabricLoader.getInstance().configDir.resolve("throwerlist")
    private val settingsPath: Path = configDirectoryPath.resolve("config.json")
    private val playersPath: Path = configDirectoryPath.resolve("throwerlist.json")
    private val importPath: Path = configDirectoryPath.resolve("tlimport.json")
    private val legacyConfigPath: Path = FabricLoader.getInstance().configDir.resolve("throwerlist.json")
    private val configComments = listOf(
        "# Example manual entry:",
        "# {",
        "#   \"username\": \"ign_here\",",
        "#   \"uuid\": \"ignore/add yourself\",",
        "#   \"reason\": \"reason_here\",",
        "#   \"ts\": 1774601234567,",
        "#   \"tags\": [\"toxic\", \"griefer\"],",
        "#   \"ignored\": false,",
        "#   \"autoRemoveAfter\": \"1 month 2 days\",",
        "#   \"expiresAt\": 1777193234567",
        "# }",
    )

    @Volatile
    private var settings = SettingsConfig()

    @Volatile
    private var players = mutableListOf<PlayerEntry>()

    @Volatile
    private var importState = ImportConfig()

    @Volatile
    private var lookupCaches = LookupCaches()

    @Synchronized
    fun load(): SettingsConfig {
        if (shouldMigrateLegacyConfig()) {
            migrateLegacyConfig()
        }

        if (Files.notExists(settingsPath) && Files.notExists(playersPath) && Files.notExists(importPath)) {
            settings = SettingsConfig()
            players = mutableListOf()
            importState = ImportConfig()
            save()
            return settings.copy()
        }

        settings = readJson(settingsPath, object : TypeToken<SettingsConfig>() {}.type, SettingsConfig(), "settings")
        players = readJson(playersPath, object : TypeToken<MutableList<PlayerEntry>>() {}.type, mutableListOf(), "local thrower list")
        importState = readJson(importPath, object : TypeToken<ImportConfig>() {}.type, ImportConfig(), "remote thrower list state")
        normalizeSettings()
        normalizePlayers()
        normalizeImportState()
        deleteLegacyHistoryFile()
        if (pruneExpiredEntriesLocked() > 0) {
            writeState()
        }
        rebuildLookupCachesLocked()

        return settings.copy()
    }

    @Synchronized
    fun save() {
        pruneExpiredEntriesLocked()
        rebuildLookupCachesLocked()
        writeState()
    }

    private fun insertConfigComments(json: String): String {
        return buildString {
            configComments.forEach { comment ->
                append(comment)
                append(System.lineSeparator())
            }
            append(json)
        }
    }

    @Synchronized
    fun isEnabled(): Boolean = settings.enabled ?: false

    @Synchronized
    fun isAutokickEnabled(): Boolean = (settings.localAutokickEnabled ?: false) || (settings.remoteAutokickEnabled ?: false)

    @Synchronized
    fun isAutokickEnabled(isRemote: Boolean): Boolean = if (isRemote) settings.remoteAutokickEnabled ?: false else settings.localAutokickEnabled ?: false

    @Synchronized
    fun isLobbyNotificationsEnabled(): Boolean = settings.lobbyNotifications

    @Synchronized
    fun toggleEnabled(): Boolean {
        return setEnabled(!isAutokickEnabled())
    }

    @Synchronized
    fun setEnabled(enabled: Boolean): Boolean {
        settings.localAutokickEnabled = enabled
        settings.remoteAutokickEnabled = enabled
        settings.enabled = enabled
        save()
        return isAutokickEnabled()
    }

    @Synchronized
    fun setAutokickEnabled(enabled: Boolean): Boolean = setEnabled(enabled)

    @Synchronized
    fun setAutokickEnabled(enabled: Boolean, isRemote: Boolean): Boolean {
        if (isRemote) {
            settings.remoteAutokickEnabled = enabled
        } else {
            settings.localAutokickEnabled = enabled
        }
        settings.enabled = isAutokickEnabled()
        save()
        return isAutokickEnabled(isRemote)
    }

    @Synchronized
    fun getAutokickMessageTemplate(isRemote: Boolean): String =
        if (isRemote) settings.remoteAutokickTemplate ?: defaultRemoteAutokickTemplate else settings.localAutokickTemplate ?: defaultLocalAutokickTemplate

    @Synchronized
    fun setAutokickMessageTemplate(template: String, isRemote: Boolean): String {
        val normalizedTemplate = template.trim()
        if (isRemote) {
            settings.remoteAutokickTemplate = normalizedTemplate
        } else {
            settings.localAutokickTemplate = normalizedTemplate
        }
        save()
        return getAutokickMessageTemplate(isRemote)
    }

    @Synchronized
    fun setLobbyNotificationsEnabled(enabled: Boolean): Boolean {
        settings.lobbyNotifications = enabled
        save()
        return settings.lobbyNotifications
    }

    @Synchronized
    fun isAssumePartyLeader(): Boolean = settings.assumePartyLeader

    @Synchronized
    fun setAssumePartyLeader(enabled: Boolean): Boolean {
        settings.assumePartyLeader = enabled
        save()
        return settings.assumePartyLeader
    }

    @Synchronized
    fun getHypixelApiKey(): String? = settings.hypixelApiKey?.takeIf { it.isNotBlank() }

    @Synchronized
    fun setHypixelApiKey(apiKey: String?): String? {
        settings.hypixelApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        save()
        return settings.hypixelApiKey
    }

    @Synchronized
    fun getUiTheme(): String = settings.uiTheme?.takeIf { it.isNotBlank() } ?: "ocean"

    @Synchronized
    fun setUiTheme(theme: String): String {
        settings.uiTheme = theme.trim().lowercase().ifBlank { "ocean" }
        save()
        return getUiTheme()
    }

    @Synchronized
    fun isRemoteScammerChecksEnabled(): Boolean = settings.remoteScammerChecksEnabled ?: true

    @Synchronized
    fun setRemoteScammerChecksEnabled(enabled: Boolean): Boolean {
        settings.remoteScammerChecksEnabled = enabled
        save()
        return isRemoteScammerChecksEnabled()
    }

    @Synchronized
    fun isAutoCheckPartyMembersEnabled(): Boolean = settings.autoCheckPartyMembersEnabled ?: true

    @Synchronized
    fun setAutoCheckPartyMembersEnabled(enabled: Boolean): Boolean {
        settings.autoCheckPartyMembersEnabled = enabled
        save()
        return isAutoCheckPartyMembersEnabled()
    }

    @Synchronized
    fun isAutoCheckOnJoinEnabled(): Boolean = settings.autoCheckOnJoinEnabled ?: true

    @Synchronized
    fun setAutoCheckOnJoinEnabled(enabled: Boolean): Boolean {
        settings.autoCheckOnJoinEnabled = enabled
        save()
        return isAutoCheckOnJoinEnabled()
    }

    @Synchronized
    fun getScammerStorageDuration(): String? = settings.scammerStorageDuration?.trim()?.takeIf { it.isNotEmpty() }

    @Synchronized
    fun setScammerStorageDuration(value: String?): String? {
        settings.scammerStorageDuration = value?.trim()?.takeIf { it.isNotEmpty() }
        save()
        return getScammerStorageDuration()
    }

    @Synchronized
    fun isScammerAutokickEnabled(): Boolean = settings.scammerAutokickEnabled ?: false

    @Synchronized
    fun setScammerAutokickEnabled(enabled: Boolean): Boolean {
        settings.scammerAutokickEnabled = enabled
        save()
        return isScammerAutokickEnabled()
    }

    @Synchronized
    fun isAnnounceScammerHitsEnabled(): Boolean = settings.announceScammerHitsEnabled ?: false

    @Synchronized
    fun setAnnounceScammerHitsEnabled(enabled: Boolean): Boolean {
        settings.announceScammerHitsEnabled = enabled
        save()
        return isAnnounceScammerHitsEnabled()
    }

    @Synchronized
    fun isScammerOnlyNotifyEnabled(): Boolean = settings.scammerOnlyNotifyEnabled ?: true

    @Synchronized
    fun setScammerOnlyNotifyEnabled(enabled: Boolean): Boolean {
        settings.scammerOnlyNotifyEnabled = enabled
        save()
        return isScammerOnlyNotifyEnabled()
    }

    @Synchronized
    fun isTradeScammerPopupEnabled(): Boolean = settings.tradeScammerPopupEnabled ?: true

    @Synchronized
    fun setTradeScammerPopupEnabled(enabled: Boolean): Boolean {
        settings.tradeScammerPopupEnabled = enabled
        save()
        return isTradeScammerPopupEnabled()
    }

    @Synchronized
    fun getDungeonAutokickSettings(): DungeonAutokickSettings = settings.dungeonAutokick.copy()

    @Synchronized
    fun isDungeonAutokickEnabled(): Boolean = settings.dungeonAutokick.enabled

    @Synchronized
    fun setDungeonAutokickEnabled(enabled: Boolean): Boolean {
        settings.dungeonAutokick.enabled = enabled
        save()
        return isDungeonAutokickEnabled()
    }

    @Synchronized
    fun getDungeonPbThreshold(floor: String): String? = settings.dungeonAutokick.pbThresholds.normalizedValue(floor)

    @Synchronized
    fun setDungeonPbThreshold(floor: String, value: String?): String? {
        settings.dungeonAutokick.pbThresholds.setNormalizedValue(floor, value)
        save()
        return settings.dungeonAutokick.pbThresholds.normalizedValue(floor)
    }

    @Synchronized
    fun isDungeonNoPrinceAttributeShardEnabled(): Boolean = settings.dungeonAutokick.noPrinceAttributeShard

    @Synchronized
    fun setDungeonNoPrinceAttributeShardEnabled(enabled: Boolean): Boolean {
        settings.dungeonAutokick.noPrinceAttributeShard = enabled
        save()
        return isDungeonNoPrinceAttributeShardEnabled()
    }

    @Synchronized
    fun isDungeonNoSpiritPetEnabled(): Boolean = settings.dungeonAutokick.noSpiritPet

    @Synchronized
    fun setDungeonNoSpiritPetEnabled(enabled: Boolean): Boolean {
        settings.dungeonAutokick.noSpiritPet = enabled
        save()
        return isDungeonNoSpiritPetEnabled()
    }

    @Synchronized
    fun isDungeonThornsOnEquippedArmourEnabled(): Boolean = settings.dungeonAutokick.thornsOnEquippedArmourSet

    @Synchronized
    fun setDungeonThornsOnEquippedArmourEnabled(enabled: Boolean): Boolean {
        settings.dungeonAutokick.thornsOnEquippedArmourSet = enabled
        save()
        return isDungeonThornsOnEquippedArmourEnabled()
    }

    @Synchronized
    fun hasConfiguredDungeonAutokick(): Boolean = settings.dungeonAutokick.enabled && settings.dungeonAutokick.hasConfiguredChecks()

    @Synchronized
    fun listPlayers(): List<PlayerEntry> = activePlayers().map { it.copy() }

    @Synchronized
    fun localUsernames(): List<String> = activePlayers().map { it.username }.sortedBy { it.lowercase() }

    @Synchronized
    fun localListedUsernames(): Set<String> = lookupCaches.localListedUsernames

    @Synchronized
    fun localIgnoredUsernames(): Set<String> = lookupCaches.localIgnoredUsernames

    @Synchronized
    fun miscIgnoredUsernames(): List<String> = lookupCaches.miscIgnoredUsernames

    @Synchronized
    fun miscIgnoredUsernameSet(): Set<String> = lookupCaches.miscIgnoredUsernameSet

    fun lookupVersion(): Long = lookupCaches.version

    @Synchronized
    fun isMiscIgnoreListEnabled(): Boolean = settings.miscIgnoreListEnabled

    @Synchronized
    fun setMiscIgnoreListEnabled(enabled: Boolean): Boolean {
        settings.miscIgnoreListEnabled = enabled
        save()
        return settings.miscIgnoreListEnabled
    }

    @Synchronized
    fun isMiscIgnoredUsername(username: String): Boolean =
        normalizeUsernameKey(username)?.let(lookupCaches.miscIgnoredUsernameSet::contains) == true

    @Synchronized
    fun isIgnoredUsername(username: String): Boolean =
        normalizeUsernameKey(username)?.let { normalized ->
            normalized in lookupCaches.localIgnoredUsernames ||
                (settings.miscIgnoreListEnabled && normalized in lookupCaches.miscIgnoredUsernameSet)
        } == true

    @Synchronized
    fun addMiscIgnoredUsername(username: String): Boolean {
        val normalizedUsername = username.trim()
        if (!normalizedUsername.matches(Regex("^[A-Za-z0-9_]{1,16}$"))) {
            return false
        }
        if (settings.miscIgnoredUsernames.any { it.equals(normalizedUsername, ignoreCase = true) }) {
            return false
        }

        settings.miscIgnoredUsernames.add(normalizedUsername)
        save()
        return true
    }

    @Synchronized
    fun removeMiscIgnoredUsername(username: String): Boolean {
        val iterator = settings.miscIgnoredUsernames.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().equals(username, ignoreCase = true)) {
                iterator.remove()
                save()
                return true
            }
        }
        return false
    }

    @Synchronized
    fun isSwingSpeedEnabled(): Boolean = settings.swingSpeedEnabled

    @Synchronized
    fun setSwingSpeedEnabled(enabled: Boolean): Boolean {
        settings.swingSpeedEnabled = enabled
        save()
        return settings.swingSpeedEnabled
    }

    @Synchronized
    fun getSwingSpeedValue(): Float = settings.swingSpeedValue

    @Synchronized
    fun setSwingSpeedValue(value: Float): Float {
        settings.swingSpeedValue = value.coerceIn(0.1f, 1.0f)
        save()
        return settings.swingSpeedValue
    }

    @Synchronized
    fun isPlayerListEmpty(): Boolean = activePlayers().isEmpty()

    @Synchronized
    fun clearPlayers() {
        ensureFreshPlayersLocked()
        players.clear()
        save()
    }

    @Synchronized
    fun findByUsername(username: String): PlayerEntry? =
        activePlayers()
            .firstOrNull { it.username.equals(username, ignoreCase = true) }
            ?.copy()

    @Synchronized
    fun findByUuid(uuid: String): PlayerEntry? =
        activePlayers()
            .firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?.copy()

    @Synchronized
    fun addPlayer(entry: PlayerEntry): Boolean {
        ensureFreshPlayersLocked()
        val exists = players.any {
            it.uuid.equals(entry.uuid, ignoreCase = true) || it.username.equals(entry.username, ignoreCase = true)
        }
        if (exists) {
            return false
        }

        importState.hiddenRemoteUuids.remove(entry.uuid.lowercase())
        if (entry.ts == null) {
            entry.ts = System.currentTimeMillis()
        }
        normalizePlayerEntry(entry)
        players.add(entry)
        save()
        return true
    }

    @Synchronized
    fun importPlayers(entries: Collection<PlayerEntry>): ImportPlayersResult {
        ensureFreshPlayersLocked()
        var importedCount = 0
        var skippedCount = 0

        entries.forEach { entry ->
            val exists = players.any {
                it.uuid.equals(entry.uuid, ignoreCase = true) || it.username.equals(entry.username, ignoreCase = true)
            }
            if (exists) {
                skippedCount++
                return@forEach
            }

            importState.hiddenRemoteUuids.remove(entry.uuid.lowercase())
            if (entry.ts == null) {
                entry.ts = System.currentTimeMillis()
            }
            normalizePlayerEntry(entry)
            if (EntryExpiry.hasExpired(entry.expiresAt)) {
                skippedCount++
                return@forEach
            }
            players.add(entry)
            importedCount++
        }

        if (importedCount > 0) {
            save()
        }

        return ImportPlayersResult(importedCount, skippedCount)
    }

    @Synchronized
    fun removePlayer(username: String): PlayerEntry? {
        ensureFreshPlayersLocked()
        val iterator = players.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.username.equals(username, ignoreCase = true)) {
                iterator.remove()
                save()
                return entry.copy()
            }
        }
        return null
    }

    @Synchronized
    fun editReason(username: String, newReason: String): PlayerEntry? {
        ensureFreshPlayersLocked()
        val entry = players.firstOrNull { it.username.equals(username, ignoreCase = true) } ?: return null
        entry.reason = newReason
        save()
        return entry.copy()
    }

    @Synchronized
    fun setTags(username: String, tags: Collection<String>): PlayerEntry? {
        ensureFreshPlayersLocked()
        val entry = players.firstOrNull { it.username.equals(username, ignoreCase = true) } ?: return null
        entry.tags = ThrowerTags.normalize(tags)
        save()
        return entry.copy()
    }

    @Synchronized
    fun updateLocalEntry(
        username: String,
        newReason: String,
        newTags: Collection<String>,
        ignored: Boolean,
        autoRemoveAfter: String?,
        expiresAt: Long?,
    ): PlayerEntry? {
        ensureFreshPlayersLocked()
        val entry = players.firstOrNull { it.username.equals(username, ignoreCase = true) } ?: return null
        entry.reason = newReason
        entry.tags = ThrowerTags.normalize(newTags)
        entry.ignored = ignored
        entry.autoRemoveAfter = autoRemoveAfter?.trim()?.takeIf { it.isNotEmpty() }
        entry.expiresAt = expiresAt
        normalizePlayerEntry(entry)
        save()
        return entry.copy()
    }

    @Synchronized
    fun updateUsername(uuid: String, username: String): PlayerEntry? {
        ensureFreshPlayersLocked()
        val entry = players.firstOrNull { it.uuid.equals(uuid, ignoreCase = true) } ?: return null
        if (!entry.username.equals(username, ignoreCase = true) || entry.username != username) {
            entry.username = username
            save()
        }
        return entry.copy()
    }

    @Synchronized
    fun hideRemoteEntry(uuid: String): Boolean {
        return setRemoteEntryDisabled(uuid, true)
    }

    @Synchronized
    fun setRemoteEntryDisabled(uuid: String, disabled: Boolean): Boolean {
        val normalizedUuid = uuid.lowercase()
        val changed = if (disabled) {
            importState.hiddenRemoteUuids.add(normalizedUuid)
        } else {
            importState.hiddenRemoteUuids.remove(normalizedUuid)
        }
        if (!changed) {
            return false
        }
        save()
        return true
    }

    @Synchronized
    fun toggleRemoteEntryDisabled(uuid: String): Boolean {
        val disabled = !isRemoteHidden(uuid)
        setRemoteEntryDisabled(uuid, disabled)
        return disabled
    }

    @Synchronized
    fun isRemoteHidden(uuid: String): Boolean =
        normalizeUuidKey(uuid)?.let(lookupCaches.hiddenRemoteUuids::contains) == true

    @Synchronized
    fun isRemoteDisabled(uuid: String): Boolean = isRemoteHidden(uuid)

    @Synchronized
    fun getOrCreateRemoteImportTimestamp(uuid: String): Long {
        ensureFreshPlayersLocked()
        val normalizedUuid = uuid.lowercase()
        val existing = importState.remoteImportedTimestamps[normalizedUuid]
        if (existing != null) {
            return existing
        }

        val created = System.currentTimeMillis()
        importState.remoteImportedTimestamps[normalizedUuid] = created
        save()
        return created
    }

    private fun shouldMigrateLegacyConfig(): Boolean =
        Files.notExists(settingsPath) &&
            Files.notExists(playersPath) &&
            Files.notExists(importPath) &&
            Files.exists(legacyConfigPath)

    private fun migrateLegacyConfig() {
        val legacyConfig = runCatching {
            val rawConfig = Files.readString(legacyConfigPath)
            val cleanedConfig = rawConfig.lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(System.lineSeparator())
            gson.fromJson(cleanedConfig, LegacyThrowerListConfig::class.java) ?: LegacyThrowerListConfig()
        }.getOrElse {
            ThrowerListMod.logger.error("Failed to migrate legacy throwerlist config, using defaults", it)
            LegacyThrowerListConfig()
        }

        settings = SettingsConfig(
            enabled = legacyConfig.enabled,
            localAutokickEnabled = legacyConfig.enabled,
            remoteAutokickEnabled = false,
            localAutokickTemplate = defaultLocalAutokickTemplate,
            remoteAutokickTemplate = defaultRemoteAutokickTemplate,
            lobbyNotifications = legacyConfig.lobbyNotifications,
            assumePartyLeader = legacyConfig.assumePartyLeader,
            hypixelApiKey = legacyConfig.hypixelApiKey,
        )
        players = legacyConfig.players
        importState = ImportConfig(
            hiddenRemoteUuids = legacyConfig.hiddenRemoteUuids,
            remoteImportedTimestamps = legacyConfig.remoteImportedTimestamps,
        )
        normalizePlayers()
        normalizeImportState()
        save()
    }

    private fun normalizeSettings() {
        settings.localAutokickEnabled = settings.localAutokickEnabled ?: settings.enabled ?: true
        settings.remoteAutokickEnabled = settings.remoteAutokickEnabled ?: false
        settings.localAutokickTemplate = settings.localAutokickTemplate
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultLocalAutokickTemplate
        settings.remoteAutokickTemplate = settings.remoteAutokickTemplate
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: defaultRemoteAutokickTemplate
        settings.uiTheme = settings.uiTheme?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "ocean"
        settings.remoteScammerChecksEnabled = settings.remoteScammerChecksEnabled ?: true
        settings.autoCheckPartyMembersEnabled = settings.autoCheckPartyMembersEnabled ?: true
        settings.autoCheckOnJoinEnabled = settings.autoCheckOnJoinEnabled ?: true
        settings.miscIgnoreListEnabled = settings.miscIgnoreListEnabled
        settings.miscIgnoredUsernames = settings.miscIgnoredUsernames
            .mapNotNull { it.trim().takeIf { trimmed -> trimmed.matches(Regex("^[A-Za-z0-9_]{1,16}$")) } }
            .distinctBy { it.lowercase() }
            .toMutableList()
        settings.scammerStorageDuration = settings.scammerStorageDuration?.trim()?.takeIf { it.isNotEmpty() }
        settings.scammerAutokickEnabled = settings.scammerAutokickEnabled ?: false
        settings.announceScammerHitsEnabled = settings.announceScammerHitsEnabled ?: false
        settings.scammerOnlyNotifyEnabled = settings.scammerOnlyNotifyEnabled ?: true
        settings.tradeScammerPopupEnabled = settings.tradeScammerPopupEnabled ?: true
        settings.dungeonAutokick = settings.dungeonAutokick.normalized()
        settings.enabled = isAutokickEnabled()
    }

    private fun MutableMap<String, String?>.normalizedValue(floor: String): String? =
        get(floor.trim().uppercase())?.trim()?.takeIf { it.isNotEmpty() }

    private fun MutableMap<String, String?>.setNormalizedValue(floor: String, value: String?) {
        val normalizedFloor = floor.trim().uppercase()
        val normalizedValue = value?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedValue == null) {
            remove(normalizedFloor)
        } else {
            put(normalizedFloor, normalizedValue)
        }
    }

    private fun normalizePlayers() {
        players.forEach(::normalizePlayerEntry)
    }

    private fun normalizeImportState() {
        importState.hiddenRemoteUuids = importState.hiddenRemoteUuids
            .mapTo(linkedSetOf()) { it.lowercase() }
        importState.remoteImportedTimestamps = importState.remoteImportedTimestamps.entries
            .associateTo(linkedMapOf()) { it.key.lowercase() to it.value }
    }

    private fun deleteLegacyHistoryFile() {
        val historyPath = configDirectoryPath.resolve("tlhistory.json")
        if (Files.exists(historyPath)) {
            Files.deleteIfExists(historyPath)
        }
    }

    private fun activePlayers(): List<PlayerEntry> {
        ensureFreshPlayersLocked()
        return players
    }

    private fun ensureFreshPlayersLocked() {
        if (pruneExpiredEntriesLocked() > 0) {
            writeState()
        }
    }

    private fun pruneExpiredEntriesLocked(nowMillis: Long = System.currentTimeMillis()): Int {
        val iterator = players.iterator()
        var removed = 0
        while (iterator.hasNext()) {
            if (EntryExpiry.hasExpired(iterator.next().expiresAt, nowMillis)) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    private fun normalizePlayerEntry(entry: PlayerEntry) {
        entry.tags = ThrowerTags.normalize(entry.tags)
        val normalizedExpiry = EntryExpiry.normalizeEntry(entry)
        entry.autoRemoveAfter = normalizedExpiry.timeframe
        entry.expiresAt = normalizedExpiry.expiresAt
    }

    private fun rebuildLookupCachesLocked() {
        val localListedUsernames = linkedSetOf<String>()
        val localIgnoredUsernames = linkedSetOf<String>()
        players.forEach { entry ->
            val normalizedUsername = normalizeUsernameKey(entry.username) ?: return@forEach
            if (entry.ignored) {
                localIgnoredUsernames.add(normalizedUsername)
            } else {
                localListedUsernames.add(normalizedUsername)
            }
        }

        val miscIgnoredUsernames = settings.miscIgnoredUsernames
            .mapNotNull(::normalizeUsernameKey)
            .distinct()
            .sorted()
        val hiddenRemoteUuids = importState.hiddenRemoteUuids
            .mapNotNull(::normalizeUuidKey)
            .toCollection(linkedSetOf())

        lookupCaches = LookupCaches(
            version = lookupCaches.version + 1L,
            localListedUsernames = localListedUsernames,
            localIgnoredUsernames = localIgnoredUsernames,
            miscIgnoredUsernames = miscIgnoredUsernames,
            miscIgnoredUsernameSet = miscIgnoredUsernames.toCollection(linkedSetOf()),
            hiddenRemoteUuids = hiddenRemoteUuids,
        )
    }

    private fun normalizeUsernameKey(username: String?): String? =
        username?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)

    private fun normalizeUuidKey(uuid: String?): String? =
        uuid?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase(Locale.ROOT)

    private fun writeState() {
        Files.createDirectories(configDirectoryPath)
        Files.writeString(settingsPath, gson.toJson(settings))
        Files.writeString(playersPath, insertConfigComments(gson.toJson(players)))
        Files.writeString(importPath, gson.toJson(importState))
        deleteLegacyHistoryFile()
    }

    private fun <T> readJson(path: Path, type: java.lang.reflect.Type, defaultValue: T, label: String): T {
        if (Files.notExists(path)) {
            return defaultValue
        }

        return runCatching {
            val rawConfig = Files.readString(path)
            val cleanedConfig = rawConfig.lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(System.lineSeparator())
            gson.fromJson<T>(cleanedConfig, type) ?: defaultValue
        }.getOrElse {
            ThrowerListMod.logger.error("Failed to load $label, using defaults", it)
            defaultValue
        }
    }

    data class SettingsConfig(
        var enabled: Boolean? = true,
        var localAutokickEnabled: Boolean? = true,
        var remoteAutokickEnabled: Boolean? = false,
        var localAutokickTemplate: String? = defaultLocalAutokickTemplate,
        var remoteAutokickTemplate: String? = defaultRemoteAutokickTemplate,
        var lobbyNotifications: Boolean = true,
        var assumePartyLeader: Boolean = false,
        var hypixelApiKey: String? = null,
        var uiTheme: String? = "ocean",
        var remoteScammerChecksEnabled: Boolean? = true,
        var autoCheckPartyMembersEnabled: Boolean? = true,
        var autoCheckOnJoinEnabled: Boolean? = true,
        var miscIgnoreListEnabled: Boolean = false,
        var miscIgnoredUsernames: MutableList<String> = mutableListOf(),
        var scammerStorageDuration: String? = null,
        var scammerAutokickEnabled: Boolean? = false,
        var announceScammerHitsEnabled: Boolean? = false,
        var scammerOnlyNotifyEnabled: Boolean? = true,
        var tradeScammerPopupEnabled: Boolean? = true,
        var swingSpeedEnabled: Boolean = false,
        var swingSpeedValue: Float = 1.0f,
        var dungeonAutokick: DungeonAutokickSettings = DungeonAutokickSettings(),
    )

    data class DungeonAutokickSettings(
        var enabled: Boolean = false,
        var pbThresholds: MutableMap<String, String?> = linkedMapOf(),
        var noPrinceAttributeShard: Boolean = false,
        var noSpiritPet: Boolean = false,
        var thornsOnEquippedArmourSet: Boolean = false,
    ) {
        fun normalized(): DungeonAutokickSettings {
            pbThresholds = pbThresholds.entries
                .mapNotNull { (key, value) ->
                    val normalizedKey = key.trim().uppercase().takeIf { it.matches(Regex("""F7|M[1-7]""")) } ?: return@mapNotNull null
                    normalizedKey to value?.trim()?.takeIf { it.isNotEmpty() }
                }
                .associateTo(linkedMapOf()) { it }
            return this
        }

        fun hasConfiguredChecks(): Boolean =
            pbThresholds.values.any { !it.isNullOrBlank() } || noPrinceAttributeShard || noSpiritPet || thornsOnEquippedArmourSet
    }

    data class ImportConfig(
        var hiddenRemoteUuids: MutableSet<String> = linkedSetOf(),
        var remoteImportedTimestamps: MutableMap<String, Long> = linkedMapOf(),
    )

    data class ImportPlayersResult(
        val importedCount: Int,
        val skippedCount: Int,
    )

    private data class LookupCaches(
        val version: Long = 0L,
        val localListedUsernames: Set<String> = emptySet(),
        val localIgnoredUsernames: Set<String> = emptySet(),
        val miscIgnoredUsernames: List<String> = emptyList(),
        val miscIgnoredUsernameSet: Set<String> = emptySet(),
        val hiddenRemoteUuids: Set<String> = emptySet(),
    )

    private data class LegacyThrowerListConfig(
        var enabled: Boolean = true,
        var lobbyNotifications: Boolean = true,
        var assumePartyLeader: Boolean = false,
        var hypixelApiKey: String? = null,
        var players: MutableList<PlayerEntry> = mutableListOf(),
        var hiddenRemoteUuids: MutableSet<String> = linkedSetOf(),
        var remoteImportedTimestamps: MutableMap<String, Long> = linkedMapOf(),
    )
}
