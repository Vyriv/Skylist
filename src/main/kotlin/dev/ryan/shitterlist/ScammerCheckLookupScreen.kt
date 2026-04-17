package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting

class ScammerCheckLookupScreen(
    private val parent: Screen,
    private val initialValue: String = "",
) : Screen(Text.literal("Scammer Check")) {
    private var inputField: TextFieldWidget? = null
    private var checkButton: ButtonWidget? = null
    private var doneButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var statusMessage: Text? = null
    private var statusColor = 0xFF7FD6FF.toInt()

    override fun init() {
        super.init()
        val panel = panelRect()
        val rowWidth = panel.right - panel.left - 56
        val left = panel.centerX() - rowWidth / 2
        inputField = TextFieldWidget(textRenderer, left, panel.top + 56, rowWidth, 20, Text.literal("Username, UUID, or Discord ID")).also {
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.setMaxLength(64)
            it.text = initialValue
            addDrawableChild(it)
            setInitialFocus(it)
        }
        checkButton = ThemedButtonWidget.builder(Text.literal("Check")) {
            runLookup()
        }.dimensions(left, panel.top + 92, 92, 20).build().also { addDrawableChild(it) }
        doneButton = ThemedButtonWidget.builder(Text.literal("Done")) {
            close()
        }.dimensions(panel.centerX() - 42, panel.bottom - 30, 84, 20).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
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
        super.render(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(checkButton, doneButton).forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
        }
        return super.mouseReleased(click)
    }

    private fun runLookup() {
        val target = inputField?.text?.trim().orEmpty()
        if (target.isEmpty()) {
            statusMessage = Text.literal("Enter a username, UUID, or Discord ID.")
            statusColor = 0xFFFFDD77.toInt()
            return
        }
        checkButton?.active = false
        statusMessage = Text.literal("Checking...")
        statusColor = 0xFF7FD6FF.toInt()
        ScammerCheckService.checkTarget(target, ScammerCheckService.CheckSource.SLASH_COMMAND).whenComplete { outcome, throwable ->
            client?.execute {
                checkButton?.active = true
                if (throwable != null) {
                    statusMessage = Text.literal("Check failed.")
                    statusColor = 0xFFFF7777.toInt()
                    return@execute
                }
                val verdict = outcome?.verdict
                if (verdict == null) {
                    statusMessage = Text.literal("$target is not on the SBZ scammer list.")
                    statusColor = 0xFF88FF88.toInt()
                } else {
                    statusMessage = Text.empty()
                        .append(Text.literal(verdict.username).styled { it.withColor((verdict.severityColor ?: Formatting.RED.colorValue ?: 0xFF5555) and 0xFFFFFF) })
                        .append(Text.literal(" is on the ${verdict.sourceLabel} list for ").formatted(Formatting.RED))
                        .append(Text.literal("\"${verdict.reason}\"").formatted(Formatting.GRAY))
                    statusColor = 0xFFFFFFFF.toInt()
                }
            }
        }
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredText(context, textRenderer, text, centerX, y, color)
    }

    private fun drawWrapped(context: DrawContext, text: String, x: Int, y: Int, rowWidth: Int, color: Int) {
        val words = text.split(' ')
        var line = ""
        var row = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (textRenderer.getWidth(candidate) <= rowWidth) {
                line = candidate
            } else {
                ThemeRenderer.drawText(context, textRenderer, line, x, y + row * 10, color)
                line = word
                row++
            }
        }
        if (line.isNotEmpty()) {
            ThemeRenderer.drawText(context, textRenderer, line, x, y + row * 10, color)
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
