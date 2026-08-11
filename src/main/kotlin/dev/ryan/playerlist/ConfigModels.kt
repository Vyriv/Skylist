package dev.ryan.playerlist

import java.util.Locale

data class SettingsConfig(
    var enabled: Boolean? = true,
    var localAutokickEnabled: Boolean? = true,
    var remoteAutokickEnabled: Boolean? = false,
    var localAutokickTemplate: String? = ConfigManager.defaultLocalAutokickTemplate,
    var remoteAutokickTemplate: String? = ConfigManager.defaultRemoteAutokickTemplate,
    var lobbyNotifications: Boolean = true,
    var assumePartyLeader: Boolean = false,
    var customCapesDisabled: Boolean = false,
    var customScalerDisabled: Boolean = false,
    var hypixelApiKey: String? = null,
    var uiTheme: String? = "ocean",
    var remoteScammerChecksEnabled: Boolean? = true,
    var autoCheckPartyMembersEnabled: Boolean? = true,
    var autoCheckOnJoinEnabled: Boolean? = true,
    var miscIgnoreListEnabled: Boolean = false,
    var miscIgnoredUsernames: MutableList<String> = mutableListOf(),
    var scammerStorageDuration: String? = null,
    var scammerAutokickEnabled: Boolean? = false,
    var scammerLogOnlyThreshold: Double? = ScammerListManager.DEFAULT_LOG_ONLY_THRESHOLD,
    var scammerAutokickThreshold: String? = ScammerListManager.ScammerSeverity.CRITICAL.name,
    var announceScammerHitsEnabled: Boolean? = false,
    var scammerWarningThreshold: String? = ScammerListManager.ScammerSeverity.MEDIUM.name,
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
    var checkForRouters: Boolean = false,
    var routerAction: String = "WARN",
) {
    fun normalized(): DungeonAutokickSettings {
        pbThresholds = normalizeDungeonThresholds(pbThresholds)
        routerAction = normalizeRouterAction(routerAction)
        return this
    }

    fun hasConfiguredChecks(): Boolean =
        pbThresholds.values.any { thresholdValue -> !thresholdValue.isNullOrBlank() } ||
            noPrinceAttributeShard ||
            noSpiritPet ||
            thornsOnEquippedArmourSet

    private fun normalizeDungeonThresholds(rawThresholds: MutableMap<String, String?>): MutableMap<String, String?> {
        val normalizedThresholds = linkedMapOf<String, String?>()
        rawThresholds.forEach { (floorKey, thresholdValue) ->
            val normalizedKey = normalizeDungeonFloorKey(floorKey) ?: return@forEach
            normalizedThresholds[normalizedKey] = thresholdValue?.trim()?.takeIf { trimmedValue -> trimmedValue.isNotEmpty() }
        }
        return normalizedThresholds
    }

    private fun normalizeDungeonFloorKey(rawFloorKey: String): String? {
        val normalizedKey = rawFloorKey.trim().uppercase(Locale.ROOT)
        return normalizedKey.takeIf { floorKey -> floorKey.matches(Regex("""F7|M[1-7]""")) }
    }

    private fun normalizeRouterAction(rawAction: String): String {
        val normalizedAction = rawAction.trim().uppercase(Locale.ROOT)
        return when (normalizedAction) {
            "NOTHING", "WARN", "KICK" -> normalizedAction
            else -> "WARN"
        }
    }
}

data class ImportConfig(
    var hiddenRemoteUuids: MutableSet<String> = linkedSetOf(),
    var remoteImportedTimestamps: MutableMap<String, Long> = linkedMapOf(),
)

data class ImportPlayersResult(
    val importedCount: Int,
    val skippedCount: Int,
)

internal data class LookupCaches(
    val version: Long = 0L,
    val localListedUsernames: Set<String> = emptySet(),
    val localIgnoredUsernames: Set<String> = emptySet(),
    val miscIgnoredUsernames: List<String> = emptyList(),
    val miscIgnoredUsernameSet: Set<String> = emptySet(),
    val hiddenRemoteUuids: Set<String> = emptySet(),
)

internal data class LegacyThrowerListConfig(
    var enabled: Boolean = true,
    var lobbyNotifications: Boolean = true,
    var assumePartyLeader: Boolean = false,
    var hypixelApiKey: String? = null,
    var players: MutableList<PlayerEntry> = mutableListOf(),
    var hiddenRemoteUuids: MutableSet<String> = linkedSetOf(),
    var remoteImportedTimestamps: MutableMap<String, Long> = linkedMapOf(),
)
