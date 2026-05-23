package dev.ryan.playerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import kotlin.math.abs
import kotlin.math.max

class ScammerSettingsScreen(private val parent: Screen) : Screen(Text.literal("Scammer Settings")) {
    private val severityOptions = ScammerListManager.ScammerSeverity.entries.toList()
    private val storageOptions = listOf(
        StorageOption("Off", ConfigManager.scammerStorageDisabledValue, "Don't save"),
        StorageOption("1d", "1 day", "1 day"),
        StorageOption("7d", "7 days", "7 days"),
        StorageOption("30d", "30 days", "30 days"),
        StorageOption("90d", "90 days", "90 days"),
        StorageOption("1y", "1 year", "1 year"),
        StorageOption("Perm", null, "Permanent"),
    )

    private var remoteChecksButton: ButtonWidget? = null
    private var autoPartyButton: ButtonWidget? = null
    private var warningThresholdButton: ButtonWidget? = null
    private var autokickThresholdButton: ButtonWidget? = null
    private var autokickButton: ButtonWidget? = null
    private var announceButton: ButtonWidget? = null
    private var tradePopupButton: ButtonWidget? = null
    private var logOnlyThresholdField: TextFieldWidget? = null
    private var doneButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var activeDropdown: DropdownMenu? = null
    private var draggingStorageSlider = false
    private var storageOptionIndex = 0

    override fun init() {
        super.init()
        val panel = panelRect()
        val metrics = metrics(panel)
        storageOptionIndex = storageOptionIndexFor(ConfigManager.getScammerStorageDuration())

        remoteChecksButton = dropdownButton(metrics.remoteChecksValueRect) {
            activeDropdown = booleanDropdown(metrics.remoteChecksValueRect) {
                ConfigManager.setRemoteScammerChecksEnabled(it)
                updateLabels()
            }
        }
        autoPartyButton = dropdownButton(metrics.autoPartyValueRect) {
            activeDropdown = booleanDropdown(metrics.autoPartyValueRect) {
                ConfigManager.setAutoCheckPartyMembersEnabled(it)
                updateLabels()
            }
        }

        logOnlyThresholdField = TextFieldWidget(
            textRenderer,
            metrics.logOnlyValueRect.left + 6,
            metrics.logOnlyValueRect.top + 4,
            metrics.logOnlyValueRect.width() - 12,
            12,
            Text.literal("Log-only threshold"),
        ).also {
            it.setDrawsBackground(false)
            it.setMaxLength(8)
            it.text = formatDecimal(ConfigManager.getScammerLogOnlyThreshold())
            addDrawableChild(it)
        }

        warningThresholdButton = dropdownButton(metrics.warningThresholdValueRect) {
            activeDropdown = severityDropdown(metrics.warningThresholdValueRect) {
                ConfigManager.setScammerWarningThreshold(it)
                updateLabels()
            }
        }
        autokickThresholdButton = dropdownButton(metrics.autokickThresholdValueRect) {
            activeDropdown = severityDropdown(metrics.autokickThresholdValueRect) {
                ConfigManager.setScammerAutokickThreshold(it)
                updateLabels()
            }
        }

        autokickButton = dropdownButton(metrics.autokickValueRect) {
            activeDropdown = booleanDropdown(metrics.autokickValueRect) {
                ConfigManager.setScammerAutokickEnabled(it)
                updateLabels()
            }
        }
        announceButton = dropdownButton(metrics.announceValueRect) {
            activeDropdown = booleanDropdown(metrics.announceValueRect) {
                ConfigManager.setAnnounceScammerHitsEnabled(it)
                updateLabels()
            }
        }
        tradePopupButton = dropdownButton(metrics.tradePopupValueRect) {
            activeDropdown = booleanDropdown(metrics.tradePopupValueRect) {
                ConfigManager.setTradeScammerPopupEnabled(it)
                updateLabels()
            }
        }

        doneButton = ThemedButtonWidget.builder(Text.literal("Done")) {
            persistFields()
            close()
        }.dimensions(
            panel.centerX() - 80,
            metrics.doneButtonY,
            160,
            18,
        ).build().also { addDrawableChild(it) }

        updateLabels()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        persistFields()
        client?.setScreen(parent)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val panel = panelRect()
        val metrics = metrics(panel)
        context.fill(0, 0, width, height, theme.overlayBackground)
        ThemeRenderer.drawPanel(context, panel.left, panel.top, panel.right, panel.bottom, 22, theme)
        ThemeRenderer.drawCenteredText(context, textRenderer, title.string, panel.centerX(), panel.top + 8, theme.lightTextAccent)

        drawSection(context, "Detection", metrics.leftColumn.left, metrics.detectionHeaderY, metrics.columnWidth, theme)
        drawRow(context, metrics.leftColumn.left, metrics.detectionFirstRowY, metrics.columnWidth, "Enable remote scammer checks", null, theme)
        drawRow(context, metrics.leftColumn.left, metrics.detectionFirstRowY + metrics.rowPitch, metrics.columnWidth, "Auto-check party members", null, theme)

        drawSection(context, "Storage", metrics.leftColumn.left, metrics.storageHeaderY, metrics.columnWidth, theme)
        drawRow(
            context,
            metrics.leftColumn.left,
            metrics.storageFirstRowY,
            metrics.columnWidth,
            "Storage duration",
            tooltipText = "Choose how long positive remote scammer hits stay cached: Off, common durations, or Permanent.",
            theme = theme,
        )

        drawSection(context, "Scoring & Thresholds", metrics.rightColumn.left, metrics.scoringHeaderY, metrics.columnWidth, theme)
        drawRow(
            context,
            metrics.rightColumn.left,
            metrics.scoringFirstRowY,
            metrics.columnWidth,
            "Log-only threshold",
            tooltipText = "Entries below this score stay silent in gameplay and only show in the list/check output.",
            theme = theme,
        )
        drawRow(
            context,
            metrics.rightColumn.left,
            metrics.scoringFirstRowY + metrics.rowPitch,
            metrics.columnWidth,
            "Warning threshold",
            tooltipText = "Minimum severity needed before warnings and trade popups can appear.",
            theme = theme,
        )
        drawRow(
            context,
            metrics.rightColumn.left,
            metrics.scoringFirstRowY + metrics.rowPitch * 2,
            metrics.columnWidth,
            "Autokick threshold",
            tooltipText = "Minimum severity label required before auto-kick is allowed.",
            theme = theme,
        )

        drawSection(context, "Actions", metrics.rightColumn.left, metrics.actionsHeaderY, metrics.columnWidth, theme)
        drawRow(context, metrics.rightColumn.left, metrics.actionsFirstRowY, metrics.columnWidth, "Autokick scammers", null, theme)
        drawRow(context, metrics.rightColumn.left, metrics.actionsFirstRowY + metrics.rowPitch, metrics.columnWidth, "Announce scammers in p chat", null, theme)
        drawRow(context, metrics.rightColumn.left, metrics.actionsFirstRowY + metrics.rowPitch * 2, metrics.columnWidth, "Trade scammer pop-up", null, theme)

        super.render(context, mouseX, mouseY, deltaTicks)

        drawValueBox(context, metrics.remoteChecksValueRect, remoteChecksButton, theme, remoteChecksButton?.message?.string.orEmpty(), semanticToggleColor(ConfigManager.isRemoteScammerChecksEnabled(), theme), true, mouseX.toDouble(), mouseY.toDouble())
        drawValueBox(context, metrics.autoPartyValueRect, autoPartyButton, theme, autoPartyButton?.message?.string.orEmpty(), semanticToggleColor(ConfigManager.isAutoCheckPartyMembersEnabled(), theme), true, mouseX.toDouble(), mouseY.toDouble())
        drawStorageSlider(context, metrics.storageValueRect, theme, mouseX.toDouble(), mouseY.toDouble())

        drawTextFieldBox(context, metrics.logOnlyValueRect, logOnlyThresholdField, theme, "2.5", semanticNumberColor(theme))
        drawValueBox(context, metrics.warningThresholdValueRect, warningThresholdButton, theme, warningThresholdButton?.message?.string.orEmpty(), semanticSeverityColor(ConfigManager.getScammerWarningThreshold(), theme), true, mouseX.toDouble(), mouseY.toDouble())
        drawValueBox(context, metrics.autokickThresholdValueRect, autokickThresholdButton, theme, autokickThresholdButton?.message?.string.orEmpty(), semanticSeverityColor(ConfigManager.getScammerAutokickThreshold(), theme), true, mouseX.toDouble(), mouseY.toDouble())

        drawValueBox(context, metrics.autokickValueRect, autokickButton, theme, autokickButton?.message?.string.orEmpty(), semanticToggleColor(ConfigManager.isScammerAutokickEnabled(), theme), true, mouseX.toDouble(), mouseY.toDouble())
        drawValueBox(context, metrics.announceValueRect, announceButton, theme, announceButton?.message?.string.orEmpty(), semanticToggleColor(ConfigManager.isAnnounceScammerHitsEnabled(), theme), true, mouseX.toDouble(), mouseY.toDouble())
        drawValueBox(context, metrics.tradePopupValueRect, tradePopupButton, theme, tradePopupButton?.message?.string.orEmpty(), semanticToggleColor(ConfigManager.isTradeScammerPopupEnabled(), theme), true, mouseX.toDouble(), mouseY.toDouble())

        ThemeRenderer.drawButton(context, doneButton, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme)
        activeDropdown?.let { drawDropdown(context, it, theme, mouseX.toDouble(), mouseY.toDouble()) }

        if (draggingStorageSlider && leftMouseDown) {
            updateStorageSlider(metrics.storageValueRect, mouseX.toDouble())
        }

        hoveredTooltip(metrics, mouseX.toDouble(), mouseY.toDouble())?.let {
            context.drawTooltip(textRenderer, Text.literal(it), mouseX, mouseY)
        }
        if (metrics.storageValueRect.contains(mouseX.toDouble(), mouseY.toDouble())) {
            context.drawTooltip(textRenderer, Text.literal("Storage: ${storageOptions[storageOptionIndex].description}"), mouseX, mouseY)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val mouseX = click.x()
        val mouseY = click.y()
        val metrics = metrics(panelRect())
        if (click.button() == 0) {
            leftMouseDown = true
            if (handleDropdownClick(mouseX, mouseY)) {
                return true
            }
            activeDropdown = null
            if (metrics.storageValueRect.contains(mouseX, mouseY)) {
                draggingStorageSlider = true
                updateStorageSlider(metrics.storageValueRect, mouseX)
                logOnlyThresholdField?.setFocused(false)
                return true
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseReleased(click: Click): Boolean {
        if (click.button() == 0) {
            leftMouseDown = false
            draggingStorageSlider = false
        }
        return super.mouseReleased(click)
    }

    private fun drawSection(context: DrawContext, title: String, x: Int, y: Int, width: Int, theme: ThemePalette) {
        ThemeRenderer.drawText(context, textRenderer, title, x, y, theme.primaryAccent)
    }

    private fun drawRow(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        label: String,
        tooltipText: String?,
        theme: ThemePalette,
    ) {
        context.fill(x, y, x + width, y + ROW_HEIGHT, theme.secondaryPanel)
        ThemeRenderer.drawOutline(context, x, y, width, ROW_HEIGHT, theme.idleBorder)

        val clippedLabel = ellipsize(label, LABEL_MAX_WIDTH)
        ThemeRenderer.drawText(context, textRenderer, clippedLabel, x + 8, y + 8, theme.lightTextAccent)

        tooltipText?.let {
            val rect = tooltipRect(x, y, clippedLabel)
            ThemeRenderer.drawText(context, textRenderer, "?", rect.left, rect.top, theme.subtleText)
        }
    }

    private fun drawStorageSlider(
        context: DrawContext,
        rect: Rect,
        theme: ThemePalette,
        mouseX: Double,
        mouseY: Double,
    ) {
        val hovered = rect.contains(mouseX, mouseY)
        val border = when {
            draggingStorageSlider && hovered -> theme.primaryAccent
            hovered -> theme.hoverAccent
            else -> theme.rowBorder
        }
        context.fill(rect.left, rect.top, rect.right, rect.bottom, theme.fieldBackground)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), border)

        val option = storageOptions[storageOptionIndex]
        val labelWidth = textRenderer.getWidth(option.label)
        ThemeRenderer.drawText(context, textRenderer, option.label, rect.left + (rect.width() - labelWidth) / 2, rect.top + 2, theme.lightTextAccent)

        val trackLeft = rect.left + 6
        val trackRight = rect.right - 6
        val trackY = rect.bottom - 5
        context.fill(trackLeft, trackY, trackRight, trackY + 1, theme.mutedText)
        storageOptions.indices.forEach { index ->
            val stopX = sliderStopX(rect, index)
            context.fill(stopX, trackY - 2, stopX + 1, trackY + 3, theme.subtleText)
        }
        val thumbX = sliderStopX(rect, storageOptionIndex)
        context.fill(thumbX - 2, trackY - 4, thumbX + 3, trackY + 5, theme.primaryAccent)
    }

    private fun drawTextFieldBox(
        context: DrawContext,
        rect: Rect,
        field: TextFieldWidget?,
        theme: ThemePalette,
        placeholder: String,
        textColor: Int = 0xFFFFFFFF.toInt(),
    ) {
        val border = if (field?.isFocused == true) theme.primaryAccent else theme.rowBorder
        context.fill(rect.left, rect.top, rect.right, rect.bottom, theme.fieldBackground)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), border)
        if (field == null || field.text.isNotEmpty() || field.isFocused) {
            return
        }
        ThemeRenderer.drawText(
            context,
            textRenderer,
            placeholder,
            rect.left + 6,
            rect.top + 5,
            textColor,
        )
    }

    private fun drawValueBox(
        context: DrawContext,
        rect: Rect,
        widget: ButtonWidget?,
        theme: ThemePalette,
        rawValue: String,
        textColor: Int,
        drawArrow: Boolean,
        mouseX: Double,
        mouseY: Double,
    ) {
        val hovered = widget?.let { ThemeRenderer.isWidgetHovered(it, mouseX, mouseY) } == true
        val pressed = hovered && leftMouseDown && widget?.active == true
        val border = when {
            pressed -> theme.primaryAccent
            hovered -> theme.hoverAccent
            else -> theme.rowBorder
        }
        val fill = if (hovered) theme.panelBackground else theme.fieldBackground
        context.fill(rect.left, rect.top, rect.right, rect.bottom, fill)
        ThemeRenderer.drawOutline(context, rect.left, rect.top, rect.width(), rect.height(), border)

        val value = rawValue.removeSuffix(" v")
        val valueWidth = textRenderer.getWidth(value)
        val valueX = rect.left + (rect.width() - valueWidth) / 2 - if (drawArrow) 4 else 0
        ThemeRenderer.drawText(context, textRenderer, value, valueX, rect.top + 5, textColor)
        if (drawArrow) {
            ThemeRenderer.drawText(context, textRenderer, "v", rect.right - 8, rect.top + 5, theme.mutedText)
        }
    }

    private fun drawDropdown(
        context: DrawContext,
        dropdown: DropdownMenu,
        theme: ThemePalette,
        mouseX: Double,
        mouseY: Double,
    ) {
        context.fill(dropdown.bounds.left, dropdown.bounds.top, dropdown.bounds.right, dropdown.bounds.bottom, theme.panelBackground)
        ThemeRenderer.drawOutline(context, dropdown.bounds.left, dropdown.bounds.top, dropdown.bounds.width(), dropdown.bounds.height(), theme.idleBorder)
        dropdown.options.forEachIndexed { index, option ->
            val row = dropdown.optionRect(index)
            val hovered = row.contains(mouseX, mouseY)
            context.fill(row.left, row.top, row.right, row.bottom, if (hovered) theme.secondaryPanel else theme.fieldBackground)
            val textWidth = textRenderer.getWidth(option.label)
            ThemeRenderer.drawText(context, textRenderer, option.label, row.left + (row.width() - textWidth) / 2, row.top + 5, option.color)
        }
    }

    private fun hoveredTooltip(metrics: Metrics, mouseX: Double, mouseY: Double): String? {
        return tooltipEntries(metrics).firstOrNull { it.rect.contains(mouseX, mouseY) }?.message
    }

    private fun tooltipEntries(metrics: Metrics): List<TooltipEntry> = listOf(
        TooltipEntry(tooltipRect(metrics.leftColumn.left, metrics.storageFirstRowY, ellipsize("Storage duration", LABEL_MAX_WIDTH)), "Choose how long positive remote scammer hits stay cached: Off, common durations, or Permanent."),
        TooltipEntry(tooltipRect(metrics.rightColumn.left, metrics.scoringFirstRowY, ellipsize("Log-only threshold", LABEL_MAX_WIDTH)), "Entries below this score stay silent in gameplay and only show in the list/check output."),
        TooltipEntry(tooltipRect(metrics.rightColumn.left, metrics.scoringFirstRowY + metrics.rowPitch, ellipsize("Warning threshold", LABEL_MAX_WIDTH)), "Minimum severity needed before warnings and trade popups can appear."),
        TooltipEntry(tooltipRect(metrics.rightColumn.left, metrics.scoringFirstRowY + metrics.rowPitch * 2, ellipsize("Autokick threshold", LABEL_MAX_WIDTH)), "Minimum severity label required before auto-kick is allowed."),
    )

    private fun tooltipRect(x: Int, y: Int, renderedLabel: String): Rect {
        val labelWidth = textRenderer.getWidth(renderedLabel)
        val left = x + 8 + labelWidth + 4
        val top = y + (ROW_HEIGHT / 2) - 4
        return Rect(left, top, left + 7, top + 8)
    }

    private fun dropdownButton(rect: Rect, action: () -> Unit): ButtonWidget =
        ThemedButtonWidget.builder(Text.literal("")) { action() }
            .dimensions(rect.left, rect.top, rect.width(), rect.height())
            .build()
            .also { addDrawableChild(it) }

    private fun updateLabels() {
        remoteChecksButton?.message = Text.literal(onOff(ConfigManager.isRemoteScammerChecksEnabled()))
        autoPartyButton?.message = Text.literal(onOff(ConfigManager.isAutoCheckPartyMembersEnabled()))
        warningThresholdButton?.message = Text.literal(ConfigManager.getScammerWarningThreshold().label)
        autokickThresholdButton?.message = Text.literal(ConfigManager.getScammerAutokickThreshold().label)
        autokickButton?.message = Text.literal(onOff(ConfigManager.isScammerAutokickEnabled()))
        announceButton?.message = Text.literal(onOff(ConfigManager.isAnnounceScammerHitsEnabled()))
        tradePopupButton?.message = Text.literal(onOff(ConfigManager.isTradeScammerPopupEnabled()))
    }

    private fun persistFields() {
        ConfigManager.setScammerStorageDuration(storageOptions[storageOptionIndex].configValue)
        ConfigManager.setScammerLogOnlyThreshold(logOnlyThresholdField?.text?.toDoubleOrNull())
        logOnlyThresholdField?.text = formatDecimal(ConfigManager.getScammerLogOnlyThreshold())
    }

    private fun onOff(enabled: Boolean): String = if (enabled) "ON" else "OFF"

    private fun formatDecimal(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format("%.2f", value).trimEnd('0').trimEnd('.')

    private fun ellipsize(text: String, maxWidth: Int): String {
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text
        }
        var output = text
        while (output.isNotEmpty() && textRenderer.getWidth("$output...") > maxWidth) {
            output = output.dropLast(1)
        }
        return if (output.isEmpty()) text.take(1) else "$output..."
    }

    private fun handleDropdownClick(mouseX: Double, mouseY: Double): Boolean {
        val dropdown = activeDropdown ?: return false
        dropdown.options.forEachIndexed { index, option ->
            if (dropdown.optionRect(index).contains(mouseX, mouseY)) {
                option.onSelect()
                activeDropdown = null
                return true
            }
        }
        return dropdown.bounds.contains(mouseX, mouseY)
    }

    private fun booleanDropdown(anchor: Rect, applyValue: (Boolean) -> Unit): DropdownMenu {
        val theme = ThemeManager.current()
        return buildDropdown(
            anchor = anchor,
            options = listOf(
                DropdownOption("ON", semanticToggleColor(true, theme)) { applyValue(true) },
                DropdownOption("OFF", semanticToggleColor(false, theme)) { applyValue(false) },
            ),
        )
    }

    private fun severityDropdown(anchor: Rect, applyValue: (ScammerListManager.ScammerSeverity) -> Unit): DropdownMenu {
        val theme = ThemeManager.current()
        return buildDropdown(
            anchor = anchor,
            options = severityOptions.map { severity ->
                DropdownOption(severity.label, semanticSeverityColor(severity, theme)) { applyValue(severity) }
            },
        )
    }

    private fun buildDropdown(anchor: Rect, options: List<DropdownOption>): DropdownMenu {
        val menuWidth = max(anchor.width(), options.maxOf { textRenderer.getWidth(it.label) } + 16)
        val panel = panelRect()
        val left = minOf(max(anchor.left, panel.left + 12), panel.right - 12 - menuWidth)
        val top = anchor.bottom + 2
        return DropdownMenu(Rect(left, top, left + menuWidth, top + options.size * DROPDOWN_ROW_HEIGHT), options)
    }

    private fun storageOptionIndexFor(rawValue: String?): Int {
        if (rawValue == null) {
            return storageOptions.lastIndex
        }
        if (rawValue.equals(ConfigManager.scammerStorageDisabledValue, ignoreCase = true)) {
            return 0
        }
        val normalized = EntryExpiry.normalize(rawValue) ?: rawValue.trim()
        return storageOptions.indexOfFirst { it.configValue == normalized }.takeIf { it >= 0 } ?: storageOptions.lastIndex
    }

    private fun updateStorageSlider(rect: Rect, mouseX: Double) {
        val index = storageOptions.indices.minByOrNull { abs(sliderStopX(rect, it) - mouseX) } ?: 0
        storageOptionIndex = index
    }

    private fun sliderStopX(rect: Rect, index: Int): Int {
        val trackLeft = rect.left + 6
        val trackWidth = rect.width() - 12
        if (storageOptions.size <= 1) {
            return trackLeft + trackWidth / 2
        }
        val ratio = index.toDouble() / (storageOptions.lastIndex.toDouble())
        return trackLeft + (trackWidth * ratio).toInt()
    }

    private fun metrics(panel: PanelRect): Metrics {
        val contentY = panel.top + 34
        val leftColumnX = panel.left + 14
        val rightColumnX = leftColumnX + COLUMN_WIDTH + COLUMN_GAP
        val rowPitch = ROW_HEIGHT + ROW_GAP
        val leftColumn = Column(leftColumnX)
        val rightColumn = Column(rightColumnX)
        val detectionHeaderY = contentY
        val detectionFirstRowY = detectionHeaderY + HEADER_TO_ROW_GAP
        val scoringHeaderY = contentY
        val scoringFirstRowY = scoringHeaderY + HEADER_TO_ROW_GAP
        val actionsHeaderY = scoringFirstRowY + rowPitch * 3
        val actionsFirstRowY = actionsHeaderY + HEADER_TO_ROW_GAP
        val storageHeaderY = actionsHeaderY
        val storageFirstRowY = storageHeaderY + HEADER_TO_ROW_GAP
        return Metrics(
            leftColumn = leftColumn,
            rightColumn = rightColumn,
            columnWidth = COLUMN_WIDTH,
            panelRight = panel.right,
            rowPitch = rowPitch,
            detectionHeaderY = detectionHeaderY,
            detectionFirstRowY = detectionFirstRowY,
            storageHeaderY = storageHeaderY,
            storageFirstRowY = storageFirstRowY,
            scoringHeaderY = scoringHeaderY,
            scoringFirstRowY = scoringFirstRowY,
            actionsHeaderY = actionsHeaderY,
            actionsFirstRowY = actionsFirstRowY,
        )
    }

    private fun panelRect(): PanelRect {
        val layout = UiLayoutManager.CenteredPanelLayout(width = FIXED_PANEL_WIDTH, height = FIXED_PANEL_HEIGHT)
        return PanelRect(
            width / 2 - layout.width / 2,
            height / 2 - layout.height / 2,
            width / 2 + layout.width / 2,
            height / 2 + layout.height / 2,
        )
    }

    private data class PanelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun centerX(): Int = (left + right) / 2
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    private data class Column(val left: Int)

    private data class Metrics(
        val leftColumn: Column,
        val rightColumn: Column,
        val columnWidth: Int,
        val panelRight: Int,
        val rowPitch: Int,
        val detectionHeaderY: Int,
        val detectionFirstRowY: Int,
        val storageHeaderY: Int,
        val storageFirstRowY: Int,
        val scoringHeaderY: Int,
        val scoringFirstRowY: Int,
        val actionsHeaderY: Int,
        val actionsFirstRowY: Int,
    ) {
        val remoteChecksValueRect: Rect
            get() = valueRect(leftColumn.left, detectionFirstRowY)
        val autoPartyValueRect: Rect
            get() = valueRect(leftColumn.left, detectionFirstRowY + rowPitch)
        val storageValueRect: Rect
            get() = valueRect(leftColumn.left, storageFirstRowY)
        val logOnlyValueRect: Rect
            get() = valueRect(rightColumn.left, scoringFirstRowY)
        val warningThresholdValueRect: Rect
            get() = valueRect(rightColumn.left, scoringFirstRowY + rowPitch)
        val autokickThresholdValueRect: Rect
            get() = valueRect(rightColumn.left, scoringFirstRowY + rowPitch * 2)
        val autokickValueRect: Rect
            get() = valueRect(rightColumn.left, actionsFirstRowY)
        val announceValueRect: Rect
            get() = valueRect(rightColumn.left, actionsFirstRowY + rowPitch)
        val tradePopupValueRect: Rect
            get() = valueRect(rightColumn.left, actionsFirstRowY + rowPitch * 2)
        val lastRowY: Int
            get() = actionsFirstRowY + rowPitch * 2
        val lastRowBottom: Int
            get() = lastRowY + ROW_HEIGHT
        val doneButtonY: Int
            get() = lastRowY + ROW_HEIGHT + 18

        private fun valueRect(rowX: Int, rowY: Int): Rect {
            val unclampedLeft = rowX + VALUE_BOX_X_OFFSET
            val maxRight = panelRight - VALUE_BOX_RIGHT_PADDING
            val right = minOf(unclampedLeft + VALUE_BOX_WIDTH, maxRight)
            val left = right - VALUE_BOX_WIDTH
            return Rect(left, rowY + 3, right, rowY + 3 + VALUE_BOX_HEIGHT)
        }
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom
        fun width(): Int = right - left
        fun height(): Int = bottom - top
    }

    private data class TooltipEntry(val rect: Rect, val message: String)

    private data class StorageOption(
        val label: String,
        val configValue: String?,
        val description: String,
    )

    private data class DropdownOption(
        val label: String,
        val color: Int,
        val onSelect: () -> Unit,
    )

    private data class DropdownMenu(
        val bounds: Rect,
        val options: List<DropdownOption>,
    ) {
        fun optionRect(index: Int): Rect =
            Rect(bounds.left, bounds.top + index * DROPDOWN_ROW_HEIGHT, bounds.right, bounds.top + (index + 1) * DROPDOWN_ROW_HEIGHT)
    }

    private companion object {
        private const val FIXED_PANEL_WIDTH = 560
        private const val FIXED_PANEL_HEIGHT = 296
        private const val ROW_HEIGHT = 24
        private const val ROW_GAP = 6
        private const val LABEL_MAX_WIDTH = 180
        private const val COLUMN_WIDTH = 258
        private const val COLUMN_GAP = 18
        private const val VALUE_BOX_X_OFFSET = 183
        private const val VALUE_BOX_WIDTH = 72
        private const val VALUE_BOX_HEIGHT = 18
        private const val HEADER_TO_ROW_GAP = 16
        private const val VALUE_BOX_RIGHT_PADDING = 16
        private const val DROPDOWN_ROW_HEIGHT = 18
    }

    private fun semanticToggleColor(enabled: Boolean, theme: ThemePalette): Int =
        (if (enabled) Formatting.GREEN.colorValue else Formatting.RED.colorValue) ?: theme.lightTextAccent

    private fun semanticSeverityColor(severity: ScammerListManager.ScammerSeverity, theme: ThemePalette): Int =
        when (severity) {
            ScammerListManager.ScammerSeverity.CRITICAL -> Formatting.RED.colorValue ?: theme.lightTextAccent
            ScammerListManager.ScammerSeverity.HIGH -> Formatting.GOLD.colorValue ?: theme.lightTextAccent
            ScammerListManager.ScammerSeverity.MEDIUM -> Formatting.YELLOW.colorValue ?: theme.lightTextAccent
            ScammerListManager.ScammerSeverity.LOW -> theme.mutedText
        }

    private fun semanticNumberColor(theme: ThemePalette): Int = theme.lightTextAccent
}
