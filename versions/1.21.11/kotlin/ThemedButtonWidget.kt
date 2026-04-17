package dev.ryan.throwerlist

import net.minecraft.client.font.DrawnTextConsumer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ButtonWidget

class ThemedButtonWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: net.minecraft.text.Text,
    onPress: PressAction,
) : ButtonWidget(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER) {
    init {
        setAlpha(0f)
    }

    override fun drawIcon(context: DrawContext, x: Int, y: Int, deltaTicks: Float) = Unit

    override fun drawLabel(consumer: DrawnTextConsumer) = Unit

    companion object {
        fun builder(message: net.minecraft.text.Text, onPress: PressAction): Builder = Builder(message, onPress)
    }

    class Builder(
        private val message: net.minecraft.text.Text,
        private val onPress: PressAction,
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
