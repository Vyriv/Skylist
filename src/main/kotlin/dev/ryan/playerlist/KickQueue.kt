package dev.ryan.playerlist

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KickQueue(
    private val client: Minecraft,
    private val isMemberPresent: (String) -> Boolean,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val queue = ConcurrentLinkedQueue<KickTarget>()
    private val lock = Any()

    @Volatile
    private var activeTarget: KickTarget? = null

    @Volatile
    private var lastKickCommandAtMillis = 0L

    fun enqueue(target: KickTarget) {
        synchronized(lock) {
            if (queue.any { it.uuid.equals(target.uuid, ignoreCase = true) } || activeTarget?.uuid.equals(target.uuid, ignoreCase = true)) {
                return
            }

            queue.add(target)
            processNextLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            activeTarget = null
            lastKickCommandAtMillis = 0L
        }
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
        synchronized(lock) {
            processNextLocked()
        }
    }

    private fun processNextLocked() {
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
                lastKickCommandAtMillis = System.currentTimeMillis()
                sendCommand("p kick ${next.username}")
            }
        }, nextKickDelayMillis(), TimeUnit.MILLISECONDS)

        scheduler.schedule({
            client.execute {
                val stillPresent = isMemberPresent(next.username)
                if (stillPresent) {
                    failCurrentKick(next)
                } else {
                    synchronized(lock) {
                        activeTarget = null
                    }
                    next.onSuccess?.invoke()
                    processNext()
                }
            }
        }, 1800, TimeUnit.MILLISECONDS)
    }

    private fun failCurrentKick(target: KickTarget) {
        client.player?.sendMessage(Component.literal("Could not kick user!"), false)
        val shouldFail = synchronized(lock) {
            if (activeTarget?.uuid.equals(target.uuid, ignoreCase = true)) {
                activeTarget = null
                true
            } else {
                false
            }
        }
        if (shouldFail) {
            target.onFailure?.invoke()
            processNext()
        }
    }

    private fun nextKickDelayMillis(): Long {
        val elapsedSinceLastKick = System.currentTimeMillis() - lastKickCommandAtMillis
        val cooldownDelay = (kickCommandCooldownMillis - elapsedSinceLastKick).coerceAtLeast(0L)
        return kickCommandBaseDelayMillis.coerceAtLeast(cooldownDelay)
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
        val onSuccess: (() -> Unit)? = null,
        val onFailure: (() -> Unit)? = null,
    )

    companion object {
        private const val kickCommandBaseDelayMillis = 650L
        private const val kickCommandCooldownMillis = 1_000L
    }
}
