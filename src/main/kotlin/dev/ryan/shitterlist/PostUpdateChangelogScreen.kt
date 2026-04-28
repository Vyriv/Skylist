package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import net.minecraft.util.Util
import kotlin.math.max

class PostUpdateChangelogScreen(
    private val parent: Screen,
    private val notice: PostUpdateChangelogManager.PendingChangelogNotice,
) : Screen(Text.literal(notice.title)) {
    private var doneButton: ButtonWidget? = null
    private var openReleaseButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var scrollOffset = 0
    private var maxScroll = 0

    override fun init() {
        super.init()
        val panel = panelRect()
        doneButton = themedButton("Done", panel.centerX() - 52, panel.bottom - 28, 104) { close() }
        openReleaseButton = themedButton("Open Release", panel.right - 118, panel.top + 8, 92) {
            Util.getOperatingSystem().open(notice.releaseUrl)
        }
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val panel = panelRect()
        val bodyLeft = panel.left + 14
        val bodyTop = panel.top + 34
        val bodyRight = panel.right - 14
        val bodyBottom = panel.bottom - 40
        val bodyWidth = bodyRight - bodyLeft - 8
        val wrapped = textRenderer.wrapLines(Text.literal(notice.body), bodyWidth)
        maxScroll = max(0, wrapped.size * LINE_HEIGHT - (bodyBottom - bodyTop - 6))
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        context.fill(0, 0, width, height, theme.overlayBackground)
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 24, theme)
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, notice.title, panel.centerX(), panel.top + 8, theme.lightTextAccent)
        ThemeRenderer.drawText(context, textRenderer, notice.version, panel.left + 14, panel.top + 10, theme.primaryAccent)

        context.fill(bodyLeft, bodyTop, bodyRight, bodyBottom, theme.secondaryPanel)
        ThemeRenderer.drawOutline(context, bodyLeft, bodyTop, bodyRight - bodyLeft, bodyBottom - bodyTop, theme.idleBorder)

        val visibleTop = bodyTop + 4
        val visibleBottom = bodyBottom - 4
        val firstLine = scrollOffset / LINE_HEIGHT
        val lineOffset = scrollOffset % LINE_HEIGHT
        var y = visibleTop - lineOffset
        for (index in firstLine until wrapped.size) {
            if (y > visibleBottom - textRenderer.fontHeight) {
                break
            }
            if (y >= visibleTop - textRenderer.fontHeight) {
                ThemeRenderer.drawTextWithShadow(context, textRenderer, wrapped[index], bodyLeft + 6, y, theme.lightTextAccent)
            }
            y += LINE_HEIGHT
        }

        if (maxScroll > 0) {
            val trackLeft = bodyRight - 4
            context.fill(trackLeft, bodyTop + 2, trackLeft + 2, bodyBottom - 2, theme.fieldBackground)
            val thumbHeight = max(18, ((bodyBottom - bodyTop - 4).toFloat() * (bodyBottom - bodyTop - 6) / (wrapped.size * LINE_HEIGHT).toFloat()).toInt())
            val thumbRange = (bodyBottom - bodyTop - 4 - thumbHeight).coerceAtLeast(1)
            val thumbOffset = ((scrollOffset.toFloat() / maxScroll.toFloat()) * thumbRange).toInt()
            context.fill(trackLeft, bodyTop + 2 + thumbOffset, trackLeft + 2, bodyTop + 2 + thumbOffset + thumbHeight, theme.primaryAccent)
        }

        super.render(context, mouseX, mouseY, deltaTicks)
        ThemeRenderer.drawButton(context, openReleaseButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        ThemeRenderer.drawButton(context, doneButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (maxScroll <= 0) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }
        scrollOffset = (scrollOffset - verticalAmount.toInt() * LINE_HEIGHT * 3).coerceIn(0, maxScroll)
        return true
    }

    private fun themedButton(label: String, x: Int, y: Int, width: Int, onPress: () -> Unit): ButtonWidget =
        ThemedButtonWidget.builder(Text.literal(label)) { onPress() }
            .dimensions(x, y, width, 20)
            .build()
            .also { addDrawableChild(it) }

    private fun panelRect(): Rect =
        Rect(width / 2 - 190, height / 2 - 110, width / 2 + 190, height / 2 + 110)

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }

    private companion object {
        private const val LINE_HEIGHT = 11
    }
}
