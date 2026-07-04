package dev.ryan.playerlist

import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SkylistBaseSettingsScreen(
    private val parent: SkylistMainScreen,
) : Screen(Component.literal("Skylist Settings")) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yy")
    private var themeButton: Button? = null
    private var openThemesButton: Button? = null
    private var reloadThemesButton: Button? = null
    private var scammerSettingsButton: Button? = null
    private var checkButton: Button? = null
    private var backButton: Button? = null
    private var leftMouseDown = false
    private var statusMessage: String? = null
    private var statusColor = 0xFF7FD6FF.toInt()

    override fun init() {
        super.init()
        val panel = panelRect()
        val left = panel.left + 34
        val rowWidth = panel.right - panel.left - 68
        val primaryWidth = rowWidth - 110

        themeButton = themedButton("Theme: ${ThemeManager.activeThemeLabel()}", left, panel.top + 48, primaryWidth) {
            val label = ThemeManager.cycleTheme()
            themeButton?.message = Component.literal("Theme: $label")
            showStatus("Theme changed to $label.", ThemeManager.current().hoverAccent)
        }
        openThemesButton = themedButton("Open", left + primaryWidth + 8, panel.top + 48, 50) {
            Util.getPlatform().openFile(ThemeManager.themesDirectoryPath().toFile())
        }
        reloadThemesButton = themedButton("Reload", left + primaryWidth + 64, panel.top + 48, 46) {
            val label = ThemeManager.reloadThemes()
            themeButton?.message = Component.literal("Theme: $label")
            showStatus("Reloaded themes. Active theme: $label.", ThemeManager.current().hoverAccent)
        }

        scammerSettingsButton = themedButton("Scammer Settings", left, panel.top + 84, rowWidth) {
            minecraft.setScreen(ScammerSettingsScreen(this))
        }

        checkButton = themedButton("Open Scammer Check", left, panel.top + 120, rowWidth) {
            minecraft.setScreen(ScammerCheckLookupScreen(this))
        }

        backButton = themedButton("Back", panel.centerX() - 42, panel.bottom - 30, 84) {
            onClose()
        }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        parent.onReturnMessage(statusMessage, statusColor)
        minecraft.setScreen(parent)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val panel = panelRect()
        context.fill(0, 0, width, height, theme.overlayBackground)
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 24, theme)
        drawCentered(context, title.string, panel.centerX(), panel.top + 8, 0xFFFFFFFF.toInt())
        drawText(context, "Loaded scammers: ${ScammerListManager.listEntries().size}", panel.left + 34, panel.top + 156, theme.lightTextAccent)
        drawText(context, "Cosmetic entries: ${PlayerCustomizationRegistry.entries.size}", panel.left + 34, panel.top + 170, theme.lightTextAccent)
        drawText(context, "Last scammer refresh: ${formatRefreshTime()}", panel.left + 34, panel.top + 184, theme.subtleText)
        drawText(context, "Cosmetics API: startup fetch via jsonhosting, worker-backed edits", panel.left + 34, panel.top + 198, theme.subtleText)

        statusMessage?.let {
            drawCentered(context, it, panel.centerX(), panel.bottom - 48, statusColor)
        }

        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(themeButton, openThemesButton, reloadThemesButton, scammerSettingsButton, checkButton, backButton)
            .forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        if (click.button() == 1 && themeButton?.isMouseOver(click.x(), click.y()) == true) {
            val label = ThemeManager.cycleThemeBack()
            themeButton?.message = Component.literal("Theme: $label")
            showStatus("Theme changed to $label.", ThemeManager.current().hoverAccent)
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    private fun themedButton(label: String, x: Int, y: Int, width: Int, onPress: () -> Unit): Button =
        ThemedButtonWidget.builder(Component.literal(label)) { onPress() }
            .dimensions(x, y, width, 20)
            .build()
            .also { addRenderableWidget(it) }

    private fun showStatus(message: String, color: Int) {
        statusMessage = message
        statusColor = color
    }

    private fun formatRefreshTime(): String {
        val refresh = ScammerListManager.lastRefreshCompletedAt() ?: return "Not yet recorded"
        return Instant.ofEpochMilli(refresh).atZone(ZoneId.systemDefault()).format(timeFormatter)
    }

    private fun panelRect(): Rect =
        Rect(width / 2 - 180, height / 2 - 120, width / 2 + 180, height / 2 + 120)

    private fun drawCentered(context: GuiGraphicsExtractor, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, font, text, centerX, y, color)
    }

    private fun drawText(context: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawTextWithShadow(context, font, text, x, y, color)
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }
}
