package dev.ryan.playerlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import net.fabricmc.loader.api.FabricLoader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object ScammerListManager {
    const val DEFAULT_LOG_ONLY_THRESHOLD = 2.5

    private val cachePath: Path = FabricLoader.getInstance().configDir.resolve("playerlist").resolve("scammers.json")
    // Legacy throwerlist identifier retained only for backwards compatibility.
    private val legacyCachePath: Path = FabricLoader.getInstance().configDir.resolve("throwerlist").resolve("scammers.json")

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private val scammers = ConcurrentHashMap<String, ScammerEntry>()
    private val usernameCache = ConcurrentHashMap<String, String>()

    @Volatile
    private var started = false

    @Volatile
    private var lastRefreshCompletedAt: Long? = null

    @Volatile
    private var lastFailureReason: String? = null

    fun start() {
        if (started) {
            return
        }

        started = true
        loadCache()
    }

    fun isConfigured(): Boolean = true

    fun listEntries(): List<ScammerEntry> = scammers.values.sortedWith(
        compareByDescending<ScammerEntry> { it.severityLevel.priority }
            .thenByDescending { it.severityResult.score }
            .thenByDescending { it.creationTimeSeconds },
    )

    fun findEntryByUuid(uuid: String): ScammerEntry? = scammers[normalizeUuid(uuid)]

    fun findEntryByUsername(username: String): ScammerEntry? =
        scammers.values.firstOrNull { it.username.equals(username, ignoreCase = true) }

    fun findEntryByDiscordId(discordId: String): ScammerEntry? =
        scammers.values.firstOrNull { entry -> entry.discordIds.any { it == discordId } }

    fun lastRefreshCompletedAt(): Long? = lastRefreshCompletedAt

    fun lastFailureReason(): String? = lastFailureReason

    fun getSeverity(entry: ScammerEntry): ScammerSeverity = entry.severityLevel

    fun getSeverityResult(entry: ScammerEntry): SeverityResult = entry.severityResult

    fun refreshAsync(): CompletableFuture<Unit> =
        CompletableFuture.runAsync {
            loadCache()
        }.thenApply { Unit }

    fun removeCachedEntry(uuid: String): Boolean {
        val removed = scammers.remove(normalizeUuid(uuid))
        if (removed != null) {
            ScammerCheckService.invalidateEntry(removed)
            saveJsonCacheToConfigDirectory()
        }
        return removed != null
    }

    fun addTestScammer(username: String, uuid: String, reason: String, createdAtMillis: Long): ScammerEntry {
        val creationTimeSeconds = (createdAtMillis / 1000L).coerceAtLeast(0L)
        val severityResult = calculateSeverityResult(reason, 0, 0, creationTimeSeconds)
        val entry = ScammerEntry(
            uuid = normalizeUuid(uuid),
            username = username,
            reason = reason,
            discordIds = emptyList(),
            discordUsers = emptyList(),
            altUuids = emptyList(),
            altUsernames = emptyList(),
            staffId = null,
            evidence = null,
            creationTimeSeconds = creationTimeSeconds,
            severityLevel = severityResult.severity,
            severityResult = severityResult,
        )
        storeScammer(entry)
        return entry
    }

    fun recomputeSeverityResults() {
        scammers.replaceAll { _, entry ->
            entry.withResolvedSeverity()
        }
        saveJsonCacheToConfigDirectory()
    }

    fun queryByTarget(target: String): CompletableFuture<ScammerEntry?> {
        val trimmed = target.trim()
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(null)
        }

        findLocalByTarget(trimmed)?.let {
            return CompletableFuture.completedFuture(it)
        }

        return when {
            isDiscordId(trimmed) -> queryByDiscordId(trimmed)
            isUuid(trimmed) -> queryByUuid(trimmed)
            else -> queryByUsername(trimmed)
        }
    }

    fun queryByUsername(username: String): CompletableFuture<ScammerEntry?> {
        findEntryByUsername(username)?.let { return CompletableFuture.completedFuture(it) }
        return UsernameResolver.resolve(username).thenCompose { resolved ->
            if (resolved == null) {
                CompletableFuture.completedFuture(null)
            } else {
                queryByUuid(resolved.uuid)
            }
        }
    }

    fun queryByUuid(uuid: String): CompletableFuture<ScammerEntry?> {
        findEntryByUuid(uuid)?.let { return CompletableFuture.completedFuture(it) }
        return CompletableFuture.supplyAsync {
            fetchAndCache("/scammers/${encodePath(normalizeUuid(uuid))}")
        }
    }

    fun queryByDiscordId(discordId: String): CompletableFuture<ScammerEntry?> {
        findEntryByDiscordId(discordId)?.let { return CompletableFuture.completedFuture(it) }
        return CompletableFuture.supplyAsync {
            fetchAndCache("/scammers/discord/${encodePath(discordId)}")
        }
    }

    private fun fetchAndCache(path: String): ScammerEntry? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(PlayerListLinks.skylistScammerApiBaseUrl + path))
            .timeout(Duration.ofSeconds(10))
            .header("accept", "*/*")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            lastFailureReason = parseFailureReason(response.body()) ?: "Unexpected response ${response.statusCode()}"
            return null
        }

        val root = JsonParser.parseString(response.body()).asJsonObject
        if (root.get("success")?.asBoolean != true) {
            lastFailureReason = root.getString("reason")
            return null
        }

        val details = root.get("details")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val scammerEntry = parseScammerEntry(details)
        if (scammerEntry != null) {
            storeScammer(scammerEntry)
        }
        lastFailureReason = null
        lastRefreshCompletedAt = System.currentTimeMillis()
        return scammerEntry
    }

    private fun findLocalByTarget(target: String): ScammerEntry? =
        when {
            isDiscordId(target) -> findEntryByDiscordId(target)
            isUuid(target) -> findEntryByUuid(target)
            else -> findEntryByUsername(target)
        }

    private fun storeScammer(entry: ScammerEntry) {
        scammers[normalizeUuid(entry.uuid)] = entry
        ScammerCheckService.invalidateEntry(entry)
        saveJsonCacheToConfigDirectory()
        resolveUsernames(entry)
    }

    private fun loadCache() {
        val loadPath = when {
            Files.exists(cachePath) -> cachePath
            Files.exists(legacyCachePath) -> legacyCachePath
            else -> return
        }

        runCatching {
            val loadedEntries = ConfigManager.gson.fromJson(Files.readString(loadPath), Array<ScammerEntry>::class.java)
                ?.toList()
                .orEmpty()
            scammers.clear()
            loadedEntries.forEach { entry ->
                val normalizedEntry = ScammerEntry(
                    uuid = normalizeUuid(entry.uuid),
                    username = entry.username,
                    reason = entry.reason,
                    discordIds = entry.discordIds,
                    discordUsers = entry.discordUsers,
                    altUuids = entry.altUuids.map(::normalizeUuid),
                    altUsernames = entry.altUsernames,
                    staffId = entry.staffId,
                    evidence = entry.evidence,
                    creationTimeSeconds = entry.creationTimeSeconds,
                    severityLevel = ScammerSeverity.LOW,
                    severityResult = SeverityResult(
                        severity = ScammerSeverity.LOW,
                        score = 0.0,
                        baseScore = 0,
                        recencyBonus = 0.0,
                        amountCoins = null,
                        ageDays = null,
                        reasons = emptyList(),
                        recommendedAction = ScammerRecommendedAction.NONE,
                    ),
                ).withResolvedSeverity()
                scammers[normalizedEntry.uuid] = normalizedEntry
                resolveUsernames(normalizedEntry)
            }
            if (loadPath == legacyCachePath) {
                saveJsonCacheToConfigDirectory()
            }
        }.onFailure {
            PlayerListMod.logger.warn("Failed to load scammer cache", it)
        }
    }

    // Writes the local scammer list as a plain JSON file inside the Skylist config directory.
    // The file contains only serialized ScammerEntry records fetched from the SBZ API.
    // It is a local config cache, not an executable payload, and is always written inside
    // FabricLoader configDir / playerlist — never outside it.
    private fun saveJsonCacheToConfigDirectory() {
        runCatching {
            // This writes local JSON cache data only. It never writes or executes code.
            writeJsonCacheFile(cachePath, ConfigManager.gson.toJson(listEntries()))
        }.onFailure {
            PlayerListMod.logger.warn("Failed to save scammer cache", it)
        }
    }

    private fun parseScammerEntry(json: JsonObject): ScammerEntry? {
        val uuid = json.getString("uuid") ?: return null
        val reason = json.getString("reason") ?: return null
        val altUuids = json.getStringArray("alts").map(::normalizeUuid)
        val discordIds = json.getStringArray("discordIds")
        val rawCreationTime = json.getLong("creationTime") ?: 0L
        val creationTimeSeconds = when {
            rawCreationTime > 9_999_999_999L -> rawCreationTime / 1000L
            rawCreationTime > 0L -> rawCreationTime
            else -> 0L
        }
        val severityResult = calculateSeverityResult(reason, altUuids.size, discordIds.size, creationTimeSeconds)
        return ScammerEntry(
            uuid = normalizeUuid(uuid),
            username = usernameCache[normalizeUuid(uuid)] ?: uuid,
            reason = reason,
            discordIds = discordIds,
            discordUsers = parseDiscordUsers(json, discordIds),
            altUuids = altUuids,
            altUsernames = altUuids.map { usernameCache[it] ?: it },
            staffId = json.getString("staff"),
            evidence = json.getString("evidence"),
            creationTimeSeconds = creationTimeSeconds,
            severityLevel = severityResult.severity,
            severityResult = severityResult,
        )
    }

    private fun calculateSeverityResult(reason: String, altCount: Int, discordCount: Int, creationTimeSeconds: Long): SeverityResult {
        val normalizedReason = reason.lowercase()
        val reasons = mutableListOf<String>()
        val amountCoins = parseLargestAmountCoins(normalizedReason)?.toLong()
        var baseScore = amountSeverityScore(amountCoins?.toDouble())

        if (amountCoins != null && baseScore > 0) {
            reasons += "Amount detected: ${formatAmount(amountCoins)} (+$baseScore)"
        }

        val scamTypeScore = scamTypeSeverityScore(normalizedReason)
        if (scamTypeScore > 0) {
            baseScore += scamTypeScore
            reasons += scamTypeReason(normalizedReason, scamTypeScore)
        }

        when {
            altCount >= 2 -> {
                baseScore += 2
                reasons += "2+ alts (+2)"
            }

            altCount == 1 -> {
                baseScore += 1
                reasons += "1 alt (+1)"
            }
        }

        if (discordCount > 1) {
            baseScore += 1
            reasons += "Multiple Discord IDs (+1)"
        }

        if (containsHighValueItem(normalizedReason)) {
            baseScore += 1
            reasons += "High-value item mentioned (+1)"
        }

        val ageDays = ageDaysFor(creationTimeSeconds)
        val recencyBonus = if (ageDays != null) {
            2.0 / (1.0 + ageDays.toDouble() / 90.0)
        } else {
            0.0
        }
        reasons += if (ageDays != null) {
            "Recent report bonus: +${formatScore(recencyBonus)}"
        } else {
            "Recent report bonus unavailable: case age unknown"
        }

        val score = baseScore.toDouble() + recencyBonus
        var severity = severityForScore(score)

        val minimumSeverity = minimumSeverity(normalizedReason, amountCoins, altCount)
        if (minimumSeverity != null && minimumSeverity.priority > severity.priority) {
            severity = minimumSeverity
            reasons += minimumSeverityReason(normalizedReason, amountCoins, altCount, minimumSeverity)
        }

        val recommendedAction = recommendedActionFor(score)
        if (recommendedAction == ScammerRecommendedAction.NONE) {
            reasons += "Below log-only threshold -> no action"
        }

        return SeverityResult(
            severity = severity,
            score = score,
            baseScore = baseScore,
            recencyBonus = recencyBonus,
            amountCoins = amountCoins,
            ageDays = ageDays,
            reasons = reasons,
            recommendedAction = recommendedAction,
        )
    }

    private fun amountSeverityScore(amountCoins: Double?): Int {
        val amount = amountCoins ?: return 0
        return when {
            amount >= 10_000_000_000.0 -> 5
            amount >= 1_000_000_000.0 -> 4
            amount >= 100_000_000.0 -> 3
            amount >= 10_000_000.0 -> 2
            amount >= 1_000_000.0 -> 1
            else -> 0
        }
    }

    private fun parseLargestAmountCoins(reason: String): Double? {
        val matches = mutableListOf<Double>()

        Regex("""(\d+(?:\.\d+)?)\s*([kmbt])\b""").findAll(reason).forEach { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val multiplier = when (match.groupValues[2]) {
                "k" -> 1_000.0
                "m" -> 1_000_000.0
                "b" -> 1_000_000_000.0
                "t" -> 1_000_000_000_000.0
                else -> 1.0
            }
            matches += value * multiplier
        }

        Regex("""(\d+(?:,\d{3})+(?:\.\d+)?)""").findAll(reason).forEach { match ->
            matches += match.value.replace(",", "").toDoubleOrNull() ?: return@forEach
        }

        Regex("""(\d+(?:\.\d+)?)\s*(million|billion|trillion|coins?)\b""").findAll(reason).forEach { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            val multiplier = when (match.groupValues[2]) {
                "million" -> 1_000_000.0
                "billion" -> 1_000_000_000.0
                "trillion" -> 1_000_000_000_000.0
                "coin", "coins" -> 1.0
                else -> 1.0
            }
            matches += value * multiplier
        }

        return matches.maxOrNull()
    }

    private fun parseDiscordUsers(json: JsonObject, fallbackIds: List<String>): List<DiscordUser> {
        val element = json.get("discordUsers")
        if (element != null && element.isJsonArray) {
            val parsed = element.asJsonArray.mapNotNull { item ->
                when {
                    item == null || item.isJsonNull -> null
                    item.isJsonPrimitive && item.asJsonPrimitive.isString -> {
                        val label = item.asString.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                        DiscordUser(id = label.removePrefix("@"), label = label)
                    }

                    item.isJsonObject -> {
                        val user = item.asJsonObject
                        val id = user.getString("id") ?: return@mapNotNull null
                        val label = user.getString("label") ?: user.getString("username")?.let { "@$it" } ?: "@$id"
                        DiscordUser(id = id, label = label)
                    }

                    else -> null
                }
            }
            if (parsed.isNotEmpty()) {
                return parsed
            }
        }

        return fallbackIds.map { DiscordUser(it, "@$it") }
    }

    private fun resolveUsernames(entry: ScammerEntry) {
        resolveUsername(entry.uuid)
        entry.altUuids.forEach(::resolveUsername)
    }

    private fun resolveUsername(uuid: String) {
        val normalizedUuid = normalizeUuid(uuid)
        if (usernameCache.containsKey(normalizedUuid)) {
            return
        }

        UsernameResolver.resolveUuid(normalizedUuid).thenAccept { username ->
            if (!username.isNullOrBlank()) {
                usernameCache[normalizedUuid] = username
                scammers.computeIfPresent(normalizedUuid) { _, existing ->
                    existing.copy(username = username).withResolvedSeverity()
                }
                scammers.replaceAll { _, existing ->
                    if (existing.altUuids.none { it.equals(normalizedUuid, ignoreCase = true) }) {
                        existing
                    } else {
                        existing.copy(altUsernames = existing.altUuids.map { altUuid -> usernameCache[altUuid] ?: altUuid }).withResolvedSeverity()
                    }
                }
                saveJsonCacheToConfigDirectory()
            }
        }
    }

    private fun parseFailureReason(body: String): String? =
        runCatching {
            JsonParser.parseString(body).asJsonObject.getString("reason")
        }.getOrNull()

    private fun encodePath(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun normalizeUuid(uuid: String): String {
        val trimmed = uuid.trim().lowercase()
        if ('-' in trimmed) {
            return trimmed
        }
        if (trimmed.length != 32) {
            return trimmed
        }
        return buildString(36) {
            append(trimmed.substring(0, 8))
            append('-')
            append(trimmed.substring(8, 12))
            append('-')
            append(trimmed.substring(12, 16))
            append('-')
            append(trimmed.substring(16, 20))
            append('-')
            append(trimmed.substring(20))
        }
    }

    private fun isUuid(value: String): Boolean =
        Regex("""^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$""").matches(value)

    private fun isDiscordId(value: String): Boolean =
        Regex("""^\d{17,20}$""").matches(value)

    data class ScammerEntry(
        val uuid: String,
        val username: String,
        val reason: String,
        val discordIds: List<String>,
        val discordUsers: List<DiscordUser>,
        val altUuids: List<String>,
        val altUsernames: List<String>,
        val staffId: String?,
        val evidence: String?,
        val creationTimeSeconds: Long,
        @SerializedName("severity")
        val severityLevel: ScammerSeverity,
        val severityResult: SeverityResult,
    ) {
        val creationTimeMillis: Long?
            get() = creationTimeSeconds.takeIf { it > 0L }?.times(1000L)

        fun withResolvedSeverity(): ScammerEntry {
            val resolved = calculateSeverityResult(reason, altUuids.size, discordIds.size, creationTimeSeconds)
            return copy(severityLevel = resolved.severity, severityResult = resolved)
        }

        @Suppress("unused")
        fun getSeverity(): Severity = Severity.from(severityLevel)
    }

    enum class Severity(val label: String, val color: Int) {
        LOW("Low", 0xFFAAAAAA.toInt()),
        MEDIUM("Medium", 0xFFFFD966.toInt()),
        HIGH("High", 0xFFFFA347.toInt()),
        CRITICAL("Critical", 0xFFFF6B6B.toInt());

        companion object {
            fun from(severity: ScammerSeverity): Severity =
                when (severity) {
                    ScammerSeverity.LOW -> LOW
                    ScammerSeverity.MEDIUM -> MEDIUM
                    ScammerSeverity.HIGH -> HIGH
                    ScammerSeverity.CRITICAL -> CRITICAL
                }
        }
    }

    enum class ScammerSeverity(val label: String, val color: Int, val priority: Int) {
        LOW("Low", 0xFFAAAAAA.toInt(), 0),
        MEDIUM("Medium", 0xFFFFD966.toInt(), 1),
        HIGH("High", 0xFFFFA347.toInt(), 2),
        CRITICAL("Critical", 0xFFFF6B6B.toInt(), 3),
    }

    enum class ScammerRecommendedAction {
        NONE,
        SUBTLE_WARN,
        WARN,
        KICK_RECOMMENDED,
        AUTO_KICK_ALLOWED,
    }

    data class SeverityResult(
        val severity: ScammerSeverity,
        val score: Double,
        val baseScore: Int,
        val recencyBonus: Double,
        val amountCoins: Long?,
        val ageDays: Long?,
        val reasons: List<String>,
        val recommendedAction: ScammerRecommendedAction,
    )

    private fun ageDaysFor(creationTimeSeconds: Long): Long? {
        if (creationTimeSeconds <= 0L) {
            return null
        }
        return ((System.currentTimeMillis() / 1000L) - creationTimeSeconds).coerceAtLeast(0L) / 86400L
    }

    private fun severityForScore(score: Double): ScammerSeverity =
        when {
            score >= 7.0 -> ScammerSeverity.CRITICAL
            score >= 5.0 -> ScammerSeverity.HIGH
            score >= 3.0 -> ScammerSeverity.MEDIUM
            else -> ScammerSeverity.LOW
        }

    private fun recommendedActionFor(score: Double): ScammerRecommendedAction =
        when {
            score < ConfigManager.getScammerLogOnlyThreshold() -> ScammerRecommendedAction.NONE
            score >= 7.0 -> ScammerRecommendedAction.AUTO_KICK_ALLOWED
            score >= 5.0 -> ScammerRecommendedAction.KICK_RECOMMENDED
            score >= 3.0 -> ScammerRecommendedAction.WARN
            else -> ScammerRecommendedAction.SUBTLE_WARN
        }

    private fun minimumSeverity(reason: String, amountCoins: Long?, altCount: Int): ScammerSeverity? =
        when {
            containsAccountTakeover(reason) -> ScammerSeverity.CRITICAL
            containsCoopScam(reason) -> ScammerSeverity.CRITICAL
            amountCoins != null && amountCoins >= 10_000_000_000L -> ScammerSeverity.CRITICAL
            amountCoins != null && amountCoins >= 1_000_000_000L -> ScammerSeverity.HIGH
            amountCoins != null && amountCoins >= 100_000_000L -> ScammerSeverity.MEDIUM
            altCount >= 2 -> ScammerSeverity.MEDIUM
            else -> null
        }

    private fun minimumSeverityReason(reason: String, amountCoins: Long?, altCount: Int, severity: ScammerSeverity): String =
        when {
            containsAccountTakeover(reason) -> "Minimum severity applied: account theft -> ${severity.label}"
            containsCoopScam(reason) -> "Minimum severity applied: coop scam -> ${severity.label}"
            amountCoins != null && amountCoins >= 10_000_000_000L -> "Minimum severity applied: 10b+ -> ${severity.label}"
            amountCoins != null && amountCoins >= 1_000_000_000L -> "Minimum severity applied: 1b+ -> ${severity.label}"
            amountCoins != null && amountCoins >= 100_000_000L -> "Minimum severity applied: 100m+ -> ${severity.label}"
            altCount >= 2 -> "Minimum severity applied: 2+ alts -> ${severity.label}"
            else -> "Minimum severity applied -> ${severity.label}"
        }

    private fun scamTypeSeverityScore(reason: String): Int =
        when {
            containsAccountTakeover(reason) -> 5
            containsCoopScam(reason) -> 5
            else -> 0
        }

    private fun scamTypeReason(reason: String, score: Int): String =
        when {
            containsAccountTakeover(reason) -> "Reason contains account theft (+$score)"
            containsCoopScam(reason) -> "Reason contains coop scam (+$score)"
            else -> "Reason pattern matched (+$score)"
        }

    private fun containsAccountTakeover(reason: String): Boolean =
        listOf("rat", "account", "hacked", "stolen", "token", "session", "malware").any { it in reason }

    private fun containsCoopScam(reason: String): Boolean =
        listOf("coop scam", "coop", "co-op", "co op").any { it in reason }

    private fun containsCollateralScam(reason: String): Boolean =
        listOf("collat", "collateral", "essence", "carry scam", "carry").any { it in reason }

    private fun containsTradeScam(reason: String): Boolean =
        listOf("trade scam", "trade", "scam").any { it in reason }

    private fun containsHighValueItem(reason: String): Boolean =
        listOf("hyperion", "hype", "term", "terminator", "gdrag", "edrag", "chimera", "handle", "scroll", "core", "necron", "wither blade").any { it in reason }

    private fun formatAmount(amountCoins: Long): String =
        when {
            amountCoins >= 1_000_000_000_000L -> "${trimDecimal(amountCoins / 1_000_000_000_000.0)}t"
            amountCoins >= 1_000_000_000L -> "${trimDecimal(amountCoins / 1_000_000_000.0)}b"
            amountCoins >= 1_000_000L -> "${trimDecimal(amountCoins / 1_000_000.0)}m"
            amountCoins >= 1_000L -> "${trimDecimal(amountCoins / 1_000.0)}k"
            else -> amountCoins.toString()
        }

    private fun formatScore(value: Double): String = trimDecimal(value)

    private fun trimDecimal(value: Double): String =
        if (value % 1.0 == 0.0) {
            value.toLong().toString()
        } else {
            String.format("%.2f", value).trimEnd('0').trimEnd('.')
        }

    data class DiscordUser(
        val id: String,
        val label: String,
    )

    private fun JsonObject.getString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.getLong(key: String): Long? =
        get(key)?.takeIf { !it.isJsonNull }?.asLong

    private fun JsonObject.getStringArray(key: String): List<String> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) {
            return emptyList()
        }
        return element.asJsonArray.mapNotNull { item ->
            item?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
