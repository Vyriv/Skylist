package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SkylistBaseSettingsScreen(
    private val parent: SkylistMainScreen,
) : Screen(Text.literal("Skylist Settings")) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yy")
    private var themeButton: ButtonWidget? = null
    private var openThemesButton: ButtonWidget? = null
    private var reloadThemesButton: ButtonWidget? = null
    private var scammerSettingsButton: ButtonWidget? = null
    private var checkButton: ButtonWidget? = null
    private var backButton: ButtonWidget? = null
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
            themeButton?.message = Text.literal("Theme: $label")
            showStatus("Theme changed to $label.", ThemeManager.current().hoverAccent)
        }
        openThemesButton = themedButton("Open", left + primaryWidth + 8, panel.top + 48, 50) {
            Util.getOperatingSystem().open(ThemeManager.themesDirectoryPath().toFile())
        }
        reloadThemesButton = themedButton("Reload", left + primaryWidth + 64, panel.top + 48, 46) {
            val label = ThemeManager.reloadThemes()
            themeButton?.message = Text.literal("Theme: $label")
            showStatus("Reloaded themes. Active theme: $label.", ThemeManager.current().hoverAccent)
        }

        scammerSettingsButton = themedButton("Scammer Settings", left, panel.top + 84, rowWidth) {
            client?.setScreen(ScammerSettingsScreen(this))
        }

        checkButton = themedButton("Open Scammer Check", left, panel.top + 120, rowWidth) {
            client?.setScreen(ScammerCheckLookupScreen(this))
        }

        backButton = themedButton("Back", panel.centerX() - 42, panel.bottom - 30, 84) {
            close()
        }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        parent.onReturnMessage(statusMessage, statusColor)
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
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

        super.render(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(themeButton, openThemesButton, reloadThemesButton, scammerSettingsButton, checkButton, backButton)
            .forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        if (click.button() == 1 && themeButton?.isMouseOver(click.x(), click.y()) == true) {
            val label = ThemeManager.cycleThemeBack()
            themeButton?.message = Text.literal("Theme: $label")
            showStatus("Theme changed to $label.", ThemeManager.current().hoverAccent)
            return true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    private fun themedButton(label: String, x: Int, y: Int, width: Int, onPress: () -> Unit): ButtonWidget =
        ThemedButtonWidget.builder(Text.literal(label)) { onPress() }
            .dimensions(x, y, width, 20)
            .build()
            .also { addDrawableChild(it) }

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

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawTextWithShadow(context, textRenderer, text, x, y, color)
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }
}
