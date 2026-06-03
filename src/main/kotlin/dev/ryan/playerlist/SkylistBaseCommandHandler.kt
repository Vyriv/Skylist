package dev.ryan.playerlist

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

object SkylistBaseCommandHandler {
    private val ownerUuid: UUID = UUID.fromString("e8a20d35-b48b-4fa1-bd92-4df9049ae76f")

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(buildRoot("skylist"))
            dispatcher.register(buildRoot("sl"))
            dispatcher.register(
                literal("check")
                    .then(argument("target", StringArgumentType.word())
                        .executes(::checkTarget),
                    ),
            )
        }
    }

    private fun buildRoot(name: String) =
        literal(name)
            .executes {
                PlayerListGuiLauncher.openMainScreen()
                Command.SINGLE_SUCCESS
            }
            .then(literal("gui")
                .executes {
                    PlayerListGuiLauncher.openMainScreen(PlayerListGuiLauncher.View.SCAMMERS)
                    Command.SINGLE_SUCCESS
                }
                .then(argument("target", StringArgumentType.word())
                    .executes { context ->
                        PlayerListGuiLauncher.openMainScreen(
                            PlayerListGuiLauncher.View.SCAMMERS,
                            StringArgumentType.getString(context, "target"),
                        )
                        Command.SINGLE_SUCCESS
                    },
                ),
            )
            .then(literal("list")
                .executes {
                    PlayerListGuiLauncher.openMainScreen(PlayerListGuiLauncher.View.SCAMMERS)
                    Command.SINGLE_SUCCESS
                }
                .then(literal("scammers").executes {
                    PlayerListGuiLauncher.openMainScreen(PlayerListGuiLauncher.View.SCAMMERS)
                    Command.SINGLE_SUCCESS
                }),
            )
            .then(literal("check")
                .then(argument("target", StringArgumentType.word())
                    .executes(::checkTarget),
                ),
            )
            .then(literal("updatecosmetics")
                .executes(::updateCosmetics),
            )
            .then(literal("settings").executes {
                PlayerListMod.client.execute {
                    PlayerListMod.client.setScreen(SkylistBaseSettingsScreen(SkylistMainScreen()))
                }
                Command.SINGLE_SUCCESS
            }
                .then(literal("update").executes(::showUpdateStatus)),
            )
            .then(literal("help").executes(::printHelp))
            .then(devCommands()
                .then(literal("addtestscammer")
                    .then(argument("name", StringArgumentType.word())
                        .then(argument("reason", StringArgumentType.greedyString())
                            .executes(::addTestScammer),
                        ),
                    ),
                ),
            )

    private fun devCommands() =
        literal("dev")
            .then(literal("assumepartyleader")
                .then(literal("true").executes { updateAssumePartyLeader(it, true) })
                .then(literal("false").executes { updateAssumePartyLeader(it, false) }),
            )
            .then(literal("versioninfo").executes(::printVersionInfo))
            .then(literal("sethypixelapikey")
                .then(argument("key", StringArgumentType.word())
                    .executes(::setHypixelApiKey),
                ),
            )
            .then(literal("get")
                .then(literal("uuid")
                    .then(argument("username", StringArgumentType.word())
                        .executes(::printUuidInfo),
                    ),
                )
                .then(literal("discord")
                    .then(argument("username", StringArgumentType.word())
                        .executes(::printDiscordInfo),
                    ),
                ),
            )
            .then(literal("togglecapes").executes(::toggleCustomCapes))
            .then(literal("togglescaler").executes(::toggleCustomScaler))
            .then(literal("toggleidentifier").executes(::toggleIdentifier))

    private fun printHelp(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        source.sendFeedback(Text.literal("Skylist commands:").formatted(Formatting.GOLD))
        source.sendFeedback(helpLine("gui", "[target]"))
        source.sendFeedback(helpLine("list", "scammers"))
        source.sendFeedback(helpLine("check", "<username/uuid/discordId>"))
        source.sendFeedback(helpLine("settings", "update"))
        source.sendFeedback(helpLine("dev", "assumepartyleader", "true/false"))
        source.sendFeedback(helpLine("dev", "versioninfo"))
        source.sendFeedback(helpLine("dev", "sethypixelapikey", "<key>"))
        source.sendFeedback(helpLine("dev", "get", "uuid", "<username>"))
        source.sendFeedback(helpLine("dev", "get", "discord", "<username>"))
        source.sendFeedback(helpLine("dev", "togglecapes"))
        source.sendFeedback(helpLine("dev", "togglescaler"))
        source.sendFeedback(helpLine("dev", "toggleidentifier"))
        source.sendFeedback(helpLine("updatecosmetics"))
        source.sendFeedback(helpLine("help"))
        return Command.SINGLE_SUCCESS
    }

    private fun updateAssumePartyLeader(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        ConfigManager.setAssumePartyLeader(enabled)
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Skylist assume party leader is now ").formatted(Formatting.GREEN)
                    .append(Text.literal(enabled.toString().uppercase()).formatted(if (enabled) Formatting.GREEN else Formatting.RED)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun toggleCustomCapes(context: CommandContext<FabricClientCommandSource>): Int {
        val disabled = ConfigManager.toggleCustomCapesDisabled()
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Custom cape cosmetics are now ").formatted(Formatting.GREEN)
                    .append(Text.literal(if (disabled) "DISABLED" else "ENABLED").formatted(if (disabled) Formatting.RED else Formatting.GREEN)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun toggleCustomScaler(context: CommandContext<FabricClientCommandSource>): Int {
        val disabled = ConfigManager.toggleCustomScalerDisabled()
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Custom player scaler is now ").formatted(Formatting.GREEN)
                    .append(Text.literal(if (disabled) "DISABLED" else "ENABLED").formatted(if (disabled) Formatting.RED else Formatting.GREEN)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun printVersionInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val currentVersion = RuntimeVersion.currentVersion()
        val latestKnownVersion = GitHubUpdateChecker.latestKnownVersionForCurrentMinecraft()
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Installed version: ").formatted(Formatting.GREEN)
                    .append(
                        Text.literal(currentVersion)
                            .formatted(Formatting.YELLOW)
                            .styled {
                                it.withClickEvent(ClickEvent.OpenUrl(URI.create(PlayerListLinks.githubReleasesUrl)))
                                    .withHoverEvent(HoverEvent.ShowText(Text.literal("Open Skylist GitHub releases")))
                            },
                    )
                    .append(Text.literal(" | Minecraft: ").formatted(Formatting.GREEN))
                    .append(Text.literal(RuntimeVersion.minecraftVersion().ifBlank { "unknown" }).formatted(Formatting.AQUA))
                    .append(
                        latestKnownVersion?.let {
                            Text.literal(" | Latest known: ").formatted(Formatting.GREEN)
                                .append(Text.literal(it).formatted(Formatting.AQUA))
                        } ?: Text.empty(),
                    ),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun showUpdateStatus(context: CommandContext<FabricClientCommandSource>): Int {
        GitHubUpdateChecker.showUpdateStatus(context.source)
        return Command.SINGLE_SUCCESS
    }

    private fun printUuidInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val username = StringArgumentType.getString(context, "username")
        UsernameResolver.resolve(username).thenAccept { resolved ->
            PlayerListMod.client.execute {
                if (resolved == null) {
                    source.sendError(tlMessage("Could not resolve username: $username"))
                    return@execute
                }

                source.sendFeedback(
                    tlMessage(
                        Text.literal("UUID for ").formatted(Formatting.GREEN)
                            .append(Text.literal(resolved.username).formatted(Formatting.GRAY))
                            .append(Text.literal(" is ").formatted(Formatting.GREEN))
                            .append(
                                Text.literal(resolved.uuid)
                                    .formatted(Formatting.AQUA, Formatting.UNDERLINE)
                                    .styled {
                                        it.withClickEvent(ClickEvent.CopyToClipboard(resolved.uuid))
                                            .withHoverEvent(HoverEvent.ShowText(Text.literal("Copy UUID")))
                                    },
                            ),
                    ),
                )
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun setHypixelApiKey(context: CommandContext<FabricClientCommandSource>): Int {
        if (!isOwner(context.source)) {
            context.source.sendError(ownerOnlyError())
            return 0
        }

        val apiKey = StringArgumentType.getString(context, "key")
        ConfigManager.setHypixelApiKey(apiKey)
        context.source.sendFeedback(
            tlMessage(
                Text.literal("Saved Hypixel API key for local dev commands.").formatted(Formatting.GREEN),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun printDiscordInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        if (!isOwner(source)) {
            source.sendError(ownerOnlyError())
            return 0
        }

        val username = StringArgumentType.getString(context, "username")
        UsernameResolver.resolve(username).thenAccept { resolved ->
            if (resolved == null) {
                PlayerListMod.client.execute {
                    source.sendError(tlMessage("Could not resolve username: $username"))
                }
                return@thenAccept
            }

            UsernameResolver.resolveLinkedDiscord(resolved.uuid).thenAccept { lookup ->
                PlayerListMod.client.execute {
                    if (!lookup.failureReason.isNullOrBlank()) {
                        source.sendError(tlMessage("Discord lookup failed for ${resolved.username}: ${lookup.failureReason}"))
                        return@execute
                    }

                    if (lookup.discord.isNullOrBlank()) {
                        source.sendError(tlMessage("No linked Discord found for ${resolved.username}"))
                        return@execute
                    }

                    source.sendFeedback(
                        tlMessage(
                            Text.literal("Linked Discord for ").formatted(Formatting.GREEN)
                                .append(Text.literal(resolved.username).formatted(Formatting.GRAY))
                                .append(Text.literal(": ").formatted(Formatting.GREEN))
                                .append(Text.literal(lookup.discord).formatted(Formatting.AQUA)),
                        ),
                    )
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun updateCosmetics(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        source.sendFeedback(tlMessage(Text.literal("Refreshing cosmetic player assignments from the live API...").formatted(Formatting.AQUA)))

        refreshCosmetics().whenComplete { _, throwable ->
            PlayerListMod.client.execute {
                when {
                    throwable != null -> {
                        source.sendError(tlMessage("Failed to refresh cosmetic player assignments. Check log for details."))
                    }

                    else -> {
                        source.sendFeedback(
                            tlMessage(
                                Text.literal("Refreshed live cosmetic assignments for ${PlayerCustomizationRegistry.entries.size} player")
                                    .formatted(Formatting.GREEN)
                                    .append(Text.literal(if (PlayerCustomizationRegistry.entries.size == 1) "." else "s.").formatted(Formatting.GREEN)),
                            ),
                        )
                    }
                }
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun toggleIdentifier(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        if (!isOwner(source)) {
            source.sendError(tlMessage("This command is developer only >:("))
            return 0
        }

        val enabled = SkylistPresenceManager.toggleIdentifierEnabled()
        if (enabled) {
            SkylistPresenceManager.refreshAsync()
        }
        source.sendFeedback(
            tlMessage(
                Text.literal("Developer identifiers are now ").formatted(Formatting.GREEN)
                    .append(Text.literal(if (enabled) "ENABLED" else "DISABLED").formatted(if (enabled) Formatting.GREEN else Formatting.RED)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    fun refreshCosmetics(): java.util.concurrent.CompletableFuture<Unit> =
        ContentManager.refreshRemotePeopleNow(logPrefix = "command")

    private fun checkTarget(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val target = StringArgumentType.getString(context, "target").trim()
        if (target.isEmpty()) {
            source.sendError(tlMessage("Enter a username, UUID, or Discord ID to check."))
            return 0
        }

        val localMatch = ScammerListManager.findEntryByUuid(target)
            ?: ScammerListManager.findEntryByUsername(target)
            ?: ScammerListManager.findEntryByDiscordId(target)
        if (localMatch != null) {
            source.sendFeedback(buildResultMessage(localMatch.username, localMatch.reason, localMatch.severityLevel.color, localMatch.severityResult))
            return Command.SINGLE_SUCCESS
        }

        ScammerCheckService.checkTarget(target, ScammerCheckService.CheckSource.SLASH_COMMAND).whenComplete { outcome, throwable ->
            PlayerListMod.client.execute {
                when {
                    throwable != null -> source.sendError(tlMessage("Scammer check failed."))
                    outcome?.verdict != null -> source.sendFeedback(
                        buildResultMessage(outcome.verdict.username, outcome.verdict.reason, outcome.verdict.severityColor, outcome.verdict.severityResult),
                    )

                    else -> source.sendFeedback(tlMessage(Text.literal("$target is not on the SBZ scammer list.").formatted(Formatting.GREEN)))
                }
            }
        }

        return Command.SINGLE_SUCCESS
    }

    private fun addTestScammer(context: CommandContext<FabricClientCommandSource>): Int {
        if (!isOwner(context.source)) {
            context.source.sendError(ownerOnlyError())
            return 0
        }

        val username = StringArgumentType.getString(context, "name").trim()
        val reason = StringArgumentType.getString(context, "reason").trim()
        if (username.isEmpty() || reason.isEmpty()) {
            context.source.sendError(tlMessage("Usage: /skylist dev addtestscammer <name> <reason>"))
            return 0
        }

        val syntheticUuid = UUID.nameUUIDFromBytes("skylist-test:$username".toByteArray(StandardCharsets.UTF_8)).toString()
        val entry = ScammerListManager.addTestScammer(username, syntheticUuid, reason, System.currentTimeMillis())
        context.source.sendFeedback(
            tlMessage(
                Text.literal("${entry.username} was added to the local scammer cache for \"${entry.reason}\".")
                    .formatted(Formatting.GREEN),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun buildResultMessage(
        username: String,
        reason: String,
        color: Int?,
        severityResult: ScammerListManager.SeverityResult? = null,
    ): MutableText =
        tlMessage(
            Text.literal(username).styled { style -> style.withColor((color ?: (Formatting.RED.colorValue ?: 0xFF5555)) and 0xFFFFFF) }
                .append(Text.literal(" is on the SBZ scammer list for ").formatted(Formatting.RED))
                .append(Text.literal("\"$reason\"").formatted(Formatting.GRAY))
                .append(severityResultText(severityResult)),
        )

    private fun severityResultText(severityResult: ScammerListManager.SeverityResult?): MutableText {
        if (severityResult == null) {
            return Text.empty()
        }
        return Text.empty()
            .append(Text.literal("\nSeverity: ").formatted(Formatting.DARK_GRAY))
            .append(Text.literal(severityResult.severity.label).styled { it.withColor(severityResult.severity.color and 0xFFFFFF) })
            .append(Text.literal(" | Score: ${formatScore(severityResult.score)}").formatted(Formatting.YELLOW))
            .append(Text.literal(" | Action: ${formatAction(severityResult.recommendedAction)}").formatted(Formatting.GOLD))
            .append(Text.literal("\nWhy: ${severitySummary(severityResult)}").formatted(Formatting.GRAY))
    }

    private fun severitySummary(severityResult: ScammerListManager.SeverityResult): String =
        severityResult.reasons.take(4).joinToString("; ")

    private fun formatAction(action: ScammerListManager.ScammerRecommendedAction): String =
        action.name.lowercase().replace('_', ' ')

    private fun formatScore(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.2f", value).trimEnd('0').trimEnd('.')

    private fun helpLine(vararg segments: String): MutableText =
        Text.literal("/")
            .formatted(Formatting.DARK_GRAY)
            .append(Text.literal("skylist ").formatted(Formatting.AQUA))
            .also { line ->
                segments.forEachIndexed { index, segment ->
                    if (index > 0) {
                        line.append(Text.literal(" "))
                    }
                    val color = when {
                        segment.equals("assumepartyleader", ignoreCase = true) -> Formatting.YELLOW
                        segment.equals("update", ignoreCase = true) -> Formatting.GREEN
                        segment.equals("versioninfo", ignoreCase = true) -> Formatting.GOLD
                        segment.equals("sethypixelapikey", ignoreCase = true) -> Formatting.GOLD
                        segment.equals("get", ignoreCase = true) -> Formatting.AQUA
                        segment.equals("uuid", ignoreCase = true) -> Formatting.AQUA
                        segment.equals("discord", ignoreCase = true) -> Formatting.AQUA
                        segment.equals("togglecapes", ignoreCase = true) -> Formatting.YELLOW
                        segment.equals("togglescaler", ignoreCase = true) -> Formatting.YELLOW
                        segment.equals("toggleidentifier", ignoreCase = true) -> Formatting.YELLOW
                        segment.equals("updatecosmetics", ignoreCase = true) -> Formatting.GREEN
                        segment.equals("true/false", ignoreCase = true) -> Formatting.GRAY
                        segment.startsWith("<") && segment.endsWith(">") -> Formatting.GRAY
                        else -> Formatting.WHITE
                    }
                    line.append(Text.literal(segment).formatted(color))
                }
            }

    private fun isOwner(source: FabricClientCommandSource): Boolean =
        source.player.gameProfile.id == ownerUuid

    private fun ownerOnlyError(): MutableText =
        tlMessage("Only the mod owner can use this command")

    private fun tlMessage(message: String): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(Text.literal(message))

    private fun tlMessage(message: Text): MutableText =
        Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(message)
}
