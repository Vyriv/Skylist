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

// ChatFormatting dropped its getColor()/getChar() accessors in 26.2 (it's now just a legacy
// prefix-char marker). These are Minecraft's own long-stable legacy formatting constants, so we
// keep our own copy instead of depending on Mojang's API surface for it.
private val LEGACY_FORMATTING_CODES: Map<ChatFormatting, Char> = mapOf(
    ChatFormatting.BLACK to '0',
    ChatFormatting.DARK_BLUE to '1',
    ChatFormatting.DARK_GREEN to '2',
    ChatFormatting.DARK_AQUA to '3',
    ChatFormatting.DARK_RED to '4',
    ChatFormatting.DARK_PURPLE to '5',
    ChatFormatting.GOLD to '6',
    ChatFormatting.GRAY to '7',
    ChatFormatting.DARK_GRAY to '8',
    ChatFormatting.BLUE to '9',
    ChatFormatting.GREEN to 'a',
    ChatFormatting.AQUA to 'b',
    ChatFormatting.RED to 'c',
    ChatFormatting.LIGHT_PURPLE to 'd',
    ChatFormatting.YELLOW to 'e',
    ChatFormatting.WHITE to 'f',
    ChatFormatting.OBFUSCATED to 'k',
    ChatFormatting.BOLD to 'l',
    ChatFormatting.STRIKETHROUGH to 'm',
    ChatFormatting.UNDERLINE to 'n',
    ChatFormatting.ITALIC to 'o',
    ChatFormatting.RESET to 'r',
)

private val LEGACY_FORMATTING_COLORS: Map<ChatFormatting, Int> = mapOf(
    ChatFormatting.BLACK to 0x000000,
    ChatFormatting.DARK_BLUE to 0x0000AA,
    ChatFormatting.DARK_GREEN to 0x00AA00,
    ChatFormatting.DARK_AQUA to 0x00AAAA,
    ChatFormatting.DARK_RED to 0xAA0000,
    ChatFormatting.DARK_PURPLE to 0xAA00AA,
    ChatFormatting.GOLD to 0xFFAA00,
    ChatFormatting.GRAY to 0xAAAAAA,
    ChatFormatting.DARK_GRAY to 0x555555,
    ChatFormatting.BLUE to 0x5555FF,
    ChatFormatting.GREEN to 0x55FF55,
    ChatFormatting.AQUA to 0x55FFFF,
    ChatFormatting.RED to 0xFF5555,
    ChatFormatting.LIGHT_PURPLE to 0xFF55FF,
    ChatFormatting.YELLOW to 0xFFFF55,
    ChatFormatting.WHITE to 0xFFFFFF,
)

val ChatFormatting.colorValue: Int?
    get() = LEGACY_FORMATTING_COLORS[this]

val ChatFormatting.code: Char
    get() = LEGACY_FORMATTING_CODES.getValue(this)

val Minecraft.textRenderer: Font
    get() = font

val Minecraft.session: User
    get() = user

val Minecraft.world: ClientLevel?
    get() = level

// Minecraft.screen/setScreen moved onto Minecraft.gui in 26.2. Kept both call shapes so every
// call site in the mod can keep saying `minecraft.setScreen(x)` / `minecraft.currentScreen`
// unchanged - member resolution picks the real method on 26.1.2, this extension covers 26.2.
val Minecraft.currentScreen: Screen?
    //? if <26.2 {
    get() = screen
    //?}
    //? if >=26.2 {
    /*get() = gui.screen()
    *///?}

fun Minecraft.setScreen(screen: Screen?) {
    //? if <26.2 {
    setScreen(screen)
    //?}
    //? if >=26.2 {
    /*gui.setScreen(screen)
    *///?}
}

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
