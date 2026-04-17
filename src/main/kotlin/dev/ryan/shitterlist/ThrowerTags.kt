package dev.ryan.throwerlist

object ThrowerTags {
    val supported: List<String> = listOf("toxic", "griefer", "ratter", "cheater")

    fun normalize(tags: Collection<String?>?): MutableList<String> =
        tags.orEmpty()
            .mapNotNull { it?.trim()?.lowercase() }
            .filter { it in supported }
            .distinct()
            .toMutableList()
}
