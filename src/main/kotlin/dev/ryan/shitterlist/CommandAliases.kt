package dev.ryan.throwerlist

object CommandAliases {
    private val aliases = listOf("sl")

    fun rewriteCommand(command: String): String {
        val trimmed = command.trimStart()
        val alias = aliases.firstOrNull { trimmed.equals(it, ignoreCase = true) || trimmed.startsWith("$it ", ignoreCase = true) }
            ?: return command

        val suffix = trimmed.removePrefix(alias).let {
            if (it === trimmed) trimmed.substring(alias.length) else it
        }
        return "skylist$suffix"
    }
}
