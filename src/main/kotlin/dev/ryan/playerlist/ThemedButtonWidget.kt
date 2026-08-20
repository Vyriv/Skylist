package dev.ryan.playerlist

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

class ThemedButtonWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    onPress: OnPress,
) : Button(x, y, width, height, message, onPress, DEFAULT_NARRATION) {
    init {
        setAlpha(0f)
    }

    override fun extractContents(context: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, deltaTicks: Float) = Unit

    companion object {
        fun builder(message: Component, onPress: OnPress): Builder = Builder(message, onPress)
    }

    class Builder(
        private val message: Component,
        private val onPress: OnPress,
    ) {
        private var x: Int = 0
        private var y: Int = 0
        private var width: Int = DEFAULT_WIDTH
        private var height: Int = DEFAULT_HEIGHT

        fun dimensions(x: Int, y: Int, width: Int, height: Int): Builder {
            this.x = x
            this.y = y
            this.width = width
            this.height = height
            return this
        }

        fun build(): ThemedButtonWidget = ThemedButtonWidget(x, y, width, height, message, onPress)
    }
}
