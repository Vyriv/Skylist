package dev.ryan.throwerlist

import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SkylistMainScreen(
    initialSearch: String = "",
) : Screen(Text.literal("Skylist")) {
    private companion object {
        const val MAX_FRAME_WIDTH = 492
        const val MAX_FRAME_HEIGHT = 324
        const val WINDOW_MARGIN = 18
        const val MIN_FRAME_WIDTH = 360
        const val MIN_FRAME_HEIGHT = 288
        const val FRAME_CONTENT_MARGIN = 16
        const val TITLE_BAR_HEIGHT = 26
        const val SECTION_LABEL_Y = 34
        const val PANEL_TOP = 48
        const val PANEL_GAP = 12
        const val PANEL_PADDING = 10
        const val LEFT_PANEL_WIDTH = 214
        const val SEARCH_HEIGHT = 20
        const val SEARCH_LIST_GAP = 8
        const val LIST_VIEWPORT_INSET = 1
        const val ROW_SLOT_HEIGHT = 34
        const val ROW_INSET = 2
        const val ROW_HORIZONTAL_PADDING = 8
        const val ROW_TEXT_GAP = 2
        const val FOOTER_HEIGHT = 42
        const val FOOTER_BOTTOM_INSET = 14
        const val FOOTER_PANEL_GAP = 8
        const val FOOTER_STATUS_TOP = 2
        const val BUTTON_HEIGHT = 20
        const val BUTTON_GAP = 6
        const val BUTTON_GROUP_GAP = 16
        const val REMOVE_BUTTON_WIDTH = 92
        const val DONE_BUTTON_WIDTH = 82
        const val DETAIL_LABEL_GAP = 3
        const val DETAIL_LINE_GAP = 2
        const val DETAIL_SECTION_GAP = 7
    }

    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        fun contains(mouseX: Double, mouseY: Double): Boolean =
            mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom

        fun width(): Int = right - left
        fun height(): Int = bottom - top
        fun centerX(): Int = (left + right) / 2
    }

    private data class FooterButtonLayout(
        val buttonTop: Int,
        val secondaryWidth: Int,
        val refreshX: Int,
        val settingsX: Int,
        val checkX: Int,
        val removeX: Int,
        val doneX: Int,
    )

    private val timestampFormatter = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yy")
    private var searchField: TextFieldWidget? = null
    private var refreshButton: ButtonWidget? = null
    private var settingsButton: ButtonWidget? = null
    private var checkButton: ButtonWidget? = null
    private var removeCachedButton: ButtonWidget? = null
    private var doneButton: ButtonWidget? = null
    private var leftMouseDown = false
    private var searchQuery = initialSearch
    private var allEntries = emptyList<ScammerListManager.ScammerEntry>()
    private var filteredEntries = emptyList<ScammerListManager.ScammerEntry>()
    private var selectedUuid: String? = null
    private var scrollOffset = 0
    private var statusMessage: String? = null
    private var statusColor = 0xFF7FD6FF.toInt()
    private var refreshing = false

    override fun init() {
        super.init()
        val layout = footerButtonLayout()
        val search = searchRect()

        searchField = TextFieldWidget(
            textRenderer,
            search.left,
            search.top,
            search.width(),
            search.height(),
            Text.literal("Search scammers..."),
        ).also {
            it.setDrawsBackground(false)
            it.setMaxLength(64)
            ThemeRenderer.applyTextFieldInset(it)
            it.text = searchQuery
            it.setChangedListener { value ->
                searchQuery = value
                refreshEntries(keepSelection = true)
            }
            addDrawableChild(it)
            setInitialFocus(it)
        }

        refreshButton = themedButton("Refresh", layout.refreshX, layout.buttonTop, layout.secondaryWidth) {
            refreshing = true
            statusMessage = "Refreshing scammer cache..."
            statusColor = 0xFF7FD6FF.toInt()
            ScammerListManager.refreshAsync().whenComplete { _, throwable ->
                client?.execute {
                    refreshing = false
                    if (throwable != null) {
                        statusMessage = ScammerListManager.lastFailureReason() ?: "Refresh failed."
                        statusColor = 0xFFFF7777.toInt()
                    } else {
                        refreshEntries(keepSelection = true)
                        statusMessage = "Scammer cache refreshed."
                        statusColor = 0xFF88FF88.toInt()
                    }
                }
            }
        }

        settingsButton = themedButton("Settings", layout.settingsX, layout.buttonTop, layout.secondaryWidth) {
            client?.setScreen(SkylistBaseSettingsScreen(this))
        }

        checkButton = themedButton("Check", layout.checkX, layout.buttonTop, layout.secondaryWidth) {
            client?.setScreen(ScammerCheckLookupScreen(this, selectedEntry()?.username.orEmpty()))
        }

        removeCachedButton = themedButton("Remove", layout.removeX, layout.buttonTop, REMOVE_BUTTON_WIDTH) {
            val selected = selectedEntry() ?: return@themedButton
            if (ScammerListManager.removeCachedEntry(selected.uuid)) {
                statusMessage = "Removed cached scammer entry for ${selected.username}."
                statusColor = 0xFFFFDD77.toInt()
                refreshEntries(keepSelection = false)
            }
        }

        doneButton = themedButton("Done", layout.doneX, layout.buttonTop, DONE_BUTTON_WIDTH) {
            close()
        }

        refreshEntries(keepSelection = false)
        updateButtons()
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        client?.setScreen(null)
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, deltaTicks: Float) {
        val theme = ThemeManager.current()
        val frame = frameRect()
        val listPanel = listPanelRect()
        val list = listRect()
        val detail = detailRect()
        val footer = footerRect()

        context.fill(0, 0, width, height, theme.overlayBackground)
        ThemeRenderer.drawPanel(context, frame.left, frame.top, frame.right, frame.bottom, TITLE_BAR_HEIGHT, theme)
        drawCentered(context, title.string, frame.centerX(), frame.top + 9, 0xFFFFFFFF.toInt())
        drawText(context, "Scammer List", listPanel.left, frame.top + SECTION_LABEL_Y, theme.hoverAccent)
        drawText(context, "Details", detail.left, frame.top + SECTION_LABEL_Y, theme.hoverAccent)

        context.fill(listPanel.left, listPanel.top, listPanel.right, listPanel.bottom, theme.withAlpha(theme.secondaryPanel, 0xB0))
        context.fill(detail.left, detail.top, detail.right, detail.bottom, theme.withAlpha(theme.secondaryPanel, 0xB0))
        ThemeRenderer.drawOutline(context, listPanel.left, listPanel.top, listPanel.width(), listPanel.height(), theme.idleBorder)
        ThemeRenderer.drawOutline(context, detail.left, detail.top, detail.width(), detail.height(), theme.idleBorder)

        ThemeRenderer.drawTextField(context, searchField, theme)
        context.fill(list.left, list.top, list.right, list.bottom, theme.listBackground)
        ThemeRenderer.drawOutline(context, list.left, list.top, list.width(), list.height(), theme.idleBorder)
        context.fill(footer.left, footer.top - FOOTER_PANEL_GAP, footer.right, footer.top - FOOTER_PANEL_GAP + 1, theme.idleBorder)

        drawSearchPlaceholder(context, theme)
        drawEntries(context, mouseX.toDouble(), mouseY.toDouble(), list, theme)
        drawDetails(context, detail, theme)
        drawFooterStatus(context, footer, theme)

        super.render(context, mouseX, mouseY, deltaTicks)
        listOfNotNull(refreshButton, settingsButton, checkButton, removeCachedButton, doneButton)
            .forEach { ThemeRenderer.drawButton(context, it, mouseX.toDouble(), mouseY.toDouble(), leftMouseDown, theme) }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            leftMouseDown = true
        }

        if (click.button() != 0) {
            return super.mouseClicked(click, doubled)
        }

        hoveredEntry(click.x(), click.y())?.let { entry ->
            selectedUuid = entry.uuid
            statusMessage = null
            if (doubled) {
                client?.setScreen(ScammerCheckLookupScreen(this, entry.username))
            }
            updateButtons()
            return true
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
        if (!listRect().contains(mouseX, mouseY)) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
        }

        val maxOffset = (filteredEntries.size - visibleRows()).coerceAtLeast(0)
        if (maxOffset == 0) {
            scrollOffset = 0
            return true
        }

        val delta = when {
            verticalAmount > 0 -> -1
            verticalAmount < 0 -> 1
            else -> 0
        }
        scrollOffset = (scrollOffset + delta).coerceIn(0, maxOffset)
        return true
    }

    fun onReturnMessage(message: String?, color: Int) {
        if (!message.isNullOrBlank()) {
            statusMessage = message
            statusColor = color
        }
        refreshEntries(keepSelection = true)
        updateButtons()
    }

    private fun refreshEntries(keepSelection: Boolean) {
        allEntries = ScammerListManager.listEntries()
        filteredEntries = allEntries.filter { entry ->
            val query = searchQuery.trim()
            if (query.isBlank()) {
                true
            } else {
                val normalized = query.lowercase()
                entry.username.lowercase().contains(normalized) ||
                    entry.reason.lowercase().contains(normalized) ||
                    entry.uuid.lowercase().contains(normalized) ||
                    entry.altUsernames.any { it.lowercase().contains(normalized) } ||
                    entry.discordUsers.any { it.label.lowercase().contains(normalized) }
            }
        }

        if (!keepSelection || filteredEntries.none { it.uuid == selectedUuid }) {
            selectedUuid = filteredEntries.firstOrNull()?.uuid
        }

        val maxOffset = (filteredEntries.size - visibleRows()).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)
        updateButtons()
    }

    private fun updateButtons() {
        val hasSelection = selectedEntry() != null
        checkButton?.active = !refreshing
        removeCachedButton?.active = hasSelection && !refreshing
        refreshButton?.active = !refreshing
    }

    private fun themedButton(label: String, x: Int, y: Int, width: Int, onPress: () -> Unit): ButtonWidget =
        ThemedButtonWidget.builder(Text.literal(label)) { onPress() }
            .dimensions(x, y, width, BUTTON_HEIGHT)
            .build()
            .also { addDrawableChild(it) }

    private fun drawEntries(context: DrawContext, mouseX: Double, mouseY: Double, list: Rect, theme: ThemePalette) {
        if (filteredEntries.isEmpty()) {
            drawCentered(context, "No scammer entries match your search.", list.centerX(), list.top + 18, theme.subtleText)
            return
        }

        visibleEntries().forEachIndexed { index, entry ->
            val row = rowRect(index)
            val selected = entry.uuid == selectedUuid
            val hovered = row.contains(mouseX, mouseY)
            val fill = when {
                selected -> theme.withAlpha(theme.hoverAccent, 0x70)
                hovered -> theme.withAlpha(theme.secondaryPanel, 0xD8)
                else -> theme.withAlpha(theme.panelBackground, 0x80)
            }
            context.fill(row.left, row.top, row.right, row.bottom, fill)
            ThemeRenderer.drawOutline(context, row.left, row.top, row.width(), row.height(), if (selected) theme.primaryAccent else theme.idleBorder)

            val contentLeft = row.left + ROW_HORIZONTAL_PADDING
            val contentRight = row.right - ROW_HORIZONTAL_PADDING
            val severity = entry.severity.label
            val severityWidth = textRenderer.getWidth(severity)
            val severityX = contentRight - severityWidth
            val lineTop = row.top + (row.height() - (textRenderer.fontHeight * 2 + ROW_TEXT_GAP)) / 2
            val usernameWidth = (severityX - contentLeft - 8).coerceAtLeast(48)
            val previewWidth = (contentRight - contentLeft).coerceAtLeast(48)
            val previewColor = if (selected) theme.lightTextAccent else theme.subtleText

            drawText(context, ellipsizeToWidth(entry.username, usernameWidth), contentLeft, lineTop, 0xFFFFFFFF.toInt())
            drawText(context, severity, severityX, lineTop, entry.severity.color)
            drawText(context, ellipsizeToWidth(entry.reason, previewWidth), contentLeft, lineTop + textRenderer.fontHeight + ROW_TEXT_GAP, previewColor)
        }
    }

    private fun drawDetails(context: DrawContext, detail: Rect, theme: ThemePalette) {
        val entry = selectedEntry()
        if (entry == null) {
            drawCentered(context, "Select a scammer entry to view details.", detail.centerX(), detail.top + 18, theme.subtleText)
            return
        }

        val contentLeft = detail.left + PANEL_PADDING
        val contentWidth = detail.width() - PANEL_PADDING * 2
        val contentRight = detail.right - PANEL_PADDING
        var y = detail.top + PANEL_PADDING

        drawText(context, ellipsizeToWidth(entry.username, (contentWidth - 70).coerceAtLeast(90)), contentLeft, y, 0xFFFFFFFF.toInt())
        drawText(context, entry.severity.label, contentRight - textRenderer.getWidth(entry.severity.label), y, entry.severity.color)
        y += textRenderer.fontHeight + DETAIL_SECTION_GAP

        y = drawWrappedBlock(context, "UUID", entry.uuid, contentLeft, y, contentWidth, theme, theme.lightTextAccent, detail.bottom - PANEL_PADDING)
        y = drawWrappedBlock(context, "Added", formatTimestamp(entry.creationTimeMillis), contentLeft, y, contentWidth, theme, theme.subtleText, detail.bottom - PANEL_PADDING)
        y = drawWrappedBlock(context, "Reason", entry.reason, contentLeft, y, contentWidth, theme, 0xFFFFFFFF.toInt(), detail.bottom - PANEL_PADDING)
        if (entry.discordUsers.isNotEmpty() || entry.discordIds.isNotEmpty()) {
            val discordText = entry.discordUsers.map { it.label }.ifEmpty { entry.discordIds }.joinToString(", ")
            y = drawWrappedBlock(context, "Discord", discordText, contentLeft, y, contentWidth, theme, 0xFFFFFFFF.toInt(), detail.bottom - PANEL_PADDING)
        }
        if (entry.altUsernames.isNotEmpty()) {
            y = drawWrappedBlock(context, "Alt usernames", entry.altUsernames.joinToString(", "), contentLeft, y, contentWidth, theme, 0xFFFFFFFF.toInt(), detail.bottom - PANEL_PADDING)
        }
        if (entry.altUuids.isNotEmpty()) {
            y = drawWrappedBlock(context, "Alt UUIDs", entry.altUuids.joinToString(", "), contentLeft, y, contentWidth, theme, 0xFFFFFFFF.toInt(), detail.bottom - PANEL_PADDING)
        }
        if (!entry.evidence.isNullOrBlank()) {
            drawWrappedBlock(context, "Evidence", entry.evidence, contentLeft, y, contentWidth, theme, 0xFFFFFFFF.toInt(), detail.bottom - PANEL_PADDING)
        }
    }

    private fun drawWrappedBlock(
        context: DrawContext,
        label: String,
        value: String,
        x: Int,
        y: Int,
        width: Int,
        theme: ThemePalette,
        valueColor: Int,
        bottomLimit: Int,
    ): Int {
        if (y > bottomLimit - textRenderer.fontHeight) {
            return y
        }

        drawText(context, label, x, y, theme.hoverAccent)
        val availableLines = ((bottomLimit - (y + textRenderer.fontHeight + DETAIL_LABEL_GAP)) / (textRenderer.fontHeight + DETAIL_LINE_GAP)).coerceAtLeast(1)
        val wrappedLines = wrappedLines(value, width)
        val linesToDraw = if (wrappedLines.size > availableLines) {
            wrappedLines.take(availableLines).toMutableList().also { lines ->
                lines[lines.lastIndex] = ellipsizeToWidth(lines.last() + "...", width)
            }
        } else {
            wrappedLines
        }

        var currentY = y + textRenderer.fontHeight + DETAIL_LABEL_GAP
        linesToDraw.forEach { line ->
            drawText(context, line, x, currentY, valueColor)
            currentY += textRenderer.fontHeight + DETAIL_LINE_GAP
        }
        return currentY + DETAIL_SECTION_GAP
    }

    private fun drawFooterStatus(context: DrawContext, footer: Rect, theme: ThemePalette) {
        val status = statusMessage ?: "Loaded ${allEntries.size} scammer entr${if (allEntries.size == 1) "y" else "ies"}."
        drawText(
            context,
            ellipsizeToWidth(status, footer.width()),
            footer.left,
            footer.top + FOOTER_STATUS_TOP,
            statusColor.takeIf { statusMessage != null } ?: theme.subtleText,
        )
    }

    private fun drawSearchPlaceholder(context: DrawContext, theme: ThemePalette) {
        val field = searchField ?: return
        if (field.text.isNotEmpty() || field.isFocused) {
            return
        }
        drawText(
            context,
            ellipsizeToWidth("Search by username, UUID, reason, or Discord label...", field.width),
            ThemeRenderer.textFieldPlaceholderX(field),
            ThemeRenderer.textFieldPlaceholderY(field),
            theme.subtleText,
        )
    }

    private fun wrappedLines(text: String, maxWidth: Int): List<String> {
        if (text.isBlank()) {
            return listOf("-")
        }

        val lines = mutableListOf<String>()
        val paragraphs = text.replace('\r', '\n').split('\n')
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.isBlank()) {
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) {
                    lines += ""
                }
                return@forEachIndexed
            }

            var currentLine = ""
            paragraph.split(' ').filter { it.isNotBlank() }.forEach { word ->
                if (textRenderer.getWidth(word) > maxWidth) {
                    if (currentLine.isNotEmpty()) {
                        lines += currentLine
                        currentLine = ""
                    }
                    val chunks = splitToWidth(word, maxWidth)
                    chunks.dropLast(1).forEach(lines::add)
                    currentLine = chunks.lastOrNull().orEmpty()
                    return@forEach
                }

                val candidate = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (textRenderer.getWidth(candidate) <= maxWidth) {
                    currentLine = candidate
                } else {
                    lines += currentLine
                    currentLine = word
                }
            }

            if (currentLine.isNotEmpty()) {
                lines += currentLine
            }

            if (paragraphIndex != paragraphs.lastIndex && lines.isNotEmpty() && lines.last().isNotEmpty()) {
                lines += ""
            }
        }

        return lines.ifEmpty { listOf("-") }
    }

    private fun visibleEntries(): List<ScammerListManager.ScammerEntry> =
        filteredEntries.drop(scrollOffset).take(visibleRows())

    private fun visibleRows(): Int = (listRect().height() / ROW_SLOT_HEIGHT).coerceAtLeast(1)

    private fun hoveredEntry(mouseX: Double, mouseY: Double): ScammerListManager.ScammerEntry? =
        visibleEntries().withIndex().firstOrNull { indexed ->
            rowRect(indexed.index).contains(mouseX, mouseY)
        }
            ?.value

    private fun selectedEntry(): ScammerListManager.ScammerEntry? =
        filteredEntries.firstOrNull { it.uuid == selectedUuid } ?: allEntries.firstOrNull { it.uuid == selectedUuid }

    private fun ellipsizeToWidth(value: String, maxWidth: Int): String {
        if (value.isEmpty() || maxWidth <= 0) {
            return ""
        }
        if (textRenderer.getWidth(value) <= maxWidth) {
            return value
        }

        val ellipsis = "..."
        if (textRenderer.getWidth(ellipsis) >= maxWidth) {
            return ellipsis.take(1)
        }

        var result = value
        while (result.isNotEmpty()) {
            val candidate = result.trimEnd() + ellipsis
            if (textRenderer.getWidth(candidate) <= maxWidth) {
                return candidate
            }
            result = result.dropLast(1)
        }
        return ellipsis
    }

    private fun splitToWidth(value: String, maxWidth: Int): List<String> {
        if (value.isEmpty() || maxWidth <= 0) {
            return listOf(value)
        }

        val chunks = mutableListOf<String>()
        var current = ""
        value.forEach { char ->
            val candidate = current + char
            if (current.isEmpty() || textRenderer.getWidth(candidate) <= maxWidth) {
                current = candidate
            } else {
                chunks += current
                current = char.toString()
            }
        }
        if (current.isNotEmpty()) {
            chunks += current
        }
        return chunks.ifEmpty { listOf(value) }
    }

    private fun formatTimestamp(timestamp: Long?): String {
        if (timestamp == null || timestamp <= 0L) {
            return "Unknown"
        }

        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(timestampFormatter)
    }

    private fun frameRect(): Rect =
        Rect(
            width / 2 - frameWidth() / 2,
            height / 2 - frameHeight() / 2,
            width / 2 + frameWidth() / 2,
            height / 2 + frameHeight() / 2,
        )

    private fun frameWidth(): Int =
        (width - WINDOW_MARGIN * 2).coerceAtMost(MAX_FRAME_WIDTH).coerceAtLeast(MIN_FRAME_WIDTH)

    private fun frameHeight(): Int =
        (height - WINDOW_MARGIN * 2).coerceAtMost(MAX_FRAME_HEIGHT).coerceAtLeast(MIN_FRAME_HEIGHT)

    private fun contentRect(): Rect {
        val frame = frameRect()
        return Rect(frame.left + FRAME_CONTENT_MARGIN, frame.top, frame.right - FRAME_CONTENT_MARGIN, frame.bottom)
    }

    private fun listPanelRect(): Rect {
        val content = contentRect()
        val maxLeftPanelRight = content.right - PANEL_GAP - 168
        val right = (content.left + LEFT_PANEL_WIDTH).coerceAtMost(maxLeftPanelRight)
        return Rect(content.left, frameRect().top + PANEL_TOP, right, footerRect().top - FOOTER_PANEL_GAP)
    }

    private fun searchRect(): Rect {
        val panel = listPanelRect()
        return Rect(panel.left + PANEL_PADDING, panel.top + PANEL_PADDING, panel.right - PANEL_PADDING, panel.top + PANEL_PADDING + SEARCH_HEIGHT)
    }

    private fun listRect(): Rect {
        val panel = listPanelRect()
        val search = searchRect()
        return Rect(panel.left + LIST_VIEWPORT_INSET, search.bottom + SEARCH_LIST_GAP, panel.right - LIST_VIEWPORT_INSET, panel.bottom - LIST_VIEWPORT_INSET)
    }

    private fun detailRect(): Rect {
        val listPanel = listPanelRect()
        val content = contentRect()
        return Rect(listPanel.right + PANEL_GAP, listPanel.top, content.right, listPanel.bottom)
    }

    private fun footerRect(): Rect {
        val frame = frameRect()
        val content = contentRect()
        return Rect(content.left, frame.bottom - FOOTER_HEIGHT - FOOTER_BOTTOM_INSET, content.right, frame.bottom - FOOTER_BOTTOM_INSET)
    }

    private fun rowRect(index: Int): Rect {
        val list = listRect()
        val rowTop = list.top + index * ROW_SLOT_HEIGHT
        return Rect(list.left + ROW_INSET, rowTop + ROW_INSET, list.right - ROW_INSET, rowTop + ROW_SLOT_HEIGHT - ROW_INSET)
    }

    private fun footerButtonLayout(): FooterButtonLayout {
        val footer = footerRect()
        val buttonTop = footer.bottom - BUTTON_HEIGHT
        val secondaryWidth = (
            footer.width() -
                REMOVE_BUTTON_WIDTH -
                DONE_BUTTON_WIDTH -
                BUTTON_GROUP_GAP -
                (BUTTON_GAP * 3)
            ) / 3
        val clampedSecondaryWidth = secondaryWidth.coerceAtLeast(70)
        val refreshX = footer.left
        val settingsX = refreshX + clampedSecondaryWidth + BUTTON_GAP
        val checkX = settingsX + clampedSecondaryWidth + BUTTON_GAP
        val doneX = footer.right - DONE_BUTTON_WIDTH
        val removeX = doneX - BUTTON_GAP - REMOVE_BUTTON_WIDTH
        return FooterButtonLayout(
            buttonTop = buttonTop,
            secondaryWidth = clampedSecondaryWidth,
            refreshX = refreshX,
            settingsX = settingsX,
            checkX = checkX,
            removeX = removeX,
            doneX = doneX,
        )
    }

    private fun drawCentered(context: DrawContext, text: String, centerX: Int, y: Int, color: Int) {
        ThemeRenderer.drawCenteredTextWithShadow(context, textRenderer, text, centerX, y, color)
    }

    private fun drawText(context: DrawContext, text: String, x: Int, y: Int, color: Int) {
        ThemeRenderer.drawTextWithShadow(context, textRenderer, text, x, y, color)
    }
}
