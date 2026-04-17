package dev.ryan.throwerlist

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

object ThemeManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val themesDirectory: Path = FabricLoader.getInstance().configDir.resolve("throwerlist").resolve("themes")
    private val legacyThemePath: Path = themesDirectory.resolve("theme.json")
    private val commentHeader = listOf(
        "# Skylist theme file.",
        "# Edit the colors below to change this theme.",
        "# Supported formats: #RRGGBB, #AARRGGBB, rgba(r, g, b, a).",
        "# For rgba alpha, use 0.0-1.0 for opacity or 0-255 for direct alpha.",
        "# The game ignores comment lines that start with #.",
    )

    private val blueTheme = ThemeFile(
        primaryAccent = "rgba(127, 214, 255, 1.0)",
        hoverAccent = "rgba(168, 229, 255, 1.0)",
        darkAccent = "rgba(78, 166, 216, 1.0)",
        mainBackground = "rgba(26, 32, 42, 1.0)",
        panelBackground = "rgba(35, 44, 57, 1.0)",
        secondaryPanel = "rgba(28, 36, 48, 1.0)",
        idleBorder = "rgba(50, 64, 80, 1.0)",
        lightTextAccent = "rgba(232, 246, 255, 1.0)",
    )

    @Volatile
    private var currentTheme = resolvePalette(blueTheme)

    @Volatile
    private var availableThemes: List<String> = listOf("ocean")

    private val themesResourcePath = "assets/throwerlist/themes/"
    private val defaultThemeNames = listOf(
        "ocean", "grape", "greyscale", "lime", "mango", "mint", "strawberry", "pastellite", "rose quartz", "watermelon"
    )

    @Synchronized
    fun load() {
        Files.createDirectories(themesDirectory)
        migrateLegacyCombinedThemeFile()
        extractDefaultThemes()
        reloadThemes()
    }

    private fun extractDefaultThemes() {
        for (name in defaultThemeNames) {
            val fileName = "$name.json"
            val path = themesDirectory.resolve(fileName)
            if (Files.notExists(path)) {
                runCatching {
                    ThemeManager::class.java.classLoader.getResourceAsStream("$themesResourcePath$fileName")?.use { inputStream ->
                        Files.copy(inputStream, path)
                    }
                }.onFailure {
                    ThrowerListMod.logger.error("Failed to extract default theme: $fileName", it)
                }
            }
        }
    }

    @Synchronized
    fun current(): ThemePalette = currentTheme

    @Synchronized
    fun activeThemeLabel(): String =
        normalizeThemeName(ConfigManager.getUiTheme())
            .split('-', '_', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    fun themesDirectoryPath(): Path = themesDirectory

    @Synchronized
    fun cycleTheme(): String {
        if (availableThemes.isEmpty()) {
            reloadThemes()
        }
        val currentThemeName = normalizeThemeName(ConfigManager.getUiTheme())
        val currentIndex = availableThemes.indexOf(currentThemeName)
        val nextTheme = if (currentIndex == -1) {
            availableThemes.firstOrNull() ?: "ocean"
        } else {
            availableThemes[(currentIndex + 1) % availableThemes.size]
        }
        ConfigManager.setUiTheme(nextTheme)
        currentTheme = resolvePalette(readThemeFile(nextTheme))
        return activeThemeLabel()
    }

    @Synchronized
    fun cycleThemeBack(): String {
        if (availableThemes.isEmpty()) {
            reloadThemes()
        }
        val currentThemeName = normalizeThemeName(ConfigManager.getUiTheme())
        val currentIndex = availableThemes.indexOf(currentThemeName)
        val previousTheme = if (currentIndex == -1) {
            availableThemes.lastOrNull() ?: "ocean"
        } else {
            availableThemes[(currentIndex - 1).floorMod(availableThemes.size)]
        }
        ConfigManager.setUiTheme(previousTheme)
        currentTheme = resolvePalette(readThemeFile(previousTheme))
        return activeThemeLabel()
    }

    @Synchronized
    fun reloadThemes(): String {
        availableThemes = discoverThemeNames()
        val activeTheme = normalizeThemeName(ConfigManager.getUiTheme())
        val resolvedTheme = if (activeTheme in availableThemes) activeTheme else availableThemes.firstOrNull() ?: "ocean"
        if (resolvedTheme != activeTheme) {
            ConfigManager.setUiTheme(resolvedTheme)
        }
        currentTheme = resolvePalette(readThemeFile(resolvedTheme))
        return activeThemeLabel()
    }

    private fun writeNormalizedThemeFile(themeName: String, theme: ThemeFile) {
        val json = gson.toJson(theme)
        val body = buildString {
            commentHeader.forEach {
                append(it)
                append(System.lineSeparator())
            }
            append(json)
        }
        Files.writeString(themeFilePath(themeName), body)
    }

    private fun readThemeFile(themeName: String): ThemeFile {
        val normalizedName = normalizeThemeName(themeName)
        val defaults = themeDefaults(normalizedName)
        val path = themeFilePath(normalizedName)
        if (Files.notExists(path)) {
            return defaults
        }

        return runCatching {
            val cleaned = Files.readString(path)
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(System.lineSeparator())
            (gson.fromJson(cleaned, ThemeFile::class.java) ?: defaults).normalizedAgainst(defaults)
        }.getOrElse {
            ThrowerListMod.logger.error("Failed to load theme file for $normalizedName, using defaults", it)
            defaults
        }
    }

    private fun migrateLegacyCombinedThemeFile() {
        if (Files.notExists(legacyThemePath)) {
            return
        }

        runCatching {
            val cleaned = Files.readString(legacyThemePath)
                .lineSequence()
                .filterNot { it.trimStart().startsWith("#") }
                .joinToString(System.lineSeparator())
            val legacyConfig = gson.fromJson(cleaned, LegacyThemeConfig::class.java) ?: LegacyThemeConfig()
            writeNormalizedThemeFile("blue", (legacyConfig.themes["blue"] ?: blueTheme).normalizedAgainst(blueTheme))
            val migratedTheme = normalizeThemeName(legacyConfig.activeTheme).ifBlank { "ocean" }
            ConfigManager.setUiTheme(if (migratedTheme == "purple") "ocean" else migratedTheme)
            Files.deleteIfExists(legacyThemePath)
        }.onFailure {
            ThrowerListMod.logger.error("Failed to migrate legacy theme.json, keeping defaults", it)
        }
    }

    private fun discoverThemeNames(): List<String> {
        val names = runCatching {
            Files.list(themesDirectory).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) }
                    .map { it.fileName.toString() }
                    .filter { it.endsWith(".json", ignoreCase = true) }
                    .map { normalizeThemeName(it.removeSuffix(".json")) }
                    .filter { it.isNotBlank() }
                    .toList()
            }
        }.getOrElse {
            ThrowerListMod.logger.error("Failed to scan theme files, using defaults", it)
            listOf("ocean")
        }

        return names.distinct().sorted().ifEmpty { listOf("ocean") }
    }

    private fun themeDefaults(themeName: String): ThemeFile = blueTheme

    private fun Int.floorMod(other: Int): Int {
        if (other == 0) {
            return 0
        }
        val result = this % other
        return if (result < 0) result + other else result
    }

    private fun normalizeThemeName(themeName: String): String =
        themeName.trim().lowercase().removeSuffix(".json").ifBlank { "ocean" }

    private fun themeFilePath(themeName: String): Path = themesDirectory.resolve("${normalizeThemeName(themeName)}.json")

    private fun resolvePalette(theme: ThemeFile): ThemePalette {
        return ThemePalette(
            primaryAccent = parseColor(theme.primaryAccent, blueTheme.primaryAccent),
            hoverAccent = parseColor(theme.hoverAccent, blueTheme.hoverAccent),
            darkAccent = parseColor(theme.darkAccent, blueTheme.darkAccent),
            mainBackground = parseColor(theme.mainBackground, blueTheme.mainBackground),
            panelBackground = parseColor(theme.panelBackground, blueTheme.panelBackground),
            secondaryPanel = parseColor(theme.secondaryPanel, blueTheme.secondaryPanel),
            idleBorder = parseColor(theme.idleBorder, blueTheme.idleBorder),
            lightTextAccent = parseColor(theme.lightTextAccent, blueTheme.lightTextAccent),
        )
    }

    private fun parseColor(value: String?, fallback: String): Int {
        val normalized = value?.trim().orEmpty()
        return parseHexColor(normalized)
            ?: parseRgbaColor(normalized)
            ?: parseHexColor(fallback.trim())
            ?: 0xFFFFFFFF.toInt()
    }

    private fun parseHexColor(value: String): Int? {
        val raw = value.removePrefix("#")
        val parsed = raw.toLongOrNull(16)?.toInt() ?: return null
        return when (raw.length) {
            6 -> 0xFF000000.toInt() or parsed
            8 -> parsed
            else -> null
        }
    }

    private fun parseRgbaColor(value: String): Int? {
        if (!value.startsWith("rgba(", ignoreCase = true) || !value.endsWith(")")) {
            return null
        }
        val parts = value.substringAfter('(').substringBeforeLast(')').split(',').map { it.trim() }
        if (parts.size != 4) {
            return null
        }
        val red = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val green = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val blue = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
        val alpha = parseAlpha(parts[3]) ?: return null
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun parseAlpha(value: String): Int? {
        val decimal = value.toFloatOrNull() ?: return null
        return if (decimal <= 1f) {
            (decimal.coerceIn(0f, 1f) * 255f).toInt()
        } else {
            decimal.toInt().coerceIn(0, 255)
        }
    }

    private data class LegacyThemeConfig(
        var activeTheme: String = "blue",
        var themes: LinkedHashMap<String, ThemeFile> = linkedMapOf(
            "blue" to blueTheme,
        ),
    )

    data class ThemeFile(
        var primaryAccent: String = blueTheme.primaryAccent,
        var hoverAccent: String = blueTheme.hoverAccent,
        var darkAccent: String = blueTheme.darkAccent,
        var mainBackground: String = blueTheme.mainBackground,
        var panelBackground: String = blueTheme.panelBackground,
        var secondaryPanel: String = blueTheme.secondaryPanel,
        var idleBorder: String = blueTheme.idleBorder,
        var lightTextAccent: String = blueTheme.lightTextAccent,
    ) {
        fun normalizedAgainst(defaults: ThemeFile): ThemeFile = ThemeFile(
            primaryAccent = primaryAccent.ifBlank { defaults.primaryAccent },
            hoverAccent = hoverAccent.ifBlank { defaults.hoverAccent },
            darkAccent = darkAccent.ifBlank { defaults.darkAccent },
            mainBackground = mainBackground.ifBlank { defaults.mainBackground },
            panelBackground = panelBackground.ifBlank { defaults.panelBackground },
            secondaryPanel = secondaryPanel.ifBlank { defaults.secondaryPanel },
            idleBorder = idleBorder.ifBlank { defaults.idleBorder },
            lightTextAccent = lightTextAccent.ifBlank { defaults.lightTextAccent },
        )
    }
}

data class ThemePalette(
    val primaryAccent: Int,
    val hoverAccent: Int,
    val darkAccent: Int,
    val mainBackground: Int,
    val panelBackground: Int,
    val secondaryPanel: Int,
    val idleBorder: Int,
    val lightTextAccent: Int,
) {
    val overlayBackground: Int
        get() = scaleAlpha(mainBackground, 0xDD / 255f)
    val frameBackground: Int
        get() = scaleAlpha(panelBackground, 0xE0 / 255f)
    val listBackground: Int
        get() = scaleAlpha(secondaryPanel, 0xA6 / 255f)
    val mutedText: Int
        get() = mix(lightTextAccent, idleBorder, 0.42f)
    val subtleText: Int
        get() = hoverAccent
    val sectionHeader: Int
        get() = hoverAccent
    val linkText: Int
        get() = hoverAccent
    val fieldBackground: Int
        get() = secondaryPanel
    val rowBorder: Int
        get() = mix(idleBorder, lightTextAccent, 0.18f)
    val selectedRow: Int
        get() = scaleAlpha(mix(panelBackground, primaryAccent, 0.2f), 0xF0 / 255f)
    val hoveredRow: Int
        get() = scaleAlpha(mix(secondaryPanel, primaryAccent, 0.12f), 0xD4 / 255f)
    val idleRow: Int
        get() = scaleAlpha(mainBackground, 0x86 / 255f)
    val buttonDisabledText: Int
        get() = mix(lightTextAccent, idleBorder, 0.55f)

    fun scaleAlpha(color: Int, multiplier: Float): Int {
        val scaled = (((color ushr 24) and 0xFF) * multiplier.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (scaled shl 24) or (color and 0xFFFFFF)
    }

    fun withAlpha(color: Int, alpha: Int): Int = (alpha.coerceIn(0, 255) shl 24) or (color and 0xFFFFFF)

    fun mix(start: Int, end: Int, amount: Float): Int {
        val clamped = amount.coerceIn(0f, 1f)
        val startA = (start ushr 24) and 0xFF
        val startR = (start shr 16) and 0xFF
        val startG = (start shr 8) and 0xFF
        val startB = start and 0xFF
        val endA = (end ushr 24) and 0xFF
        val endR = (end shr 16) and 0xFF
        val endG = (end shr 8) and 0xFF
        val endB = end and 0xFF
        val a = (startA + ((endA - startA) * clamped)).toInt()
        val r = (startR + ((endR - startR) * clamped)).toInt()
        val g = (startG + ((endG - startG) * clamped)).toInt()
        val b = (startB + ((endB - startB) * clamped)).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
