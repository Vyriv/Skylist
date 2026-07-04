package dev.ryan.playerlist

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.DeltaTracker
import net.minecraft.client.User
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.player.LocalPlayer
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import java.util.UUID

typealias Text = Component

fun MutableComponent.formatted(vararg formatting: ChatFormatting): MutableComponent = withStyle(*formatting)

fun MutableComponent.styled(updater: (Style) -> Style): MutableComponent = withStyle(updater)

fun Style.withParent(parent: Style): Style = applyTo(parent)

fun Style.withUnderline(underlined: Boolean?): Style = withUnderlined(underlined)

val ChatFormatting.colorValue: Int?
    get() = color

val ChatFormatting.code: Char
    get() = char

val Minecraft.textRenderer: Font
    get() = font

val Minecraft.session: User
    get() = user

val Minecraft.world: ClientLevel?
    get() = level

val Minecraft.currentScreen: Screen?
    get() = screen

val Minecraft.currentServerEntry: ServerData?
    get() = currentServer

val Minecraft.isIntegratedServerRunning: Boolean
    get() = hasSingleplayerServer()

val Minecraft.renderTickCounter: DeltaTracker
    get() = deltaTracker

val User.username: String
    get() = name

val User.uuidOrNull: UUID?
    get() = profileId

fun DeltaTracker.getTickProgress(ignoreFreeze: Boolean): Float = getGameTimeDeltaPartialTick(ignoreFreeze)

fun Font.getWidth(value: String): Int = width(value)

fun Font.getWidth(value: FormattedText): Int = width(value)

fun Font.getWidth(value: FormattedCharSequence): Int = width(value)

val Font.fontHeight: Int
    get() = lineHeight

var EditBox.text: String
    get() = value
    set(value) {
        setValue(value)
    }

fun EditBox.setDrawsBackground(drawsBackground: Boolean) {
    setBordered(drawsBackground)
}

fun EditBox.setChangedListener(listener: (String) -> Unit) {
    setResponder(listener)
}

fun FormattedText.asOrderedText(): FormattedCharSequence = Language.getInstance().getVisualOrder(this)

fun LocalPlayer.sendMessage(message: Component, overlay: Boolean) {
    if (overlay) {
        sendOverlayMessage(message)
    } else {
        sendSystemMessage(message)
    }
}

val LocalPlayer.networkHandler: ClientPacketListener
    get() = connection

fun ClientPacketListener.sendChatCommand(command: String) {
    sendCommand(command)
}
