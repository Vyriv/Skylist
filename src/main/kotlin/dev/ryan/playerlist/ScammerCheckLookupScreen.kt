package dev.ryan.playerlist

import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import net.minecraft.ChatFormatting

class ScammerCheckLookupScreen(
    private val parent: Screen,
    private val initialValue: String = "",
) : Screen(Component.literal("Scammer Check")) {
    private var inputField: EditBox? = null
    private var checkButton: Button? = null
    private var doneButton: Button? = null
    private var leftMouseDown = false
    private var statusMessage: Text? = null
    private var statusColor = 0xFF7FD6FF.toInt()

    override fun init() {
        super.init()
        val panel = panelRect()
        val rowWidth = panel.right - panel.left - 56
        val left = panel.centerX() - rowWidth / 2
        inputField = EditBox(font, left, panel.top + 56, rowWidth, 20, Component.literal("Username, UUID, or Discord ID")).also {
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.setMaxLength(64)
            it.text = initialValue
            addRenderableWidget(it)
            setInitialFocus(it)
        }
        checkButton = ThemedButtonWidget.builder(Component.literal("Check")) {
            runLookup()
        }.dimensions(left, panel.top + 92, 92, 20).build().also { addRenderableWidget(it) }
        doneButton = ThemedButtonWidget.builder(Component.literal("Done")) {
            onClose()
        }.dimensions(panel.centerX() - 42, panel.bottom - 30, 84, 20).build().also { addRenderableWidget(it) }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 22, theme)
        drawCentered(context, title.string, panel.centerX(), panel.top + 8, 0xFFFFFFFF.toInt())
        drawCentered(context, "Check a username, UUID, or Discord ID against the scammer list.", panel.centerX(), panel.top + 34, theme.subtleText)
        ThemeRenderer.drawTextField(context, inputField, theme)
        statusMessage?.let {
            drawWrapped(context, it.string, panel.left + 28, panel.top + 126, rowWidth = panel.right - panel.left - 56, color = statusColor)
        }
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(checkButton, doneButton).forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: MouseButtonEvent, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    private fun runLookup() {
        val target = inputField?.text?.trim().orEmpty()
        if (target.isEmpty()) {
            statusMessage = Component.literal("Enter a username, UUID, or Discord ID.")
            statusColor = 0xFFFFDD77.toInt()
            return
        }
        checkButton?.active = false
        statusMessage = Component.literal("Checking...")
        statusColor = 0xFF7FD6FF.toInt()
        ScammerCheckService.checkTarget(target, ScammerCheckService.CheckSource.SLASH_COMMAND).whenComplete { outcome, throwable ->
            minecraft.execute {
                checkButton?.active = true
                if (throwable != null) {
                    statusMessage = Component.literal("Check failed.")
                    statusColor = 0xFFFF7777.toInt()
                    return@execute
                }
                val verdict = outcome?.verdict
                if (verdict == null) {
                    statusMessage = Component.literal("$target is not on the SBZ scammer list.")
                    statusColor = 0xFF88FF88.toInt()
                } else {
                    statusMessage = Component.empty()
                        .append(Component.literal(verdict.username).styled { it.withColor((verdict.severityColor ?: ChatFormatting.RED.colorValue ?: 0xFF5555) and 0xFFFFFF) })
                        .append(Component.literal(" is on the ${verdict.sourceLabel} list for ").formatted(ChatFormatting.RED))
                        .append(Component.literal("\"${verdict.reason}\"").formatted(ChatFormatting.GRAY))
                    statusColor = 0xFFFFFFFF.toInt()
                }
            }
        }
    }

    private fun drawCentered(context: GuiGraphicsExtractor, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredText(context, font, text, centerX, y, color)
    }

    private fun drawWrapped(context: GuiGraphicsExtractor, text: String, x: Int, y: Int, rowWidth: Int, color: Int) {
        val words = text.split(' ')
        var line = ""
        var row = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (font.getWidth(candidate) <= rowWidth) {
                line = candidate
            } else {
                ThemeRenderer.drawText(context, font, line, x, y + row * 10, color)
                line = word
                row++
            }
        }
        if (line.isNotEmpty()) {
            ThemeRenderer.drawText(context, font, line, x, y + row * 10, color)
        }
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.scammerSettingsPanel()
        return PanelRect(width / 2 - layout.width / 2, height / 2 - layout.height / 2, width / 2 + layout.width / 2, height / 2 + layout.height / 2)
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }
}
