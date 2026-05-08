package dev.ryan.throwerlist

object CommandAliases {
    private val rootAliases = listOf("sl", "tl")

    fun register() = Unit

    fun rewriteCommand(command: String): String {
        val trimmed = command.trimStart()
        if (trimmed.equals("check", ignoreCase = true) || trimmed.startsWith("check ", ignoreCase = true)) {
            return "skylist $trimmed"
        }

        val alias = rootAliases.firstOrNull { trimmed.equals(it, ignoreCase = true) || trimmed.startsWith("$it ", ignoreCase = true) }
            ?: return command

        val suffix = trimmed.removePrefix(alias).let {
            if (it === trimmed) trimmed.substring(alias.length) else it
        }
        return "skylist$suffix"
    }
}
