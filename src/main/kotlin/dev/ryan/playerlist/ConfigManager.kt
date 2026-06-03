package dev.ryan.playerlist

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.text.buildString

object ConfigManager {
    const val scammerStorageDisabledValue = "none"
    internal const val defaultLocalAutokickTemplate = "[SL] <IGN> is on Vyriv's Skylist for <REASON>"
    internal const val defaultRemoteAutokickTemplate = "[SL] <IGN> is on Vyriv's Skylist for <REASON>. Appeal at gg/4ZSFKWSY65"
    val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val configDirectoryPath: Path = FabricLoader.getInstance().configDir.resolve("playerlist")
    private val settingsPath: Path = configDirectoryPath.resolve("config.json")
    private val playersPath: Path = configDirectoryPath.resolve("playerlist.json")
    private val importPath: Path = configDirectoryPath.resolve("tlimport.json")
    private val previousConfigDirectoryPath: Path = FabricLoader.getInstance().configDir.resolve("PlayerList")
    private val previousSettingsPath: Path = previousConfigDirectoryPath.resolve("config.json")
    private val previousPlayersPath: Path = previousConfigDirectoryPath.resolve("PlayerList.json")
    private val previousImportPath: Path = previousConfigDirectoryPath.resolve("tlimport.json")
    // Legacy throwerlist identifier retained only for backwards compatibility.
    private val legacyConfigDirectoryPath: Path = FabricLoader.getInstance().configDir.resolve("throwerlist")
    private val legacySettingsPath: Path = legacyConfigDirectoryPath.resolve("config.json")
    private val legacyPlayersPath: Path = legacyConfigDirectoryPath.resolve("throwerlist.json")
    private val legacyImportPath: Path = legacyConfigDirectoryPath.resolve("tlimport.json")
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

        if (!hasAnyConfigState()) {
            settings = SettingsConfig()
            players = mutableListOf()
            importState = ImportConfig()
            save()
            return settings.copy()
        }

        settings = readSettingsConfig(listOf(settingsPath, previousSettingsPath, legacySettingsPath))
        players = readPlayerEntries(listOf(playersPath, previousPlayersPath, legacyPlayersPath))
        importState = readImportConfig(listOf(importPath, previousImportPath, legacyImportPath))
        normalizeSettings()
        normalizePlayers()
        normalizeImportState()
        deleteLegacyHistoryFile()
        if (pruneExpiredEntriesLocked() > 0) {
            writeState()
        }
        rebuildLookupCachesLocked()
        if (usedLegacyStorage()) {
            writeState()
        }

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
    fun isAutokickEnabled(isRemote: Boolean): Boolean =
        if (isRemote) {
            settings.remoteAutokickEnabled ?: false
        } else {
            settings.localAutokickEnabled ?: false
        }

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
        if (isRemote) {
            settings.remoteAutokickTemplate ?: defaultRemoteAutokickTemplate
        } else {
            settings.localAutokickTemplate ?: defaultLocalAutokickTemplate
        }

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
    fun isCustomCapesEnabled(): Boolean = !settings.customCapesDisabled

    @Synchronized
    fun toggleCustomCapesDisabled(): Boolean {
        settings.customCapesDisabled = !settings.customCapesDisabled
        save()
        return settings.customCapesDisabled
    }

    @Synchronized
    fun isCustomScalerEnabled(): Boolean = !settings.customScalerDisabled

    @Synchronized
    fun toggleCustomScalerDisabled(): Boolean {
        settings.customScalerDisabled = !settings.customScalerDisabled
        save()
        return settings.customScalerDisabled
    }

    @Synchronized
    fun isDeveloperIdentifierEnabled(): Boolean = settings.developerIdentifierEnabled

    @Synchronized
    fun toggleDeveloperIdentifierEnabled(): Boolean {
        settings.developerIdentifierEnabled = !settings.developerIdentifierEnabled
        save()
        return settings.developerIdentifierEnabled
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
    fun getScammerLogOnlyThreshold(): Double =
        settings.scammerLogOnlyThreshold?.takeIf { it > 0.0 } ?: ScammerListManager.DEFAULT_LOG_ONLY_THRESHOLD

    @Synchronized
    fun setScammerLogOnlyThreshold(value: Double?): Double {
        settings.scammerLogOnlyThreshold = value?.takeIf { it > 0.0 } ?: ScammerListManager.DEFAULT_LOG_ONLY_THRESHOLD
        save()
        ScammerListManager.recomputeSeverityResults()
        return getScammerLogOnlyThreshold()
    }

    @Synchronized
    fun getScammerAutokickThreshold(): ScammerListManager.ScammerSeverity =
        parseScammerSeverity(settings.scammerAutokickThreshold) ?: ScammerListManager.ScammerSeverity.CRITICAL

    @Synchronized
    fun setScammerAutokickThreshold(value: ScammerListManager.ScammerSeverity): ScammerListManager.ScammerSeverity {
        settings.scammerAutokickThreshold = value.name
        save()
        return getScammerAutokickThreshold()
    }

    @Synchronized
    fun getScammerWarningThreshold(): ScammerListManager.ScammerSeverity =
        parseScammerSeverity(settings.scammerWarningThreshold) ?: ScammerListManager.ScammerSeverity.MEDIUM

    @Synchronized
    fun setScammerWarningThreshold(value: ScammerListManager.ScammerSeverity): ScammerListManager.ScammerSeverity {
        settings.scammerWarningThreshold = value.name
        save()
        return getScammerWarningThreshold()
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
    fun isDungeonRouterCheckEnabled(): Boolean = settings.dungeonAutokick.checkForRouters

    @Synchronized
    fun setDungeonRouterCheckEnabled(enabled: Boolean): Boolean {
        settings.dungeonAutokick.checkForRouters = enabled
        save()
        return isDungeonRouterCheckEnabled()
    }

    @Synchronized
    fun getDungeonRouterAction(): String = normalizeDungeonRouterAction(settings.dungeonAutokick.routerAction)

    @Synchronized
    fun setDungeonRouterAction(value: String): String {
        settings.dungeonAutokick.routerAction = normalizeDungeonRouterAction(value)
        save()
        return getDungeonRouterAction()
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
        entry.tags = PlayerListTags.normalize(tags)
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
        entry.tags = PlayerListTags.normalize(newTags)
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
            Files.notExists(previousSettingsPath) &&
            Files.notExists(previousPlayersPath) &&
            Files.notExists(previousImportPath) &&
            Files.exists(legacyConfigPath)

    private fun hasAnyConfigState(): Boolean =
        Files.exists(settingsPath) ||
            Files.exists(playersPath) ||
            Files.exists(importPath) ||
            Files.exists(previousSettingsPath) ||
            Files.exists(previousPlayersPath) ||
            Files.exists(previousImportPath) ||
            Files.exists(legacySettingsPath) ||
            Files.exists(legacyPlayersPath) ||
            Files.exists(legacyImportPath) ||
            Files.exists(legacyConfigPath)

    private fun usedLegacyStorage(): Boolean =
        (Files.notExists(settingsPath) && Files.exists(previousSettingsPath)) ||
            (Files.notExists(playersPath) && Files.exists(previousPlayersPath)) ||
            (Files.notExists(importPath) && Files.exists(previousImportPath)) ||
            (Files.notExists(settingsPath) && Files.exists(legacySettingsPath)) ||
            (Files.notExists(playersPath) && Files.exists(legacyPlayersPath)) ||
            (Files.notExists(importPath) && Files.exists(legacyImportPath)) ||
            shouldMigrateLegacyConfig()

    private fun migrateLegacyConfig() {
        val legacyConfig = runCatching {
            val rawConfig = Files.readString(legacyConfigPath)
            val cleanedConfig = rawConfig.lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(System.lineSeparator())
            gson.fromJson(cleanedConfig, LegacyThrowerListConfig::class.java) ?: LegacyThrowerListConfig()
        }.getOrElse {
            PlayerListMod.logger.error("Failed to migrate legacy PlayerList config, using defaults", it)
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
        settings.localAutokickTemplate = normalizeOptionalText(
            settings.localAutokickTemplate,
            defaultLocalAutokickTemplate,
        )
        settings.remoteAutokickTemplate = normalizeOptionalText(
            settings.remoteAutokickTemplate,
            defaultRemoteAutokickTemplate,
        )
        settings.uiTheme = normalizeOptionalLowercaseText(settings.uiTheme, "ocean")
        settings.remoteScammerChecksEnabled = settings.remoteScammerChecksEnabled ?: true
        settings.autoCheckPartyMembersEnabled = settings.autoCheckPartyMembersEnabled ?: true
        settings.autoCheckOnJoinEnabled = settings.autoCheckOnJoinEnabled ?: true
        settings.miscIgnoreListEnabled = settings.miscIgnoreListEnabled
        settings.miscIgnoredUsernames = normalizeIgnoredUsernames(settings.miscIgnoredUsernames)
        settings.scammerStorageDuration = settings.scammerStorageDuration
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { normalizedDuration ->
                if (normalizedDuration.equals(scammerStorageDisabledValue, ignoreCase = true)) {
                    scammerStorageDisabledValue
                } else {
                    normalizedDuration
                }
            }
        settings.scammerAutokickEnabled = settings.scammerAutokickEnabled ?: false
        settings.scammerLogOnlyThreshold =
            settings.scammerLogOnlyThreshold?.takeIf { thresholdValue -> thresholdValue > 0.0 }
                ?: ScammerListManager.DEFAULT_LOG_ONLY_THRESHOLD
        settings.scammerAutokickThreshold =
            parseScammerSeverity(settings.scammerAutokickThreshold)?.name
                ?: ScammerListManager.ScammerSeverity.CRITICAL.name
        settings.announceScammerHitsEnabled = settings.announceScammerHitsEnabled ?: false
        settings.scammerWarningThreshold =
            parseScammerSeverity(settings.scammerWarningThreshold)?.name
                ?: ScammerListManager.ScammerSeverity.MEDIUM.name
        settings.scammerOnlyNotifyEnabled = settings.scammerOnlyNotifyEnabled ?: true
        settings.tradeScammerPopupEnabled = settings.tradeScammerPopupEnabled ?: true
        settings.dungeonAutokick = settings.dungeonAutokick.normalized()
        settings.enabled = isAutokickEnabled()
    }

    private fun normalizeOptionalText(value: String?, defaultValue: String): String =
        value?.trim()?.takeIf { trimmedValue -> trimmedValue.isNotEmpty() } ?: defaultValue

    private fun normalizeOptionalLowercaseText(value: String?, defaultValue: String): String =
        value?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { trimmedValue -> trimmedValue.isNotEmpty() }
            ?: defaultValue

    private fun normalizeIgnoredUsernames(rawUsernames: List<String>): MutableList<String> {
        val normalizedUsernames = mutableListOf<String>()
        rawUsernames.forEach { rawUsername ->
            val trimmedUsername = rawUsername.trim()
            if (!trimmedUsername.matches(Regex("^[A-Za-z0-9_]{1,16}$"))) {
                return@forEach
            }
            if (normalizedUsernames.none { existingUsername -> existingUsername.equals(trimmedUsername, ignoreCase = true) }) {
                normalizedUsernames += trimmedUsername
            }
        }
        return normalizedUsernames
    }

    private fun parseScammerSeverity(value: String?): ScammerListManager.ScammerSeverity? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()?.let { raw ->
            ScammerListManager.ScammerSeverity.entries.firstOrNull { it.name == raw }
        }

    private fun normalizeDungeonRouterAction(value: String?): String =
        value?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf { it == "NOTHING" || it == "WARN" || it == "KICK" }
            ?: "WARN"

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
        listOf(
            configDirectoryPath.resolve("tlhistory.json"),
            legacyConfigDirectoryPath.resolve("tlhistory.json"),
        ).forEach { historyPath ->
            if (Files.exists(historyPath)) {
                Files.deleteIfExists(historyPath)
            }
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
        entry.tags = PlayerListTags.normalize(entry.tags)
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
        // This writes local JSON configuration data only. It never writes or executes code.
        writeJsonCacheFile(settingsPath, gson.toJson(settings))
        writeJsonCacheFile(playersPath, insertConfigComments(gson.toJson(players)))
        writeJsonCacheFile(importPath, gson.toJson(importState))
        deleteLegacyHistoryFile()
    }

    private fun readSettingsConfig(paths: List<Path>): SettingsConfig =
        readJsonValue(paths, SettingsConfig::class.java, SettingsConfig(), "settings")

    private fun readImportConfig(paths: List<Path>): ImportConfig =
        readJsonValue(paths, ImportConfig::class.java, ImportConfig(), "remote PlayerList state")

    private fun readPlayerEntries(paths: List<Path>): MutableList<PlayerEntry> {
        val cleanedConfig = readCleanConfigText(paths, "local PlayerList") ?: return mutableListOf()
        return runCatching {
            val playerEntries = gson.fromJson(cleanedConfig, Array<PlayerEntry>::class.java) ?: emptyArray()
            playerEntries.toMutableList()
        }.getOrElse {
            PlayerListMod.logger.error("Failed to load local PlayerList, using defaults", it)
            mutableListOf()
        }
    }

    private fun <T> readJsonValue(paths: List<Path>, valueClass: Class<T>, defaultValue: T, label: String): T {
        val cleanedConfig = readCleanConfigText(paths, label) ?: return defaultValue
        return runCatching {
            gson.fromJson(cleanedConfig, valueClass) ?: defaultValue
        }.getOrElse {
            PlayerListMod.logger.error("Failed to load $label, using defaults", it)
            defaultValue
        }
    }

    private fun readCleanConfigText(paths: List<Path>, label: String): String? {
        val path = paths.firstOrNull(Files::exists) ?: return null
        if (Files.notExists(path)) {
            return null
        }

        return runCatching {
            val rawConfig = Files.readString(path)
            rawConfig.lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(System.lineSeparator())
        }.getOrElse {
            PlayerListMod.logger.error("Failed to load $label, using defaults", it)
            null
        }
    }

}
