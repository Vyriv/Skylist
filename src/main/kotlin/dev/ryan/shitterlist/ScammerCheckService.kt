package dev.ryan.throwerlist

import com.google.gson.reflect.TypeToken
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.text.Text
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

object ScammerCheckService {
    private const val requestCooldownMillis = 10 * 60 * 1000L
    private const val requestSpacingMillis = 800L
    private const val negativeCacheMillis = 3L * 24 * 60 * 60 * 1000
    private val verdictPath: Path = FabricLoader.getInstance().configDir.resolve("throwerlist").resolve("scammer_verdicts.json")

    private val executor = Executors.newSingleThreadExecutor()
    private val queue = LinkedBlockingQueue<QueuedRequest>()
    private val running = AtomicBoolean(false)
    private val verdicts = linkedMapOf<String, CachedVerdict>()
    private val lastRequestByKey = linkedMapOf<String, Long>()

    fun start() {
        loadCache()
        if (running.compareAndSet(false, true)) {
            executor.execute(::runLoop)
        }
    }

    fun checkTarget(target: String, source: CheckSource, progress: ((Int, Int, String) -> Unit)? = null): CompletableFuture<CheckOutcome> {
        val future = CompletableFuture<CheckOutcome>()
        queue.put(QueuedRequest(target.trim(), source, future, progress))
        return future
    }

    fun checkBatch(targets: Collection<String>, source: CheckSource, progress: ((Int, Int, String) -> Unit)? = null): CompletableFuture<List<CheckOutcome>> {
        val cleaned = targets.map(String::trim).filter(String::isNotEmpty).distinctBy { it.lowercase(Locale.ROOT) }
        if (cleaned.isEmpty()) {
            return CompletableFuture.completedFuture(emptyList())
        }

        val combined = CompletableFuture<List<CheckOutcome>>()
        val outcomes = mutableListOf<CheckOutcome>()
        var completed = 0
        cleaned.forEach { target ->
            checkTarget(target, source) { _, _, _ -> }.whenComplete { result, throwable ->
                synchronized(outcomes) {
                    completed++
                    if (throwable == null && result != null) {
                        outcomes += result
                    }
                    progress?.invoke(completed, cleaned.size, target)
                    if (completed == cleaned.size && !combined.isDone) {
                        combined.complete(outcomes.toList())
                    }
                }
            }
        }
        return combined
    }

    fun invalidateEntry(entry: ScammerListManager.ScammerEntry) {
        invalidateResolvedTarget(TargetType.UUID, normalizeUuid(entry.uuid))
        entry.discordIds.forEach { invalidateResolvedTarget(TargetType.DISCORD, it) }
    }

    fun invalidateUuid(uuid: String) {
        invalidateResolvedTarget(TargetType.UUID, normalizeUuid(uuid))
    }

    private fun runLoop() {
        while (true) {
            val request = queue.take()
            try {
                process(request)
            } catch (throwable: Throwable) {
                request.future.completeExceptionally(throwable)
            }
            Thread.sleep(requestSpacingMillis)
        }
    }

    private fun process(request: QueuedRequest) {
        if (!ConfigManager.isRemoteScammerChecksEnabled()) {
            request.future.complete(CheckOutcome(request.target, null, CheckState.SKIPPED))
            return
        }

        resolveTarget(request.target).thenAccept { resolved ->
            val key = cacheKeyFor(resolved)
            val cached = cachedVerdictFor(key)
            if (cached != null) {
                val cachedResult = cached.toResult()
                if (!cached.positive || cachedResult != null) {
                    request.future.complete(
                        CheckOutcome(
                            target = request.target,
                            verdict = cachedResult,
                            state = if (cached.positive) CheckState.HIT else CheckState.CLEAR,
                        ),
                    )
                    return@thenAccept
                }
            }

            val now = System.currentTimeMillis()
            val lastRequestedAt = synchronized(lastRequestByKey) { lastRequestByKey[key] }
            if (lastRequestedAt != null && now - lastRequestedAt < requestCooldownMillis) {
                request.future.complete(CheckOutcome(request.target, null, CheckState.COOLDOWN))
                return@thenAccept
            }
            synchronized(lastRequestByKey) {
                lastRequestByKey[key] = now
            }

            val queryFuture = when (resolved.type) {
                TargetType.DISCORD -> ScammerListManager.queryByDiscordId(resolved.value)
                TargetType.UUID -> ScammerListManager.queryByUuid(resolved.value)
            }
            queryFuture.whenComplete { entry, throwable ->
                if (throwable != null) {
                    request.future.complete(CheckOutcome(request.target, null, CheckState.ERROR))
                    return@whenComplete
                }

                val failureReason = ScammerListManager.lastFailureReason()
                if (failureReason != null && entry == null) {
                    request.future.complete(CheckOutcome(request.target, null, CheckState.ERROR))
                    return@whenComplete
                }

                if (entry != null) {
                    val result = CheckResult(
                        username = resolveDisplayName(entry.username, entry.uuid),
                        sourceLabel = "SBZ scammer",
                        reason = entry.reason,
                        entry = entry,
                        severityColor = entry.severity.color,
                        caseTimeMillis = entry.creationTimeMillis,
                    )
                    storeVerdict(key, result, positive = true)
                    request.future.complete(CheckOutcome(request.target, result, CheckState.HIT))
                } else {
                    storeVerdict(key, null, positive = false)
                    request.future.complete(CheckOutcome(request.target, null, CheckState.CLEAR))
                }
            }
        }.exceptionally {
            request.future.complete(CheckOutcome(request.target, null, CheckState.ERROR))
            null
        }
    }

    private fun resolveTarget(target: String): CompletableFuture<ResolvedTarget> {
        val trimmed = target.trim()
        return when {
            isDiscordId(trimmed) -> CompletableFuture.completedFuture(ResolvedTarget(TargetType.DISCORD, trimmed))
            isUuid(trimmed) -> CompletableFuture.completedFuture(ResolvedTarget(TargetType.UUID, normalizeUuid(trimmed)))
            else -> UsernameResolver.resolve(trimmed).thenApply { resolved ->
                ResolvedTarget(TargetType.UUID, normalizeUuid(resolved?.uuid ?: trimmed))
            }
        }
    }

    private fun resolveDisplayName(username: String, uuid: String): String {
        return if (!isUuid(username)) {
            username
        } else {
            runCatching { UsernameResolver.resolveUuid(uuid).join() }.getOrNull() ?: username
        }
    }

    private fun cachedVerdictFor(key: String): CachedVerdict? {
        val now = System.currentTimeMillis()
        synchronized(verdicts) {
            val verdict = verdicts[key] ?: return null
            if (verdict.expiresAt != null && verdict.expiresAt <= now) {
                verdicts.remove(key)
                saveCache()
                return null
            }
            return verdict
        }
    }

    private fun storeVerdict(key: String, result: CheckResult?, positive: Boolean) {
        val expiresAt = if (positive) {
            ConfigManager.getScammerStorageDuration()?.let { duration ->
                EntryExpiry.parse(duration)?.applyTo(System.currentTimeMillis())
            }
        } else {
            System.currentTimeMillis() + negativeCacheMillis
        }
        synchronized(verdicts) {
            verdicts[key] = CachedVerdict(
                positive = positive,
                username = result?.username,
                sourceLabel = result?.sourceLabel,
                reason = result?.reason,
                uuid = result?.entry?.uuid,
                expiresAt = expiresAt,
            )
        }
        saveCache()
    }

    private fun invalidateResolvedTarget(type: TargetType, value: String) {
        val key = cacheKeyFor(ResolvedTarget(type, value))
        synchronized(verdicts) {
            verdicts.remove(key)
        }
        synchronized(lastRequestByKey) {
            lastRequestByKey.remove(key)
        }
        saveCache()
    }

    private fun cacheKeyFor(target: ResolvedTarget): String = "${target.type.name.lowercase(Locale.ROOT)}:${target.value.lowercase(Locale.ROOT)}"

    private fun loadCache() {
        if (Files.notExists(verdictPath)) {
            return
        }
        runCatching {
            val type = object : TypeToken<MutableMap<String, CachedVerdict>>() {}.type
            val loaded = ConfigManager.gson.fromJson<MutableMap<String, CachedVerdict>>(Files.readString(verdictPath), type).orEmpty()
            synchronized(verdicts) {
                verdicts.clear()
                verdicts.putAll(loaded)
            }
        }.onFailure {
            ThrowerListMod.logger.warn("Failed to load scammer verdict cache", it)
        }
    }

    private fun saveCache() {
        runCatching {
            Files.createDirectories(verdictPath.parent)
            synchronized(verdicts) {
                Files.writeString(verdictPath, ConfigManager.gson.toJson(verdicts))
            }
        }.onFailure {
            ThrowerListMod.logger.warn("Failed to save scammer verdict cache", it)
        }
    }

    private fun normalizeUuid(uuid: String): String {
        val trimmed = uuid.trim().lowercase(Locale.ROOT)
        if ('-' in trimmed || trimmed.length != 32) {
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

    data class CheckResult(
        val username: String,
        val sourceLabel: String,
        val reason: String,
        val entry: ScammerListManager.ScammerEntry?,
        val severityColor: Int?,
        val caseTimeMillis: Long?,
    )

    data class CheckOutcome(
        val target: String,
        val verdict: CheckResult?,
        val state: CheckState,
    )

    enum class CheckState {
        HIT,
        CLEAR,
        COOLDOWN,
        ERROR,
        SKIPPED,
    }

    enum class CheckSource {
        SLASH_COMMAND,
        PREFIX_COMMAND,
        PARTY_JOIN,
        PARTY_SYNC,
        TRADE,
        PARTY_FINDER,
    }

    private data class QueuedRequest(
        val target: String,
        val source: CheckSource,
        val future: CompletableFuture<CheckOutcome>,
        val progress: ((Int, Int, String) -> Unit)?,
    )

    private data class ResolvedTarget(
        val type: TargetType,
        val value: String,
    )

    private enum class TargetType {
        UUID,
        DISCORD,
    }

    data class CachedVerdict(
        val positive: Boolean,
        val username: String?,
        val sourceLabel: String?,
        val reason: String?,
        val uuid: String?,
        val expiresAt: Long?,
    ) {
        fun toResult(): CheckResult? =
            if (!positive) {
                null
            } else {
                val liveEntry = uuid?.let { ScammerListManager.findEntryByUuid(it) }
                val result = CheckResult(
                    username = liveEntry?.username ?: username ?: uuid ?: "unknown",
                    sourceLabel = sourceLabel ?: "SBZ scammer",
                    reason = reason ?: "Unknown",
                    entry = liveEntry,
                    severityColor = liveEntry?.severity?.color,
                    caseTimeMillis = liveEntry?.creationTimeMillis,
                )
                if (result.entry != null || uuid == null) result else null
            }
    }
}
