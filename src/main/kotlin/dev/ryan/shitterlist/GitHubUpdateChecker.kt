package dev.ryan.throwerlist

import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.MinecraftClient
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

object GitHubUpdateChecker {
    private const val requestTimeoutSeconds = 10L
    private const val cacheDurationMillis = 15 * 60 * 1000L

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var cachedUpdate: UpdateInfo? = null

    @Volatile
    private var lastCheckedAt = 0L
    private val installInProgress = AtomicBoolean(false)
    private var hasNotifiedThisSession = false

    fun register() {
        ClientPlayConnectionEvents.JOIN.register(::onJoin)
    }

    fun latestKnownVersionForCurrentMinecraft(): String? {
        val minecraftVersion = RuntimeVersion.minecraftVersion()
        return cachedUpdate?.takeIf { it.minecraftVersion == minecraftVersion }?.latestVersion
    }

    fun installLatestUpdate(source: FabricClientCommandSource) {
        val currentJarPath = RuntimeVersion.currentJarPath()
        if (currentJarPath == null) {
            source.sendError(tlMessage("Automatic install only works from a packaged jar in your mods folder."))
            return
        }

        if (!installInProgress.compareAndSet(false, true)) {
            source.sendError(tlMessage("An update download is already running."))
            return
        }

        source.sendFeedback(tlMessage(Text.literal("Downloading the latest Skylist update...").formatted(Formatting.GREEN)))
        latestUpdateAsync(forceRefresh = true)
            .whenComplete { update, throwable ->
                ThrowerListMod.client.execute {
                    try {
                        if (throwable != null) {
                            ThrowerListMod.logger.warn("Failed to fetch latest update info", throwable)
                            source.sendError(tlMessage("Could not check for updates right now."))
                            return@execute
                        }
                        if (update == null) {
                            source.sendError(tlMessage("No compatible update was found for this Minecraft version."))
                            return@execute
                        }
                        if (compareVersions(RuntimeVersion.featureVersion(), update.releaseTag) >= 0) {
                            source.sendFeedback(tlMessage(Text.literal("You already have the latest version installed.").formatted(Formatting.GREEN)))
                            return@execute
                        }

                        stageInstall(update, currentJarPath)
                        source.sendFeedback(
                            tlMessage(
                                Text.literal("Update downloaded. ")
                                    .formatted(Formatting.GREEN)
                                    .append(Text.literal("It will install after you close Minecraft.").formatted(Formatting.YELLOW)),
                            ),
                        )
                    } catch (exception: Exception) {
                        ThrowerListMod.logger.warn("Failed to stage Skylist update", exception)
                        source.sendError(tlMessage("Failed to stage the update."))
                    } finally {
                        installInProgress.set(false)
                    }
                }
            }
    }

    private fun onJoin(
        handler: net.minecraft.client.network.ClientPlayNetworkHandler,
        sender: net.fabricmc.fabric.api.networking.v1.PacketSender,
        client: MinecraftClient,
    ) {
        val now = System.currentTimeMillis()
        val cached = cachedUpdate
        if (cached != null && now - lastCheckedAt < cacheDurationMillis) {
            notifyIfOutdated(client, cached)
            return
        }

        latestUpdateAsync(forceRefresh = false)
            .thenAccept { latest ->
                if (latest == null) {
                    return@thenAccept
                }

                client.execute {
                    notifyIfOutdated(client, latest)
                }
            }
    }

    private fun notifyIfOutdated(client: MinecraftClient, update: UpdateInfo) {
        if (hasNotifiedThisSession) return
        ThrowerListMod.logger.info("Checking if Skylist is outdated: current=${RuntimeVersion.featureVersion()}, latest=${update.releaseTag}")
        if (update.minecraftVersion != RuntimeVersion.minecraftVersion()) {
            ThrowerListMod.logger.info("Minecraft version mismatch: current=${RuntimeVersion.minecraftVersion()}, update=${update.minecraftVersion}")
            return
        }
        if (compareVersions(RuntimeVersion.featureVersion(), update.releaseTag) >= 0) {
            return
        }

        client.player?.sendMessage(buildWarningMessage(RuntimeVersion.currentVersion(), update.latestVersion), false)
        hasNotifiedThisSession = true
    }

    private fun latestUpdateAsync(forceRefresh: Boolean): CompletableFuture<UpdateInfo?> =
        CompletableFuture.supplyAsync {
            val now = System.currentTimeMillis()
            if (!forceRefresh) {
                val cached = cachedUpdate
                if (cached != null && now - lastCheckedAt < cacheDurationMillis) {
                    return@supplyAsync cached
                }
            }

            fetchUpdateInfo()?.also {
                cachedUpdate = it
                lastCheckedAt = System.currentTimeMillis()
            }
        }

    private fun fetchUpdateInfo(): UpdateInfo? {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(ThrowerListLinks.githubLatestReleaseApi))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Skylist")
            .GET()
            .build()

        return runCatching {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                error("Unexpected response ${response.statusCode()}")
            }

            parseLatestRelease(response.body())
        }.onFailure {
            ThrowerListMod.logger.warn("Failed to check Skylist GitHub releases", it)
        }.getOrNull()
    }

    private fun parseLatestRelease(body: String): UpdateInfo? {
        val parsed = JsonParser.parseString(body).asJsonObject
        val releaseTag = parsed.get("tag_name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val releaseUrl = parsed.get("html_url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (releaseTag.isEmpty()) {
            return null
        }

        val cleanTag = releaseTag.removePrefix("v").trim()
        val minecraftVersion = RuntimeVersion.minecraftVersion()
        val asset = parsed.getAsJsonArray("assets")
            ?.mapNotNull { entry -> entry.takeIf { it.isJsonObject }?.asJsonObject }
            ?.firstOrNull { entry ->
                val assetName = entry.get("name")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                (assetName.startsWith("skylist-$cleanTag-") || assetName.startsWith("skylist-$releaseTag-")) &&
                    assetName.endsWith("-$minecraftVersion.jar")
            }
            ?: return null

        val assetName = asset.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val assetUrl = asset.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        if (assetName.isEmpty()) {
            return null
        }

        return UpdateInfo(
            releaseTag = cleanTag,
            latestVersion = "$cleanTag-$minecraftVersion",
            minecraftVersion = minecraftVersion,
            releaseUrl = releaseUrl.ifEmpty { ThrowerListLinks.githubLatestReleaseUrl },
            assetName = assetName,
            assetUrl = assetUrl,
        )
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = Regex("""\d+""").findAll(left).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rightParts = Regex("""\d+""").findAll(right).map { it.value.toIntOrNull() ?: 0 }.toList()
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftValue = leftParts.getOrElse(index) { 0 }
            val rightValue = rightParts.getOrElse(index) { 0 }
            if (leftValue != rightValue) {
                return leftValue.compareTo(rightValue)
            }
        }
        return left.compareTo(right)
    }

    private fun buildWarningMessage(currentVersion: String, latestVersion: String): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(Text.literal("New version available. ").formatted(Formatting.GREEN))
            .append(Text.literal("Current: ").formatted(Formatting.GREEN))
            .append(Text.literal(currentVersion).formatted(Formatting.YELLOW))
            .append(Text.literal(" Latest: ").formatted(Formatting.GREEN))
            .append(Text.literal(latestVersion).formatted(Formatting.YELLOW))
            .append(Text.literal(" "))
            .append(updateButton())

    private fun updateButton(): MutableText =
        Text.literal("[Update Now]")
            .formatted(Formatting.AQUA, Formatting.UNDERLINE)
            .styled {
                it.withClickEvent(ClickEvent.RunCommand("/skylist settings update"))
                    .withHoverEvent(HoverEvent.ShowText(Text.literal("Download the update and install it after Minecraft closes")))
            }

    private fun stageInstall(update: UpdateInfo, currentJarPath: Path) {
        val gameDir = FabricLoader.getInstance().gameDir
        val updaterDir = gameDir.resolve(".skylist-updater")
        Files.createDirectories(updaterDir)

        val stagedJarPath = updaterDir.resolve(update.assetName)
        downloadAsset(update.assetUrl, stagedJarPath)

        val targetJarPath = currentJarPath.parent.resolve(update.assetName)
        val scriptPath = writeInstallerScript(
            updaterDir = updaterDir,
            currentJarPath = currentJarPath,
            stagedJarPath = stagedJarPath,
            targetJarPath = targetJarPath,
        )
        launchInstaller(scriptPath, ProcessHandle.current().pid())
    }

    private fun downloadAsset(url: String, destination: Path) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("User-Agent", "Skylist")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            error("Unexpected response ${response.statusCode()} while downloading update")
        }

        response.body().use { input ->
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun writeInstallerScript(
        updaterDir: Path,
        currentJarPath: Path,
        stagedJarPath: Path,
        targetJarPath: Path,
    ): Path {
        return if (isWindows()) {
            val scriptPath = updaterDir.resolve("install-update.cmd")
            Files.writeString(
                scriptPath,
                buildString {
                    appendLine("@echo off")
                    appendLine("setlocal")
                    appendLine(":wait")
                    appendLine("tasklist /FI \"PID eq %1\" | find \"%1\" >nul")
                    appendLine("if not errorlevel 1 (")
                    appendLine("  timeout /t 2 /nobreak >nul")
                    appendLine("  goto wait")
                    appendLine(")")
                    appendLine("timeout /t 1 /nobreak >nul")
                    appendLine("del /F /Q \"${escapeForCmd(currentJarPath)}\" >nul 2>nul")
                    appendLine("move /Y \"${escapeForCmd(stagedJarPath)}\" \"${escapeForCmd(targetJarPath)}\" >nul")
                },
            )
            scriptPath
        } else {
            val scriptPath = updaterDir.resolve("install-update.sh")
            Files.writeString(
                scriptPath,
                buildString {
                    appendLine("#!/bin/sh")
                    appendLine("pid=\"\$1\"")
                    appendLine("while kill -0 \"\$pid\" 2>/dev/null; do")
                    appendLine("  sleep 2")
                    appendLine("done")
                    appendLine("sleep 1")
                    appendLine("rm -f '${escapeForShell(currentJarPath)}'")
                    appendLine("mv -f '${escapeForShell(stagedJarPath)}' '${escapeForShell(targetJarPath)}'")
                },
            )
            scriptPath.toFile().setExecutable(true)
            scriptPath
        }
    }

    private fun launchInstaller(scriptPath: Path, pid: Long) {
        if (isWindows()) {
            ProcessBuilder(
                "powershell",
                "-NoProfile",
                "-WindowStyle",
                "Hidden",
                "-Command",
                "Start-Process -FilePath '${escapeForPowerShell(scriptPath)}' -ArgumentList '${pid}' -WindowStyle Hidden",
            )
                .directory(scriptPath.parent.toFile())
                .start()
            return
        }

        ProcessBuilder("sh", scriptPath.toString(), pid.toString())
            .directory(scriptPath.parent.toFile())
            .start()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").contains("Windows", ignoreCase = true)

    private fun escapeForCmd(path: Path): String =
        path.toAbsolutePath().toString().replace("\"", "\"\"")

    private fun escapeForShell(path: Path): String =
        path.toAbsolutePath().toString().replace("'", "'\"'\"'")

    private fun escapeForPowerShell(path: Path): String =
        path.toAbsolutePath().toString().replace("'", "''")

    private fun tlMessage(message: String): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(Text.literal(message))

    private fun tlMessage(message: Text): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(message)

    private data class UpdateInfo(
        val releaseTag: String,
        val latestVersion: String,
        val minecraftVersion: String,
        val releaseUrl: String,
        val assetName: String,
        val assetUrl: String,
    )
}
