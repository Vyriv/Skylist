package dev.ryan.throwerlist

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents

object OutgoingNameRewrite {
    fun register() {
        ClientSendMessageEvents.MODIFY_COMMAND.register(ClientSendMessageEvents.ModifyCommand(::rewriteCommand))
    }

    fun rewriteCommand(command: String): String {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return command
        }

        val parts = trimmed.split(Regex("\\s+")).toMutableList()
        if (parts.size <= 1) {
            return trimmed
        }

        for (index in 1 until parts.size) {
            val rewritten = PlayerCustomizationRegistry.resolveOutgoingNameAlias(parts[index]) ?: continue
            parts[index] = rewritten
        }

        return parts.joinToString(" ")
    }
}
