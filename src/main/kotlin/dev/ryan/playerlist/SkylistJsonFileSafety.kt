package dev.ryan.playerlist

import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

internal fun skylistConfigDirectoryRoot(): Path = FabricLoader.getInstance().configDir.normalize().toAbsolutePath()

internal fun writeJsonCacheFile(targetPath: Path, jsonBody: String) {
    val normalizedTargetPath = targetPath.normalize().toAbsolutePath()
    val configDirectoryRoot = skylistConfigDirectoryRoot()
    val fileName = normalizedTargetPath.fileName.toString().lowercase()

    check(normalizedTargetPath.startsWith(configDirectoryRoot)) {
        "Skylist JSON path is outside the config directory: $normalizedTargetPath"
    }
    check(fileName.endsWith(".json")) {
        "Skylist JSON target must use a .json extension: $normalizedTargetPath"
    }

    Files.createDirectories(normalizedTargetPath.parent)
    Files.writeString(normalizedTargetPath, jsonBody)
}

internal fun writeJsonLikeTextFile(targetPath: Path, textBody: String, expectedExtension: String = ".json") {
    val normalizedTargetPath = targetPath.normalize().toAbsolutePath()
    val configDirectoryRoot = skylistConfigDirectoryRoot()
    val fileName = normalizedTargetPath.fileName.toString().lowercase()

    check(normalizedTargetPath.startsWith(configDirectoryRoot)) {
        "Skylist text path is outside the config directory: $normalizedTargetPath"
    }
    check(fileName.endsWith(expectedExtension.lowercase())) {
        "Skylist text target must use a $expectedExtension extension: $normalizedTargetPath"
    }

    Files.createDirectories(normalizedTargetPath.parent)
    Files.writeString(normalizedTargetPath, textBody)
}
