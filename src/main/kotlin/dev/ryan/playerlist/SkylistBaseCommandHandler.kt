package dev.ryan.playerlist

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting
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

    private fun printHelp(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        source.sendFeedback(Component.literal("Skylist commands:").formatted(ChatFormatting.GOLD))
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
        source.sendFeedback(helpLine("updatecosmetics"))
        source.sendFeedback(helpLine("help"))
        return Command.SINGLE_SUCCESS
    }

    private fun updateAssumePartyLeader(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        ConfigManager.setAssumePartyLeader(enabled)
        context.source.sendFeedback(
            tlMessage(
                Component.literal("Skylist assume party leader is now ").formatted(ChatFormatting.GREEN)
                    .append(Component.literal(enabled.toString().uppercase()).formatted(if (enabled) ChatFormatting.GREEN else ChatFormatting.RED)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun toggleCustomCapes(context: CommandContext<FabricClientCommandSource>): Int {
        val disabled = ConfigManager.toggleCustomCapesDisabled()
        context.source.sendFeedback(
            tlMessage(
                Component.literal("Custom cape cosmetics are now ").formatted(ChatFormatting.GREEN)
                    .append(Component.literal(if (disabled) "DISABLED" else "ENABLED").formatted(if (disabled) ChatFormatting.RED else ChatFormatting.GREEN)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun toggleCustomScaler(context: CommandContext<FabricClientCommandSource>): Int {
        val disabled = ConfigManager.toggleCustomScalerDisabled()
        context.source.sendFeedback(
            tlMessage(
                Component.literal("Custom player scaler is now ").formatted(ChatFormatting.GREEN)
                    .append(Component.literal(if (disabled) "DISABLED" else "ENABLED").formatted(if (disabled) ChatFormatting.RED else ChatFormatting.GREEN)),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun printVersionInfo(context: CommandContext<FabricClientCommandSource>): Int {
        val currentVersion = RuntimeVersion.currentVersion()
        val latestKnownVersion = GitHubUpdateChecker.latestKnownVersionForCurrentMinecraft()
        context.source.sendFeedback(
            tlMessage(
                Component.literal("Installed version: ").formatted(ChatFormatting.GREEN)
                    .append(
                        Component.literal(currentVersion)
                            .formatted(ChatFormatting.YELLOW)
                            .styled {
                                it.withClickEvent(ClickEvent.OpenUrl(URI.create(PlayerListLinks.githubReleasesUrl)))
                                    .withHoverEvent(HoverEvent.ShowText(Component.literal("Open Skylist GitHub releases")))
                            },
                    )
                    .append(Component.literal(" | Minecraft: ").formatted(ChatFormatting.GREEN))
                    .append(Component.literal(RuntimeVersion.minecraftVersion().ifBlank { "unknown" }).formatted(ChatFormatting.AQUA))
                    .append(
                        latestKnownVersion?.let {
                            Component.literal(" | Latest known: ").formatted(ChatFormatting.GREEN)
                                .append(Component.literal(it).formatted(ChatFormatting.AQUA))
                        } ?: Component.empty(),
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
                        Component.literal("UUID for ").formatted(ChatFormatting.GREEN)
                            .append(Component.literal(resolved.username).formatted(ChatFormatting.GRAY))
                            .append(Component.literal(" is ").formatted(ChatFormatting.GREEN))
                            .append(
                                Component.literal(resolved.uuid)
                                    .formatted(ChatFormatting.AQUA, ChatFormatting.UNDERLINE)
                                    .styled {
                                        it.withClickEvent(ClickEvent.CopyToClipboard(resolved.uuid))
                                            .withHoverEvent(HoverEvent.ShowText(Component.literal("Copy UUID")))
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
                Component.literal("Saved Hypixel API key for local dev commands.").formatted(ChatFormatting.GREEN),
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
                            Component.literal("Linked Discord for ").formatted(ChatFormatting.GREEN)
                                .append(Component.literal(resolved.username).formatted(ChatFormatting.GRAY))
                                .append(Component.literal(": ").formatted(ChatFormatting.GREEN))
                                .append(Component.literal(lookup.discord).formatted(ChatFormatting.AQUA)),
                        ),
                    )
                }
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun updateCosmetics(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        source.sendFeedback(tlMessage(Component.literal("Refreshing cosmetic player assignments from the live API...").formatted(ChatFormatting.AQUA)))

        refreshCosmetics().whenComplete { _, throwable ->
            PlayerListMod.client.execute {
                when {
                    throwable != null -> {
                        source.sendError(tlMessage("Failed to refresh cosmetic player assignments. Check log for details."))
                    }

                    else -> {
                        source.sendFeedback(
                            tlMessage(
                                Component.literal("Refreshed live cosmetic assignments for ${PlayerCustomizationRegistry.entries.size} player")
                                    .formatted(ChatFormatting.GREEN)
                                    .append(Component.literal(if (PlayerCustomizationRegistry.entries.size == 1) "." else "s.").formatted(ChatFormatting.GREEN)),
                            ),
                        )
                    }
                }
            }
        }

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

                    else -> source.sendFeedback(tlMessage(Component.literal("$target is not on the SBZ scammer list.").formatted(ChatFormatting.GREEN)))
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
                Component.literal("${entry.username} was added to the local scammer cache for \"${entry.reason}\".")
                    .formatted(ChatFormatting.GREEN),
            ),
        )
        return Command.SINGLE_SUCCESS
    }

    private fun buildResultMessage(
        username: String,
        reason: String,
        color: Int?,
        severityResult: ScammerListManager.SeverityResult? = null,
    ): MutableComponent =
        tlMessage(
            Component.literal(username).styled { style -> style.withColor((color ?: (ChatFormatting.RED.colorValue ?: 0xFF5555)) and 0xFFFFFF) }
                .append(Component.literal(" is on the SBZ scammer list for ").formatted(ChatFormatting.RED))
                .append(Component.literal("\"$reason\"").formatted(ChatFormatting.GRAY))
                .append(severityResultText(severityResult)),
        )

    private fun severityResultText(severityResult: ScammerListManager.SeverityResult?): MutableComponent {
        if (severityResult == null) {
            return Component.empty()
        }
        return Component.empty()
            .append(Component.literal("\nSeverity: ").formatted(ChatFormatting.DARK_GRAY))
            .append(Component.literal(severityResult.severity.label).styled { it.withColor(severityResult.severity.color and 0xFFFFFF) })
            .append(Component.literal(" | Score: ${formatScore(severityResult.score)}").formatted(ChatFormatting.YELLOW))
            .append(Component.literal(" | Action: ${formatAction(severityResult.recommendedAction)}").formatted(ChatFormatting.GOLD))
            .append(Component.literal("\nWhy: ${severitySummary(severityResult)}").formatted(ChatFormatting.GRAY))
    }

    private fun severitySummary(severityResult: ScammerListManager.SeverityResult): String =
        severityResult.reasons.take(4).joinToString("; ")

    private fun formatAction(action: ScammerListManager.ScammerRecommendedAction): String =
        action.name.lowercase().replace('_', ' ')

    private fun formatScore(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.2f", value).trimEnd('0').trimEnd('.')

    private fun helpLine(vararg segments: String): MutableComponent =
        Component.literal("/")
            .formatted(ChatFormatting.DARK_GRAY)
            .append(Component.literal("skylist ").formatted(ChatFormatting.AQUA))
            .also { line ->
                segments.forEachIndexed { index, segment ->
                    if (index > 0) {
                        line.append(Component.literal(" "))
                    }
                    val color = when {
                        segment.equals("assumepartyleader", ignoreCase = true) -> ChatFormatting.YELLOW
                        segment.equals("update", ignoreCase = true) -> ChatFormatting.GREEN
                        segment.equals("versioninfo", ignoreCase = true) -> ChatFormatting.GOLD
                        segment.equals("sethypixelapikey", ignoreCase = true) -> ChatFormatting.GOLD
                        segment.equals("get", ignoreCase = true) -> ChatFormatting.AQUA
                        segment.equals("uuid", ignoreCase = true) -> ChatFormatting.AQUA
                        segment.equals("discord", ignoreCase = true) -> ChatFormatting.AQUA
                        segment.equals("togglecapes", ignoreCase = true) -> ChatFormatting.YELLOW
                        segment.equals("togglescaler", ignoreCase = true) -> ChatFormatting.YELLOW
                        segment.equals("updatecosmetics", ignoreCase = true) -> ChatFormatting.GREEN
                        segment.equals("true/false", ignoreCase = true) -> ChatFormatting.GRAY
                        segment.startsWith("<") && segment.endsWith(">") -> ChatFormatting.GRAY
                        else -> ChatFormatting.WHITE
                    }
                    line.append(Component.literal(segment).formatted(color))
                }
            }

    private fun isOwner(source: FabricClientCommandSource): Boolean =
        source.player.gameProfile.id == ownerUuid

    private fun ownerOnlyError(): MutableComponent =
        tlMessage("Only the mod owner can use this command")

    private fun tlMessage(message: String): MutableComponent =
        Component.empty()
            .append(Component.literal("[SL] ").formatted(ChatFormatting.AQUA))
            .append(Component.literal(message))

    private fun tlMessage(message: Text): MutableComponent =
        Component.empty()
            .append(Component.literal("[SL] ").formatted(ChatFormatting.AQUA))
            .append(message)
}
