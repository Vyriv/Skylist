package dev.ryan.throwerlist

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import java.util.concurrent.TimeUnit

class ScammerWarningScreen(
    private val parent: Screen?,
    private val username: String,
    private val listPhrase: String,
    private val reason: String,
    private val caseTimeMillis: Long?,
) : Screen(Text.literal("Scammer Warning")) {
    private var acknowledgeButton: ButtonWidget? = null

    override fun init() {
        super.init()
        acknowledgeButton = ThemedButtonWidget.builder(Text.literal("I understand")) {
            client?.player?.networkHandler?.sendChatCommand("trade $username")
            close()
        }.dimensions(width / 2 - 60, height / 2 + 26, 120, 20).build().also { addDrawableChild(it) }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val left = width / 2 - 170
        val top = height / 2 - 70
        val right = width / 2 + 170
        val bottom = height / 2 + 70
        val wrappedReason = wrap(reason, 300)
        ThemeRenderer.drawPanel(context, left, top, right, bottom, 24, theme)
        drawCentered(context, "$username is on $listPhrase", width / 2, top + 18, 0xFFFF6B6B.toInt())
        wrappedReason.forEachIndexed { index, line ->
            drawCentered(context, line, width / 2, top + 38 + index * 10, 0xFFFFFFFF.toInt())
        }
        drawCentered(context, relativeCaseText(), width / 2, top + 52 + wrappedReason.size * 10, theme.subtleText)
        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, acknowledgeButton, mouseX.toDouble(), mouseY.toDouble(), false, theme)
    }

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (textRenderer.getWidth(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredText(context, textRenderer, text, centerX, y, color)
    }

    private fun relativeCaseText(): String {
        val ts = caseTimeMillis ?: return "Case date unknown"
        val delta = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
        val days = TimeUnit.MILLISECONDS.toDays(delta)
        if (days >= 1) {
            return "Case was $days day${if (days == 1L) "" else "s"} ago"
        }
        val hours = TimeUnit.MILLISECONDS.toHours(delta)
        if (hours >= 1) {
            return "Case was $hours hour${if (hours == 1L) "" else "s"} ago"
        }
        val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
        if (minutes >= 1) {
            return "Case was $minutes minute${if (minutes == 1L) "" else "s"} ago"
        }
        return "Case was just now"
    }
}
