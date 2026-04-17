package dev.ryan.throwerlist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

object UsernameResolver {
    private val executor = Executors.newCachedThreadPool()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .executor(executor)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun resolve(username: String): CompletableFuture<ResolvedProfile?> {
        return CompletableFuture.supplyAsync({
            val request = HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "https://api.mojang.com/users/profiles/minecraft/" +
                            URLEncoder.encode(username, StandardCharsets.UTF_8),
                    ),
                )
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                return@supplyAsync null
            }

            val json = JsonParser.parseString(response.body()).asJsonObject
            val resolvedName = json.getRequiredString("name")
            val rawUuid = json.getRequiredString("id")
            ResolvedProfile(resolvedName, undashedUuid(rawUuid).toString())
        }, executor).exceptionally {
            ThrowerListMod.logger.warn("Failed to resolve username {}", username, it)
            null
        }
    }

    fun resolveUuid(uuid: String): CompletableFuture<String?> {
        return CompletableFuture.supplyAsync({
            val normalizedUuid = uuid.replace("-", "")
            val direct = fetchJson(
                urls = listOf(
                    "https://api.mojang.com/user/profile/$normalizedUuid",
                    "https://sessionserver.mojang.com/session/minecraft/profile/$normalizedUuid",
                ),
            )?.getRequiredString("name")
            direct ?: resolveUuidViaRelay(uuid)
        }, executor).exceptionally {
            ThrowerListMod.logger.warn("Failed to resolve uuid {}: {}", uuid, it.rootCauseSummary())
            null
        }
    }

    fun resolveLinkedDiscord(uuid: String): CompletableFuture<LinkedDiscordLookup> {
        return CompletableFuture.supplyAsync({
            val normalizedUuid = uuid.lowercase().replace("-", "")
            val root = try {
                WorkerRelay.fetchJson("/hypixel/player/$normalizedUuid", timeoutSeconds = 8)
            } catch (throwable: Throwable) {
                throw IllegalStateException("Hypixel player relay lookup failed", throwable)
            }
                ?: return@supplyAsync LinkedDiscordLookup(null, "Hypixel player relay returned no player data")

            if (root.get("success")?.asBoolean != true) {
                return@supplyAsync LinkedDiscordLookup(
                    discord = null,
                    failureReason = root.getOptionalString("cause") ?: root.getOptionalString("message") ?: "Lookup failed",
                )
            }

            val player = root.getAsJsonObject("player")
                ?: return@supplyAsync LinkedDiscordLookup(null, "Hypixel player relay returned no player data")
            val socialMedia = player.getAsJsonObject("socialMedia")
                ?: return@supplyAsync LinkedDiscordLookup(null, null)
            val links = socialMedia.getAsJsonObject("links")
            val linkedDiscord = links?.getOptionalString("DISCORD")
                ?: parseRawSocialMediaValue(socialMedia.getOptionalString("DISCORD"))

            LinkedDiscordLookup(linkedDiscord, null)
        }, executor).exceptionally {
            ThrowerListMod.logger.warn("Failed to resolve linked Discord for uuid {}", uuid, it)
            LinkedDiscordLookup(null, it.rootCauseSummary())
        }
    }

    private fun JsonObject.getRequiredString(key: String): String = get(key)?.asString ?: error("Missing '$key'")

    private fun JsonObject.getOptionalString(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseRawSocialMediaValue(value: String?): String? =
        value
            ?.substringAfter(';', value)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun fetchJson(
        urls: List<String>,
        timeout: Duration = Duration.ofSeconds(5),
        attemptsPerUrl: Int = 2,
    ): JsonObject? {
        var lastFailure: Throwable? = null

        for (url in urls) {
            repeat(attemptsPerUrl) { attempt ->
                try {
                    val request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .GET()
                        .build()

                    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                    when (response.statusCode()) {
                        200 -> return JsonParser.parseString(response.body()).asJsonObject
                        204, 404 -> return null
                        else -> {
                            lastFailure = IOException("Unexpected response ${response.statusCode()} from $url")
                        }
                    }
                } catch (throwable: Throwable) {
                    lastFailure = throwable
                    if (!throwable.isTransientLookupFailure() || attempt == attemptsPerUrl - 1) {
                        return@repeat
                    }
                }
            }
        }

        if (lastFailure != null) {
            throw lastFailure
        }

        return null
    }

    private fun resolveUuidViaRelay(uuid: String): String? {
        val normalizedUuid = uuid.lowercase()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${WorkerRelay.relayBaseUrl}/minecraft/lookup/$normalizedUuid"))
            .timeout(Duration.ofSeconds(5))
            .header("accept", "*/*")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            return null
        }

        val json = JsonParser.parseString(response.body()).asJsonObject
        return json.getOptionalString("name")
            ?: json.getOptionalString("username")
            ?: json.getOptionalString("ign")
            ?: json.getAsJsonObject("player")?.getOptionalString("name")
    }

    private fun Throwable.isTransientLookupFailure(): Boolean {
        val rootCause = unwrapRootCause()
        return rootCause is HttpConnectTimeoutException ||
            rootCause is HttpTimeoutException ||
            rootCause is IOException
    }

    private fun Throwable.rootCauseSummary(): String {
        val rootCause = unwrapRootCause()
        val message = rootCause.message?.takeIf { it.isNotBlank() } ?: "no details"
        return "${rootCause::class.java.simpleName}: $message"
    }

    private fun Throwable.unwrapRootCause(): Throwable {
        var current: Throwable = this
        while (current is CompletionException || current is ExecutionException) {
            val cause = current.cause ?: break
            current = cause
        }
        return current
    }

    private fun undashedUuid(rawUuid: String): UUID {
        val normalized = rawUuid.trim()
        val dashed = buildString(36) {
            append(normalized.substring(0, 8))
            append('-')
            append(normalized.substring(8, 12))
            append('-')
            append(normalized.substring(12, 16))
            append('-')
            append(normalized.substring(16, 20))
            append('-')
            append(normalized.substring(20, 32))
        }
        return UUID.fromString(dashed)
    }

    data class ResolvedProfile(
        val username: String,
        val uuid: String,
    )

    data class LinkedDiscordLookup(
        val discord: String?,
        val failureReason: String?,
    )
}
