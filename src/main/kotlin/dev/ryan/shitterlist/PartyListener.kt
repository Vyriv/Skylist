package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class PartyListener(
    private val client: MinecraftClient,
) {
    private companion object {
        private const val duplicateMessageWindowMillis = 250L
        private const val duplicateScammerHitWindowMillis = 15_000L
    }

    private val joinedRegex = Regex("""^(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16}) joined the party\.$""")
    private val leftRegex = Regex("""^(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16}) left the party\.$""")
    private val removedRegex = Regex("""^(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16}) was removed from the party\.$""")
    private val partyFinderJoinedRegex = Regex("""^Party Finder > ([A-Za-z0-9_]{1,16}) joined the dungeon group! \(.+\)$""", RegexOption.IGNORE_CASE)
    private val usernameRegex = Regex("""(?:\[[^\]]+]\s)?([A-Za-z0-9_]{1,16})""")
    private val tradeSentRegex = Regex("""^You have sent a trade request to ([A-Za-z0-9_]{1,16})\.$""", RegexOption.IGNORE_CASE)
    private val tradeReceivedRegex = Regex("""^([A-Za-z0-9_]{1,16}) has sent you a trade request(?:\. Click here to accept!)?$""", RegexOption.IGNORE_CASE)

    private val members = ConcurrentHashMap.newKeySet<String>()
    private val recentMessages = ConcurrentHashMap<String, Long>()
    private val recentScammerHits = ConcurrentHashMap<String, Long>()

    fun register() {
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) {
                handleIncomingText(message)
            }
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            handleIncomingText(message)
        }
    }

    fun reset() {
        members.clear()
        recentMessages.clear()
        recentScammerHits.clear()
    }

    fun isMemberPresent(username: String): Boolean = members.contains(username.lowercase(Locale.ROOT))

    fun memberUsernames(): Set<String> = members.toSet()

    fun notifyRemoteScammerHit(result: ScammerCheckService.CheckResult, tradePopup: Boolean = false) {
        client.execute {
            handleRemoteScammerHit(result, tradePopup)
        }
    }

    private fun handleIncomingText(message: Text) {
        val raw = message.string.trim()
        if (raw.isEmpty() || isRecentDuplicate(raw)) {
            return
        }

        when {
            joinedRegex.matches(raw) -> {
                val username = joinedRegex.matchEntire(raw)?.groupValues?.get(1) ?: return
                members.add(username.lowercase(Locale.ROOT))
                queueAutoScammerCheck(username, ScammerCheckService.CheckSource.PARTY_JOIN)
            }

            partyFinderJoinedRegex.matches(raw) -> {
                val username = partyFinderJoinedRegex.matchEntire(raw)?.groupValues?.get(1) ?: return
                members.add(username.lowercase(Locale.ROOT))
                queueAutoScammerCheck(username, ScammerCheckService.CheckSource.PARTY_JOIN)
            }

            leftRegex.matches(raw) -> {
                val username = leftRegex.matchEntire(raw)?.groupValues?.get(1) ?: return
                members.remove(username.lowercase(Locale.ROOT))
            }

            removedRegex.matches(raw) -> {
                val username = removedRegex.matchEntire(raw)?.groupValues?.get(1) ?: return
                members.remove(username.lowercase(Locale.ROOT))
            }

            raw == "You left the party." ||
                raw == "You are not currently in a party." ||
                raw.startsWith("The party was disbanded") -> {
                members.clear()
            }

            raw.startsWith("You have joined ") && raw.endsWith("'s party!") -> {
                members.clear()
            }

            raw.startsWith("Party Leader:") -> {
                parsePartySection(raw.removePrefix("Party Leader:"))
                queuePartyMemberChecks()
            }

            raw.startsWith("Party Moderators:") -> {
                parsePartySection(raw.removePrefix("Party Moderators:"))
                queuePartyMemberChecks()
            }

            raw.startsWith("Party Members:") -> {
                parsePartySection(raw.removePrefix("Party Members:"))
                queuePartyMemberChecks()
            }

            tradeSentRegex.matches(raw) -> {
                val username = tradeSentRegex.matchEntire(raw)?.groupValues?.get(1) ?: return
                queueTradeWarningCheck(username)
            }

            tradeReceivedRegex.matches(raw) -> {
                val username = tradeReceivedRegex.matchEntire(raw)?.groupValues?.get(1) ?: return
                queueTradeWarningCheck(username)
            }
        }
    }

    private fun queuePartyMemberChecks() {
        if (!ConfigManager.isAutoCheckPartyMembersEnabled()) {
            return
        }

        val usernames = members.toList()
            .filterNot { it.equals(client.session.username, ignoreCase = true) }
        if (usernames.isEmpty()) {
            return
        }

        ScammerCheckService.checkBatch(usernames, ScammerCheckService.CheckSource.PARTY_SYNC).thenAccept { outcomes ->
            outcomes.filter { it.state == ScammerCheckService.CheckState.HIT && it.verdict != null }
                .forEach { outcome ->
                    client.execute {
                        handleRemoteScammerHit(outcome.verdict!!, tradePopup = false)
                    }
                }
        }
    }

    private fun queueAutoScammerCheck(username: String, source: ScammerCheckService.CheckSource, showPopup: Boolean = false) {
        if (!ConfigManager.isRemoteScammerChecksEnabled()) {
            return
        }
        if (source != ScammerCheckService.CheckSource.TRADE && !ConfigManager.isAutoCheckOnJoinEnabled()) {
            return
        }

        ScammerCheckService.checkTarget(username, source).thenAccept { outcome ->
            if (outcome.state == ScammerCheckService.CheckState.HIT && outcome.verdict != null) {
                client.execute {
                    handleRemoteScammerHit(outcome.verdict, showPopup)
                }
            }
        }
    }

    private fun queueTradeWarningCheck(username: String) {
        if (!ConfigManager.isTradeScammerPopupEnabled()) {
            return
        }

        ScammerCheckService.checkTarget(username, ScammerCheckService.CheckSource.TRADE).thenAccept { outcome ->
            if (outcome.state == ScammerCheckService.CheckState.HIT && outcome.verdict != null) {
                client.execute {
                    handleRemoteScammerHit(outcome.verdict, tradePopup = true)
                }
            }
        }
    }

    private fun handleRemoteScammerHit(result: ScammerCheckService.CheckResult, tradePopup: Boolean) {
        if (!shouldShowScammerHit(result, tradePopup)) {
            return
        }

        client.player?.sendMessage(buildScammerHitMessage(result), false)
        if (ConfigManager.isAnnounceScammerHitsEnabled()) {
            client.player?.networkHandler?.sendChatCommand("pc [SL] ${result.username} is on the ${result.sourceLabel} list for \"${result.reason}\"")
        }
        if (tradePopup && ConfigManager.isTradeScammerPopupEnabled()) {
            client.setScreen(ScammerWarningScreen(client.currentScreen, result.username, "the ${result.sourceLabel} list", result.reason, result.caseTimeMillis))
        }
    }

    private fun shouldShowScammerHit(result: ScammerCheckService.CheckResult, tradePopup: Boolean): Boolean {
        if (tradePopup) {
            return true
        }

        val now = System.currentTimeMillis()
        recentScammerHits.entries.removeIf { now - it.value > duplicateScammerHitWindowMillis }
        val key = "${result.username.lowercase(Locale.ROOT)}|${result.sourceLabel.lowercase(Locale.ROOT)}|${result.reason.lowercase(Locale.ROOT)}"
        val previous = recentScammerHits.put(key, now) ?: return true
        return now - previous > duplicateScammerHitWindowMillis
    }

    private fun buildScammerHitMessage(result: ScammerCheckService.CheckResult): Text {
        val usernameText = Text.literal(result.username).styled { style ->
            val color = result.severityColor ?: Formatting.RED.colorValue ?: 0xFF5555
            style.withColor(color and 0xFFFFFF)
        }
        return Text.empty()
            .append(Text.literal("[SL] ").formatted(Formatting.AQUA))
            .append(usernameText)
            .append(Text.literal(" is on the ${result.sourceLabel} list for ").formatted(Formatting.RED))
            .append(Text.literal("\"${result.reason}\"").formatted(Formatting.GRAY))
    }

    private fun parsePartySection(section: String) {
        usernameRegex.findAll(section)
            .map { it.groupValues[1] }
            .filter { !it.equals("none", ignoreCase = true) }
            .forEach { username ->
                members.add(username.lowercase(Locale.ROOT))
            }
    }

    private fun isRecentDuplicate(raw: String): Boolean {
        val now = System.currentTimeMillis()
        recentMessages.entries.removeIf { now - it.value > duplicateMessageWindowMillis }
        val previous = recentMessages.put(raw, now) ?: return false
        return now - previous <= duplicateMessageWindowMillis
    }
}
