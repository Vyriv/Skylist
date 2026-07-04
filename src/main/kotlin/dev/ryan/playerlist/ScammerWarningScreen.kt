package dev.ryan.playerlist

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component
import java.util.concurrent.TimeUnit

class ScammerWarningScreen(
    private val parent: Screen?,
    private val username: String,
    private val listPhrase: String,
    private val reason: String,
    private val caseTimeMillis: Long?,
    private val severity: ScammerListManager.ScammerSeverity?,
    private val score: Double?,
    private val recommendedAction: ScammerListManager.ScammerRecommendedAction,
    private val continueCommand: String? = null,
) : Screen(Component.literal("Scammer Warning")) {
    private var acknowledgeButton: Button? = null

    override fun init() {
        super.init()
        acknowledgeButton = ThemedButtonWidget.builder(Component.literal(if (continueCommand != null) "Continue" else "Dismiss")) {
            continueCommand?.let { minecraft.player?.networkHandler?.sendChatCommand(it) }
            onClose()
        }.dimensions(width / 2 - 60, height / 2 + 26, 120, 20).build().also { addRenderableWidget(it) }
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        minecraft.setScreen(parent)
    }

    override fun extractRenderState(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val left = width / 2 - 170
        val top = height / 2 - 70
        val right = width / 2 + 170
        val bottom = height / 2 + 70
        val wrappedReason = wrap(reason, 300)
        ThemeRenderer.drawPanel(context, left, top, right, bottom, 24, theme)
        drawCentered(context, "$username is on $listPhrase", width / 2, top + 18, severity?.color ?: 0xFFFF6B6B.toInt())
        wrappedReason.forEachIndexed { index, line ->
            drawCentered(context, line, width / 2, top + 38 + index * 10, 0xFFFFFFFF.toInt())
        }
        drawCentered(context, severityLine(), width / 2, top + 52 + wrappedReason.size * 10, theme.lightTextAccent)
        drawCentered(context, relativeCaseText(), width / 2, top + 64 + wrappedReason.size * 10, theme.subtleText)
        super.extractRenderState(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, acknowledgeButton, mouseX.toDouble(), mouseY.toDouble(), false, theme)
    }

    private fun wrap(text: String, maxWidth: Int): List<String> {
        val words = text.split(' ').filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (font.getWidth(candidate) <= maxWidth) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun drawCentered(context: GuiGraphicsExtractor, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredText(context, font, text, centerX, y, color)
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

    private fun severityLine(): String {
        val severityText = severity?.label ?: "Unknown"
        val scoreText = score?.let {
            if (it % 1.0 == 0.0) it.toLong().toString() else String.format("%.2f", it).trimEnd('0').trimEnd('.')
        } ?: "?"
        return "$severityText severity • score $scoreText • ${recommendedAction.name.replace('_', ' ')}"
    }
}
