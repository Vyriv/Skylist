package dev.ryan.playerlist

import net.fabricmc.loader.api.FabricLoader

object RuntimeVersion {
    private const val modId = "skylist"
    private val minecraftVersionPattern = Regex("""-(\d+\.\d+\.\d+)$""")

    fun currentVersion(): String =
        FabricLoader.getInstance()
            .getModContainer(modId)
            .orElseThrow { IllegalStateException("Missing Fabric metadata for $modId") }
            .metadata
            .version
            .friendlyString

    fun minecraftVersion(): String =
        minecraftVersionPattern.find(currentVersion())?.groupValues?.get(1).orEmpty()

    fun featureVersion(): String {
        val currentVersion = currentVersion()
        val minecraftVersion = minecraftVersion()
        return if (minecraftVersion.isNotEmpty() && currentVersion.endsWith("-$minecraftVersion")) {
            currentVersion.removeSuffix("-$minecraftVersion")
        } else {
            currentVersion
        }
    }

}
