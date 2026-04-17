package dev.ryan.throwerlist

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.text.OrderedText
import net.minecraft.util.Identifier

object ThemeRenderer {
    private val discordButtonTexture = Identifier.of("throwerlist", "textures/gui/discord_button.png")
    private val creditsButtonTexture = Identifier.of("throwerlist", "textures/gui/credits_button.png")
    private val settingsButtonTexture = Identifier.of("throwerlist", "textures/gui/settings_button.png")
    private val donationButtonTexture = Identifier.of("throwerlist", "textures/gui/donation_button.png")

    fun opaqueTextColor(color: Int): Int = 0xFF000000.toInt() or (color and 0xFFFFFF)

    fun drawText(context: DrawContext, text: net.minecraft.client.font.TextRenderer, value: String, x: Int, y: Int, color: Int) {
        context.drawText(text, value, x, y, opaqueTextColor(color), false)
    }

    fun drawCenteredText(context: DrawContext, text: net.minecraft.client.font.TextRenderer, value: String, centerX: Int, y: Int, color: Int) {
        drawText(context, text, value, centerX - text.getWidth(value) / 2, y, color)
    }

    fun drawTextWithShadow(context: DrawContext, text: net.minecraft.client.font.TextRenderer, value: String, x: Int, y: Int, color: Int) {
        context.drawTextWithShadow(text, value, x, y, opaqueTextColor(color))
    }

    fun drawTextWithShadow(context: DrawContext, text: net.minecraft.client.font.TextRenderer, value: OrderedText, x: Int, y: Int, color: Int) {
        context.drawTextWithShadow(text, value, x, y, opaqueTextColor(color))
    }

    fun drawCenteredTextWithShadow(context: DrawContext, text: net.minecraft.client.font.TextRenderer, value: String, centerX: Int, y: Int, color: Int) {
        drawTextWithShadow(context, text, value, centerX - text.getWidth(value) / 2, y, color)
    }

    fun drawPanel(context: DrawContext, left: Int, top: Int, right: Int, bottom: Int, titleBarHeight: Int, theme: ThemePalette) {
        context.fill(left, top, right, bottom, theme.frameBackground)
        context.fill(left + 1, top + 1, right - 1, top + titleBarHeight, theme.secondaryPanel)
        drawOutline(context, left, top, right - left, bottom - top, theme.idleBorder)
    }

    fun applyTextFieldInset(field: TextFieldWidget?) {
        if (field == null) {
            return
        }
        val layout = UiLayoutManager.rendering()
        field.x += layout.textFieldInsetX
        field.setWidth((field.width - layout.textFieldInsetX * 2).coerceAtLeast(layout.textFieldMinWidth))
        field.y += layout.textFieldInsetY
    }

    fun drawTextField(context: DrawContext, field: TextFieldWidget?, theme: ThemePalette) {
        if (field == null) {
            return
        }
        val layout = UiLayoutManager.rendering()
        val border = if (field.isFocused) theme.primaryAccent else theme.idleBorder
        val left = field.x - layout.textFieldInsetX
        val top = field.y - layout.textFieldInsetY
        val right = field.x + field.width + layout.textFieldInsetX
        val bottom = field.y + field.height - layout.textFieldInsetY
        context.fill(left - 1, top - 1, right + 1, bottom + 1, border)
        context.fill(left, top, right, bottom, theme.fieldBackground)
    }

    fun textFieldPlaceholderX(field: TextFieldWidget): Int = field.x

    fun textFieldPlaceholderY(field: TextFieldWidget): Int =
        field.y + UiLayoutManager.rendering().textFieldPlaceholderYOffset

    fun drawButton(context: DrawContext, widget: ClickableWidget?, mouseX: Double, mouseY: Double, mouseDown: Boolean, theme: ThemePalette) {
        if (widget == null || !widget.visible) {
            return
        }
        val textRenderer = ThrowerListMod.client.textRenderer
        val hovered = isWidgetHovered(widget, mouseX, mouseY)
        val pressed = hovered && mouseDown && widget.active
        val fill = when {
            !widget.active -> theme.secondaryPanel
            pressed -> theme.darkAccent
            hovered -> theme.hoverAccent
            else -> theme.panelBackground
        }
        val border = when {
            !widget.active -> theme.idleBorder
            pressed -> theme.darkAccent
            hovered -> theme.primaryAccent
            else -> theme.idleBorder
        }
        val textColor = when {
            !widget.active -> theme.buttonDisabledText
            pressed -> theme.lightTextAccent
            hovered -> theme.mainBackground
            else -> theme.lightTextAccent
        }
        context.fill(widget.x, widget.y, widget.x + widget.width, widget.y + widget.height, fill)
        drawOutline(context, widget.x, widget.y, widget.width, widget.height, border)
        val text = widget.message.string
        val textX = widget.x + (widget.width - textRenderer.getWidth(text)) / 2
        val textY = widget.y + (widget.height - textRenderer.fontHeight) / 2
        drawTextWithShadow(context, textRenderer, text, textX, textY, textColor)
    }

    fun drawDiscordButton(
        context: DrawContext,
        x: Int,
        y: Int,
        size: Int,
        hovered: Boolean,
        pressed: Boolean,
        theme: ThemePalette,
    ) {
        drawIconButton(context, x, y, size, hovered, pressed, theme, discordButtonTexture)
    }

    fun drawCreditsButton(context: DrawContext, x: Int, y: Int, size: Int, hovered: Boolean, pressed: Boolean, theme: ThemePalette) {
        drawIconButton(context, x, y, size, hovered, pressed, theme, creditsButtonTexture)
    }

    fun drawSettingsButton(context: DrawContext, x: Int, y: Int, size: Int, hovered: Boolean, pressed: Boolean, theme: ThemePalette) {
        drawIconButton(context, x, y, size, hovered, pressed, theme, settingsButtonTexture)
    }

    fun drawDonationButton(context: DrawContext, x: Int, y: Int, size: Int, hovered: Boolean, pressed: Boolean, theme: ThemePalette) {
        drawIconButton(context, x, y, size, hovered, pressed, theme, donationButtonTexture)
    }

    private fun drawIconButton(
        context: DrawContext,
        x: Int,
        y: Int,
        size: Int,
        hovered: Boolean,
        pressed: Boolean,
        theme: ThemePalette,
        texture: Identifier,
    ) {
        val border = when {
            pressed -> theme.darkAccent
            hovered -> theme.primaryAccent
            else -> theme.idleBorder
        }
        val background = when {
            pressed -> theme.darkAccent
            hovered -> theme.hoverAccent
            else -> theme.panelBackground
        }
        context.fill(x, y, x + size, y + size, background)
        drawOutline(context, x, y, size, size, border)

        // Draw the full texture into the button area.
        // We use the 10-parameter overload to specify textureWidth/Height, 
        // ensuring Minecraft doesn't default to 256 and cause pixelation/zooming.
        // By setting regionWidth/Height to texSize (512), we map the entire 
        // high-resolution image to the small destination rectangle (size - 4).
        val texSize = 512
        context.drawTexture(
            RenderPipelines.GUI_TEXTURED,
            texture,
            x + 2, y + 2,
            0f, 0f,
            size - 4, size - 4,
            texSize, texSize,
            texSize, texSize
        )
    }

    fun drawOutline(context: DrawContext, x: Int, y: Int, width: Int, height: Int, color: Int) {
        context.fill(x, y, x + width, y + 1, color)
        context.fill(x, y + height - 1, x + width, y + height, color)
        context.fill(x, y, x + 1, y + height, color)
        context.fill(x + width - 1, y, x + width, y + height, color)
    }

    fun isWidgetHovered(widget: ClickableWidget, mouseX: Double, mouseY: Double): Boolean =
        mouseX >= widget.x && mouseX <= widget.x + widget.width && mouseY >= widget.y && mouseY <= widget.y + widget.height
}
