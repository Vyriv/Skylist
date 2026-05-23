package dev.ryan.playerlist

object PlayerListTags {
    private const val legacyThrowerTag = "thrower"
    private const val playerListTag = "playerlist"

    val supported: List<String> = listOf(playerListTag, "toxic", "griefer", "ratter", "cheater")

    fun normalize(tags: Collection<String?>?): MutableList<String> =
        tags.orEmpty()
            .mapNotNull { tag ->
                when (tag?.trim()?.lowercase()) {
                    legacyThrowerTag -> playerListTag
                    else -> tag?.trim()?.lowercase()
                }
            }
            .filter { it in supported }
            .distinct()
            .toMutableList()
}
