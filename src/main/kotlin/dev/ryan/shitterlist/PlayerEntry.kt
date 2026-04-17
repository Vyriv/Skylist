package dev.ryan.throwerlist

data class PlayerEntry(
    var username: String,
    var uuid: String,
    var reason: String,
    var ts: Long? = null,
    var tags: MutableList<String> = mutableListOf(),
    var ignored: Boolean = false,
    var autoRemoveAfter: String? = null,
    var expiresAt: Long? = null,
)
