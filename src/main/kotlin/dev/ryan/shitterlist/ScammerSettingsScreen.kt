package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

class ScammerSettingsScreen(private val parent: Screen) : Screen(Text.literal("Scammer Settings")) {
    private var remoteChecksButton: ButtonWidget? = null
    private var autoPartyButton: ButtonWidget? = null
    private var autoJoinButton: ButtonWidget? = null
    private var storageField: TextFieldWidget? = null
    private var autokickButton: ButtonWidget? = null
    private var announceButton: ButtonWidget? = null
    private var notifyOnlyButton: ButtonWidget? = null
    private var tradePopupButton: ButtonWidget? = null
    private var backButton: ButtonWidget? = null
    private var leftMouseDown = false

    override fun init() {
        super.init()
        val panel = panelRect()
        val rowWidth = (panel.right - panel.left - 56).coerceAtLeast(240)
        val left = panel.centerX() - rowWidth / 2
        val rowHeight = 24
        val rowGap = 8
        val firstRowY = panel.top + 42
        val noteY = firstRowY + (rowHeight + rowGap) * 3 - 2
        val storageY = noteY + 14
        val secondBlockY = storageY + 28

        remoteChecksButton = toggle(left, firstRowY, rowWidth, "Enable remote scammer checks", ConfigManager.isRemoteScammerChecksEnabled()) {
            ConfigManager.setRemoteScammerChecksEnabled(!ConfigManager.isRemoteScammerChecksEnabled())
            updateLabels()
        }
        autoPartyButton = toggle(left, firstRowY + rowHeight + rowGap, rowWidth, "Auto-check party members", ConfigManager.isAutoCheckPartyMembersEnabled()) {
            ConfigManager.setAutoCheckPartyMembersEnabled(!ConfigManager.isAutoCheckPartyMembersEnabled())
            updateLabels()
        }
        autoJoinButton = toggle(left, firstRowY + (rowHeight + rowGap) * 2, rowWidth, "Auto-check on join", ConfigManager.isAutoCheckOnJoinEnabled()) {
            ConfigManager.setAutoCheckOnJoinEnabled(!ConfigManager.isAutoCheckOnJoinEnabled())
            updateLabels()
        }
        storageField = TextFieldWidget(textRenderer, left, storageY, rowWidth, 20, Text.literal("Storage duration")).also {
            it.setDrawsBackground(false)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = ConfigManager.getScammerStorageDuration().orEmpty()
            addDrawableChild(it)
        }
        autokickButton = toggle(left, secondBlockY, rowWidth, "Autokick remote-flagged scammers", ConfigManager.isScammerAutokickEnabled()) {
            ConfigManager.setScammerAutokickEnabled(!ConfigManager.isScammerAutokickEnabled())
            updateLabels()
        }
        announceButton = toggle(left, secondBlockY + rowHeight + rowGap, rowWidth, "Announce hits in party chat", ConfigManager.isAnnounceScammerHitsEnabled()) {
            ConfigManager.setAnnounceScammerHitsEnabled(!ConfigManager.isAnnounceScammerHitsEnabled())
            updateLabels()
        }
        notifyOnlyButton = toggle(left, secondBlockY + (rowHeight + rowGap) * 2, rowWidth, "Only notify, do not kick", ConfigManager.isScammerOnlyNotifyEnabled()) {
            ConfigManager.setScammerOnlyNotifyEnabled(!ConfigManager.isScammerOnlyNotifyEnabled())
            updateLabels()
        }
        tradePopupButton = toggle(left, secondBlockY + (rowHeight + rowGap) * 3, rowWidth, "Trade scammer pop-up", ConfigManager.isTradeScammerPopupEnabled()) {
            ConfigManager.setTradeScammerPopupEnabled(!ConfigManager.isTradeScammerPopupEnabled())
            updateLabels()
        }
        backButton = ThemedButtonWidget.builder(Text.literal("Done")) {
            ConfigManager.setScammerStorageDuration(storageField?.text)
            close()
        }.dimensions(panel.centerX() - 42, panel.bottom - 30, 84, 20).build().also { addDrawableChild(it) }
        updateLabels()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        ConfigManager.setScammerStorageDuration(storageField?.text)
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        context.fill(0, 0, width, height, theme.overlayBackground)
        val panel = panelRect()
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 22, theme)
        drawCentered(context, title.string, panel.centerX(), panel.top + 8, 0xFFFFFFFF.toInt())
        drawCentered(context, "Storage duration: blank = permanent", panel.centerX(), panel.top + 132, theme.subtleText)
        ThemeRenderer.drawTextField(context, storageField, theme)
        super.render(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(remoteChecksButton, autoPartyButton, autoJoinButton, autokickButton, announceButton, notifyOnlyButton, tradePopupButton, backButton)
            .forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) leftMouseDown = true
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) leftMouseDown = false
        return super.mouseReleased(click)
    }

    private fun toggle(x: Int, y: Int, width: Int, label: String, enabled: Boolean, action: () -> Unit): ButtonWidget =
        ThemedButtonWidget.builder(Text.literal("$label: ${if (enabled) "ON" else "OFF"}")) { action() }
            .dimensions(x, y, width, 24)
            .build()
            .also { addDrawableChild(it) }

    private fun updateLabels() {
        remoteChecksButton?.message = Text.literal("Enable remote scammer checks: ${if (ConfigManager.isRemoteScammerChecksEnabled()) "ON" else "OFF"}")
        autoPartyButton?.message = Text.literal("Auto-check party members: ${if (ConfigManager.isAutoCheckPartyMembersEnabled()) "ON" else "OFF"}")
        autoJoinButton?.message = Text.literal("Auto-check on join: ${if (ConfigManager.isAutoCheckOnJoinEnabled()) "ON" else "OFF"}")
        autokickButton?.message = Text.literal("Autokick remote-flagged scammers: ${if (ConfigManager.isScammerAutokickEnabled()) "ON" else "OFF"}")
        announceButton?.message = Text.literal("Announce hits in party chat: ${if (ConfigManager.isAnnounceScammerHitsEnabled()) "ON" else "OFF"}")
        notifyOnlyButton?.message = Text.literal("Only notify, do not kick: ${if (ConfigManager.isScammerOnlyNotifyEnabled()) "ON" else "OFF"}")
        tradePopupButton?.message = Text.literal("Trade scammer pop-up: ${if (ConfigManager.isTradeScammerPopupEnabled()) "ON" else "OFF"}")
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredText(context, textRenderer, text, centerX, y, color)
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.scammerSettingsPanel()
        return PanelRect(
            this.width / 2 - layout.width / 2,
            this.height / 2 - layout.height / 2,
            this.width / 2 + layout.width / 2,
            this.height / 2 + layout.height / 2,
        )
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
    }
}
