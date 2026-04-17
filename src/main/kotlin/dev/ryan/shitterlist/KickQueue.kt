package dev.ryan.throwerlist

import net.minecraft.client.MinecraftClient
import net.minecraft.text.Text
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KickQueue(
    private val client: MinecraftClient,
    private val isMemberPresent: (String) -> Boolean,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val queue = ConcurrentLinkedQueue<KickTarget>()

    @Volatile
    private var activeTarget: KickTarget? = null

    fun enqueue(target: KickTarget) {
        if (queue.any { it.uuid.equals(target.uuid, ignoreCase = true) } || activeTarget?.uuid.equals(target.uuid, ignoreCase = true)) {
            return
        }

        queue.add(target)
        processNext()
    }

    fun clear() {
        queue.clear()
        activeTarget = null
    }

    fun handleChatMessage(message: String) {
        val target = activeTarget ?: return
        val normalized = message.lowercase(Locale.ROOT)
        if (
            normalized.contains("you are not the party leader") ||
            normalized.contains("you are not party leader") ||
            normalized.contains("you are not the party moderator") ||
            normalized.contains("you are not party moderator") ||
            normalized.contains("could not find a party player with that name") ||
            normalized.contains("you cannot kick that player")
        ) {
            failCurrentKick(target)
        }
    }

    private fun processNext() {
        if (activeTarget != null) {
            return
        }

        val next = queue.poll() ?: return
        activeTarget = next

        scheduler.schedule({
            client.execute {
                sendCommand("pc ${buildPartyMessage(next)}")
            }
        }, 200, TimeUnit.MILLISECONDS)

        scheduler.schedule({
            client.execute {
                sendCommand("p kick ${next.username}")
            }
        }, 650, TimeUnit.MILLISECONDS)

        scheduler.schedule({
            client.execute {
                val stillPresent = isMemberPresent(next.username)
                if (stillPresent) {
                    failCurrentKick(next)
                } else {
                    activeTarget = null
                    processNext()
                }
            }
        }, 1800, TimeUnit.MILLISECONDS)
    }

    private fun failCurrentKick(target: KickTarget) {
        client.player?.sendMessage(Text.literal("Could not kick user!"), false)
        if (activeTarget?.uuid.equals(target.uuid, ignoreCase = true)) {
            activeTarget = null
            processNext()
        }
    }

    private fun sendCommand(command: String) {
        client.player?.networkHandler?.sendChatCommand(command)
    }

    private fun buildPartyMessage(target: KickTarget): String {
        return target.partyMessage ?: "[SL] ${target.username} is flagged for ${target.reason}"
    }

    data class KickTarget(
        val username: String,
        val uuid: String,
        val reason: String,
        val isRemote: Boolean,
        val partyMessage: String? = null,
    )
}
