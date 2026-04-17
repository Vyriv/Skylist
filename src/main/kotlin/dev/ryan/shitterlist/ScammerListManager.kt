package dev.ryan.throwerlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
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
    private val cachePath: Path = FabricLoader.getInstance().configDir.resolve("throwerlist").resolve("scammers.json")

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

    fun listEntries(): List<ScammerEntry> = scammers.values.sortedByDescending { it.creationTimeSeconds }

    fun findEntryByUuid(uuid: String): ScammerEntry? = scammers[normalizeUuid(uuid)]

    fun findEntryByUsername(username: String): ScammerEntry? =
        scammers.values.firstOrNull { it.username.equals(username, ignoreCase = true) }

    fun findEntryByDiscordId(discordId: String): ScammerEntry? =
        scammers.values.firstOrNull { entry -> entry.discordIds.any { it == discordId } }

    fun lastRefreshCompletedAt(): Long? = lastRefreshCompletedAt

    fun lastFailureReason(): String? = lastFailureReason

    fun refreshAsync(): CompletableFuture<Unit> =
        CompletableFuture.runAsync {
            loadCache()
        }.thenApply { Unit }

    fun removeCachedEntry(uuid: String): Boolean {
        val removed = scammers.remove(normalizeUuid(uuid))
        if (removed != null) {
            ScammerCheckService.invalidateEntry(removed)
            saveCache()
        }
        return removed != null
    }

    fun addTestScammer(username: String, uuid: String, reason: String, createdAtMillis: Long): ScammerEntry {
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
            creationTimeSeconds = (createdAtMillis / 1000L).coerceAtLeast(0L),
            severity = calculateSeverity(reason, 0, 0, (createdAtMillis / 1000L).coerceAtLeast(0L)),
        )
        storeScammer(entry)
        return entry
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
            .uri(URI.create(WorkerRelay.relayBaseUrl + path))
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
        saveCache()
        resolveUsernames(entry)
    }

    private fun loadCache() {
        if (Files.notExists(cachePath)) {
            return
        }

        runCatching {
            val type = object : TypeToken<List<ScammerEntry>>() {}.type
            val loaded = ConfigManager.gson.fromJson<List<ScammerEntry>>(Files.readString(cachePath), type).orEmpty()
            scammers.clear()
            loaded.forEach { entry ->
                scammers[normalizeUuid(entry.uuid)] = entry.copy(uuid = normalizeUuid(entry.uuid))
                resolveUsernames(entry)
            }
        }.onFailure {
            ThrowerListMod.logger.warn("Failed to load scammer cache", it)
        }
    }

    private fun saveCache() {
        runCatching {
            Files.createDirectories(cachePath.parent)
            Files.writeString(cachePath, ConfigManager.gson.toJson(listEntries()))
        }.onFailure {
            ThrowerListMod.logger.warn("Failed to save scammer cache", it)
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
            severity = calculateSeverity(reason, altUuids.size, discordIds.size, creationTimeSeconds),
        )
    }

    private fun calculateSeverity(reason: String, altCount: Int, discordCount: Int, creationTimeSeconds: Long): Severity {
        val normalizedReason = reason.lowercase()
        val ageDays = ((System.currentTimeMillis() / 1000) - creationTimeSeconds).coerceAtLeast(0) / 86400
        var score = amountSeverityScore(parseLargestAmountCoins(normalizedReason))

        score += when {
            ageDays <= 30 -> 1
            ageDays > 365 * 3 -> -2
            ageDays > 365 -> -1
            else -> 0
        }

        score += when {
            altCount >= 2 -> 2
            altCount == 1 -> 1
            else -> 0
        }
        if (discordCount > 1) {
            score += 1
        }
        if (listOf("account", "coop", "rat", "hacked", "stolen").any { it in normalizedReason }) {
            score += 2
        }
        if (listOf("hyperion", "hype", "term", "terminator", "gdrag", "edrag", "chimera", "handle", "scroll", "core", "collat", "collateral", "essence").any { it in normalizedReason }) {
            score += 1
        }

        if (ageDays <= 14) {
            score += 0
        }
        return when {
            score >= 5 -> Severity.CRITICAL
            score >= 4 -> Severity.HIGH
            score >= 2 -> Severity.MEDIUM
            else -> Severity.LOW
        }
    }

    private fun amountSeverityScore(amountCoins: Double?): Int {
        val amount = amountCoins ?: return 0
        return when {
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
                    existing.copy(username = username)
                }
                scammers.replaceAll { _, existing ->
                    if (existing.altUuids.none { it.equals(normalizedUuid, ignoreCase = true) }) {
                        existing
                    } else {
                        existing.copy(altUsernames = existing.altUuids.map { altUuid -> usernameCache[altUuid] ?: altUuid })
                    }
                }
                saveCache()
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
        val severity: Severity,
    ) {
        val creationTimeMillis: Long?
            get() = creationTimeSeconds.takeIf { it > 0L }?.times(1000L)
    }

    enum class Severity(val label: String, val color: Int) {
        LOW("Low", 0xFFAAAAAA.toInt()),
        MEDIUM("Medium", 0xFFFFD966.toInt()),
        HIGH("High", 0xFFFFA347.toInt()),
        CRITICAL("Critical", 0xFFFF6B6B.toInt()),
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
