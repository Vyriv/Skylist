package dev.ryan.throwerlist

import com.google.gson.GsonBuilder
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object UiLayoutManager {
    private val gson = GsonBuilder().create()
    private const val resourcePath = "throwerlist/layout/ui_layout.json"
    private val defaultLayout = UiLayoutFile()

    @Volatile
    private var currentLayout = defaultLayout

    @Synchronized
    fun load() {
        currentLayout = loadResource().normalized(defaultLayout)
    }

    @Synchronized
    fun mainList(): MainListLayout = currentLayout.mainList

    @Synchronized
    fun entryEditor(): EntryEditorLayout = currentLayout.entryEditor

    @Synchronized
    fun rendering(): RenderingLayout = currentLayout.rendering

    @Synchronized
    fun creditsPanel(): CenteredPanelLayout = currentLayout.panels.credits

    @Synchronized
    fun settingsPanel(): CenteredPanelLayout = currentLayout.panels.settings

    @Synchronized
    fun autokickEditorPanel(): CenteredPanelLayout = currentLayout.panels.autokickEditor

    @Synchronized
    fun tagPickerPanel(): CenteredPanelLayout = currentLayout.panels.tagPicker

    @Synchronized
    fun shareCodePanel(): CenteredPanelLayout = currentLayout.panels.shareCode

    @Synchronized
    fun entryProfilePanel(): CenteredPanelLayout = currentLayout.panels.entryProfile

    @Synchronized
    fun scammerSettingsPanel(): CenteredPanelLayout = currentLayout.panels.scammerSettings

    private fun loadResource(): UiLayoutFile {
        val stream = UiLayoutManager::class.java.classLoader.getResourceAsStream(resourcePath)
        if (stream == null) {
            ThrowerListMod.logger.error("Missing bundled UI layout resource: {}", resourcePath)
            return defaultLayout
        }

        return stream.use { input ->
            runCatching {
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    gson.fromJson(reader, UiLayoutFile::class.java) ?: defaultLayout
                }
            }.getOrElse {
                ThrowerListMod.logger.error("Failed to load bundled UI layout resource: {}", resourcePath, it)
                defaultLayout
            }
        }
    }

    private fun UiLayoutFile.normalized(defaults: UiLayoutFile): UiLayoutFile =
        UiLayoutFile(
            mainList = mainList.normalized(defaults.mainList),
            entryEditor = entryEditor.normalized(defaults.entryEditor),
            rendering = rendering.normalized(defaults.rendering),
            panels = panels.normalized(defaults.panels),
        )

    private fun MainListLayout.normalized(defaults: MainListLayout): MainListLayout =
        MainListLayout(
            frame = frame.normalized(defaults.frame),
            sidebar = sidebar.normalized(defaults.sidebar),
            titleBar = titleBar.normalized(defaults.titleBar),
            tabs = tabs.normalized(defaults.tabs),
            search = search.normalized(defaults.search),
            list = list.normalized(defaults.list),
            actionStrip = actionStrip.normalized(defaults.actionStrip),
            footer = footer.normalized(defaults.footer),
            row = row.normalized(defaults.row),
            tooltip = tooltip.normalized(defaults.tooltip),
            status = status.normalized(defaults.status),
        )

    private fun FrameLayout.normalized(defaults: FrameLayout): FrameLayout =
        FrameLayout(
            maxWidth = maxWidth.positiveOr(defaults.maxWidth),
            dungeonWidth = dungeonWidth.positiveOr(defaults.dungeonWidth),
            horizontalMargin = horizontalMargin.nonNegativeOr(defaults.horizontalMargin),
            top = top.nonNegativeOr(defaults.top),
            bottomMargin = bottomMargin.nonNegativeOr(defaults.bottomMargin),
            contentInset = contentInset.nonNegativeOr(defaults.contentInset),
        )

    private fun TitleBarLayout.normalized(defaults: TitleBarLayout): TitleBarLayout =
        TitleBarLayout(
            height = height.positiveOr(defaults.height),
            controlTopOffset = controlTopOffset.nonNegativeOr(defaults.controlTopOffset),
            buttonHeight = buttonHeight.positiveOr(defaults.buttonHeight),
            buttonGap = buttonGap.nonNegativeOr(defaults.buttonGap),
            creditsButtonWidth = creditsButtonWidth.positiveOr(defaults.creditsButtonWidth),
            settingsButtonWidth = settingsButtonWidth.positiveOr(defaults.settingsButtonWidth),
            discordButtonWidth = discordButtonWidth.positiveOr(defaults.discordButtonWidth),
            donationButtonWidth = donationButtonWidth.positiveOr(defaults.donationButtonWidth),
            trollButtonMinWidth = trollButtonMinWidth.positiveOr(defaults.trollButtonMinWidth),
            trollButtonMaxWidth = trollButtonMaxWidth.positiveOr(defaults.trollButtonMaxWidth),
            trollButtonLeftOffset = trollButtonLeftOffset.nonNegativeOr(defaults.trollButtonLeftOffset),
            trollButtonRightPadding = trollButtonRightPadding.nonNegativeOr(defaults.trollButtonRightPadding),
        )

    private fun SidebarLayout.normalized(defaults: SidebarLayout): SidebarLayout =
        SidebarLayout(
            handleWidth = handleWidth.positiveOr(defaults.handleWidth),
            flyoutWidth = flyoutWidth.positiveOr(defaults.flyoutWidth),
            contentGap = contentGap.nonNegativeOr(defaults.contentGap),
            innerPadding = innerPadding.nonNegativeOr(defaults.innerPadding),
            frameInset = frameInset.nonNegativeOr(defaults.frameInset),
            verticalInset = verticalInset.nonNegativeOr(defaults.verticalInset),
            toggleSize = toggleSize.positiveOr(defaults.toggleSize),
            toggleLeftOffset = toggleLeftOffset.nonNegativeOr(defaults.toggleLeftOffset),
            toggleTopOffset = toggleTopOffset.nonNegativeOr(defaults.toggleTopOffset),
            headerTopOffset = headerTopOffset.nonNegativeOr(defaults.headerTopOffset),
            headerButtonGap = headerButtonGap.nonNegativeOr(defaults.headerButtonGap),
            buttonTopOffset = buttonTopOffset.nonNegativeOr(defaults.buttonTopOffset),
            buttonHeight = buttonHeight.positiveOr(defaults.buttonHeight),
            buttonMinWidth = buttonMinWidth.positiveOr(defaults.buttonMinWidth),
        )

    private fun TabsLayout.normalized(defaults: TabsLayout): TabsLayout =
        TabsLayout(
            topSpacing = topSpacing.nonNegativeOr(defaults.topSpacing),
            height = height.positiveOr(defaults.height),
            gapTotal = gapTotal.nonNegativeOr(defaults.gapTotal),
        )

    private fun SearchLayout.normalized(defaults: SearchLayout): SearchLayout =
        SearchLayout(
            topSpacing = topSpacing.nonNegativeOr(defaults.topSpacing),
            height = height.positiveOr(defaults.height),
            countWidth = countWidth.positiveOr(defaults.countWidth),
            countGap = countGap.nonNegativeOr(defaults.countGap),
        )

    private fun ListAreaLayout.normalized(defaults: ListAreaLayout): ListAreaLayout =
        ListAreaLayout(
            topSpacing = topSpacing.nonNegativeOr(defaults.topSpacing),
        )

    private fun ActionStripLayout.normalized(defaults: ActionStripLayout): ActionStripLayout =
        ActionStripLayout(
            height = height.positiveOr(defaults.height),
            bottomGap = bottomGap.nonNegativeOr(defaults.bottomGap),
            textGap = textGap.nonNegativeOr(defaults.textGap),
            textMinWidth = textMinWidth.positiveOr(defaults.textMinWidth),
            detailButtons = detailButtons.normalized(defaults.detailButtons),
        )

    private fun DetailButtonsLayout.normalized(defaults: DetailButtonsLayout): DetailButtonsLayout =
        DetailButtonsLayout(
            topOffset = topOffset.nonNegativeOr(defaults.topOffset),
            width = width.positiveOr(defaults.width),
            height = height.positiveOr(defaults.height),
            gap = gap.nonNegativeOr(defaults.gap),
        )

    private fun FooterLayout.normalized(defaults: FooterLayout): FooterLayout =
        FooterLayout(
            height = height.positiveOr(defaults.height),
            bottomGap = bottomGap.nonNegativeOr(defaults.bottomGap),
            buttonGap = buttonGap.nonNegativeOr(defaults.buttonGap),
        )

    private fun RowLayout.normalized(defaults: RowLayout): RowLayout =
        RowLayout(
            height = height.positiveOr(defaults.height),
            inset = inset.nonNegativeOr(defaults.inset),
            sourceBadge = sourceBadge.normalized(defaults.sourceBadge),
            remoteState = remoteState.normalized(defaults.remoteState),
            tagBadge = tagBadge.normalized(defaults.tagBadge),
            viewIcon = viewIcon.normalized(defaults.viewIcon),
            editIcon = editIcon.normalized(defaults.editIcon),
        )

    private fun SourceBadgeLayout.normalized(defaults: SourceBadgeLayout): SourceBadgeLayout =
        SourceBadgeLayout(
            padding = padding.nonNegativeOr(defaults.padding),
            rightInset = rightInset.nonNegativeOr(defaults.rightInset),
            topOffset = topOffset.nonNegativeOr(defaults.topOffset),
            height = height.positiveOr(defaults.height),
        )

    private fun VerticalSpanLayout.normalized(defaults: VerticalSpanLayout): VerticalSpanLayout =
        VerticalSpanLayout(
            topOffset = topOffset.nonNegativeOr(defaults.topOffset),
            height = height.positiveOr(defaults.height),
        )

    private fun SquareBadgeLayout.normalized(defaults: SquareBadgeLayout): SquareBadgeLayout =
        SquareBadgeLayout(
            leftOffset = leftOffset.nonNegativeOr(defaults.leftOffset),
            topOffset = topOffset.nonNegativeOr(defaults.topOffset),
            size = size.positiveOr(defaults.size),
        )

    private fun RowIconLayout.normalized(defaults: RowIconLayout): RowIconLayout =
        RowIconLayout(
            rightInset = rightInset.nonNegativeOr(defaults.rightInset),
            width = width.positiveOr(defaults.width),
            topOffset = topOffset.nonNegativeOr(defaults.topOffset),
            height = height.positiveOr(defaults.height),
        )

    private fun TooltipLayout.normalized(defaults: TooltipLayout): TooltipLayout =
        TooltipLayout(
            maxWidth = maxWidth.positiveOr(defaults.maxWidth),
        )

    private fun StatusLayout.normalized(defaults: StatusLayout): StatusLayout =
        StatusLayout(
            height = height.positiveOr(defaults.height),
            bottomGap = bottomGap.nonNegativeOr(defaults.bottomGap),
            horizontalPadding = horizontalPadding.nonNegativeOr(defaults.horizontalPadding),
        )

    private fun EntryEditorLayout.normalized(defaults: EntryEditorLayout): EntryEditorLayout =
        EntryEditorLayout(
            panelWidth = panelWidth.positiveOr(defaults.panelWidth),
            panelHeight = panelHeight.positiveOr(defaults.panelHeight),
            titleBarHeight = titleBarHeight.positiveOr(defaults.titleBarHeight),
            contentInset = contentInset.nonNegativeOr(defaults.contentInset),
            primaryFieldWidth = primaryFieldWidth.positiveOr(defaults.primaryFieldWidth),
            columnGap = columnGap.nonNegativeOr(defaults.columnGap),
            fieldHeight = fieldHeight.positiveOr(defaults.fieldHeight),
            titleTopOffset = titleTopOffset.nonNegativeOr(defaults.titleTopOffset),
            spacing = spacing.normalized(defaults.spacing),
            actions = actions.normalized(defaults.actions),
            statusBottomGap = statusBottomGap.nonNegativeOr(defaults.statusBottomGap),
        )

    private fun EntryEditorSpacing.normalized(defaults: EntryEditorSpacing): EntryEditorSpacing =
        EntryEditorSpacing(
            titleToLabels = titleToLabels.nonNegativeOr(defaults.titleToLabels),
            labelToField = labelToField.nonNegativeOr(defaults.labelToField),
            sectionGap = sectionGap.nonNegativeOr(defaults.sectionGap),
            fieldToActions = fieldToActions.nonNegativeOr(defaults.fieldToActions),
        )

    private fun EntryEditorActions.normalized(defaults: EntryEditorActions): EntryEditorActions =
        EntryEditorActions(
            width = width.positiveOr(defaults.width),
            height = height.positiveOr(defaults.height),
        )

    private fun RenderingLayout.normalized(defaults: RenderingLayout): RenderingLayout =
        RenderingLayout(
            textFieldInsetX = textFieldInsetX.nonNegativeOr(defaults.textFieldInsetX),
            textFieldInsetY = textFieldInsetY.nonNegativeOr(defaults.textFieldInsetY),
            textFieldMinWidth = textFieldMinWidth.positiveOr(defaults.textFieldMinWidth),
            textFieldPlaceholderYOffset = textFieldPlaceholderYOffset.nonNegativeOr(defaults.textFieldPlaceholderYOffset),
        )

    private fun PanelLayouts.normalized(defaults: PanelLayouts): PanelLayouts =
        PanelLayouts(
            credits = credits.normalized(defaults.credits),
            settings = settings.normalized(defaults.settings),
            autokickEditor = autokickEditor.normalized(defaults.autokickEditor),
            tagPicker = tagPicker.normalized(defaults.tagPicker),
            shareCode = shareCode.normalized(defaults.shareCode),
            entryProfile = entryProfile.normalized(defaults.entryProfile),
            scammerSettings = scammerSettings.normalized(defaults.scammerSettings),
        )

    private fun CenteredPanelLayout.normalized(defaults: CenteredPanelLayout): CenteredPanelLayout =
        CenteredPanelLayout(
            width = width.positiveOr(defaults.width),
            height = height.positiveOr(defaults.height),
        )

    private fun Int.positiveOr(fallback: Int): Int = if (this > 0) this else fallback

    private fun Int.nonNegativeOr(fallback: Int): Int = if (this >= 0) this else fallback

    data class UiLayoutFile(
        val mainList: MainListLayout = MainListLayout(),
        val entryEditor: EntryEditorLayout = EntryEditorLayout(),
        val rendering: RenderingLayout = RenderingLayout(),
        val panels: PanelLayouts = PanelLayouts(),
    )

    data class MainListLayout(
        val frame: FrameLayout = FrameLayout(),
        val sidebar: SidebarLayout = SidebarLayout(),
        val titleBar: TitleBarLayout = TitleBarLayout(),
        val tabs: TabsLayout = TabsLayout(),
        val search: SearchLayout = SearchLayout(),
        val list: ListAreaLayout = ListAreaLayout(),
        val actionStrip: ActionStripLayout = ActionStripLayout(),
        val footer: FooterLayout = FooterLayout(),
        val row: RowLayout = RowLayout(),
        val tooltip: TooltipLayout = TooltipLayout(),
        val status: StatusLayout = StatusLayout(),
    )

    data class FrameLayout(
        val maxWidth: Int = 492,
        val dungeonWidth: Int = 420,
        val horizontalMargin: Int = 18,
        val top: Int = 18,
        val bottomMargin: Int = 12,
        val contentInset: Int = 12,
    )

    data class SidebarLayout(
        val handleWidth: Int = 14,
        val flyoutWidth: Int = 122,
        val contentGap: Int = 0,
        val innerPadding: Int = 6,
        val frameInset: Int = 1,
        val verticalInset: Int = 0,
        val toggleSize: Int = 18,
        val toggleLeftOffset: Int = 7,
        val toggleTopOffset: Int = 6,
        val headerTopOffset: Int = 14,
        val headerButtonGap: Int = 8,
        val buttonTopOffset: Int = 0,
        val buttonHeight: Int = 20,
        val buttonMinWidth: Int = 12,
    )

    data class TitleBarLayout(
        val height: Int = 28,
        val controlTopOffset: Int = 5,
        val buttonHeight: Int = 20,
        val buttonGap: Int = 4,
        val creditsButtonWidth: Int = 20,
        val settingsButtonWidth: Int = 20,
        val discordButtonWidth: Int = 20,
        val donationButtonWidth: Int = 20,
        val trollButtonMinWidth: Int = 72,
        val trollButtonMaxWidth: Int = 102,
        val trollButtonLeftOffset: Int = 28,
        val trollButtonRightPadding: Int = 28,
    )

    data class TabsLayout(
        val topSpacing: Int = 18,
        val height: Int = 20,
        val gapTotal: Int = 16,
    )

    data class SearchLayout(
        val topSpacing: Int = 10,
        val height: Int = 20,
        val countWidth: Int = 106,
        val countGap: Int = 8,
    )

    data class ListAreaLayout(
        val topSpacing: Int = 10,
    )

    data class ActionStripLayout(
        val height: Int = 40,
        val bottomGap: Int = 6,
        val textGap: Int = 10,
        val textMinWidth: Int = 80,
        val detailButtons: DetailButtonsLayout = DetailButtonsLayout(),
    )

    data class DetailButtonsLayout(
        val topOffset: Int = 10,
        val width: Int = 80,
        val height: Int = 20,
        val gap: Int = 6,
    )

    data class FooterLayout(
        val height: Int = 20,
        val bottomGap: Int = 8,
        val buttonGap: Int = 8,
    )

    data class RowLayout(
        val height: Int = 54,
        val inset: Int = 4,
        val sourceBadge: SourceBadgeLayout = SourceBadgeLayout(),
        val remoteState: VerticalSpanLayout = VerticalSpanLayout(topOffset = 25, height = 13),
        val tagBadge: SquareBadgeLayout = SquareBadgeLayout(leftOffset = 12, topOffset = 7, size = 13),
        val viewIcon: RowIconLayout = RowIconLayout(rightInset = 32, width = 17, topOffset = 10, height = 16),
        val editIcon: RowIconLayout = RowIconLayout(rightInset = 10, width = 17, topOffset = 10, height = 16),
    )

    data class SourceBadgeLayout(
        val padding: Int = 12,
        val rightInset: Int = 54,
        val topOffset: Int = 8,
        val height: Int = 13,
    )

    data class VerticalSpanLayout(
        val topOffset: Int = 0,
        val height: Int = 0,
    )

    data class SquareBadgeLayout(
        val leftOffset: Int = 0,
        val topOffset: Int = 0,
        val size: Int = 0,
    )

    data class RowIconLayout(
        val rightInset: Int = 0,
        val width: Int = 0,
        val topOffset: Int = 0,
        val height: Int = 0,
    )

    data class TooltipLayout(
        val maxWidth: Int = 170,
    )

    data class StatusLayout(
        val height: Int = 10,
        val bottomGap: Int = 4,
        val horizontalPadding: Int = 12,
    )

    data class EntryEditorLayout(
        val panelWidth: Int = 340,
        val panelHeight: Int = 180,
        val titleBarHeight: Int = 22,
        val contentInset: Int = 16,
        val primaryFieldWidth: Int = 124,
        val columnGap: Int = 12,
        val fieldHeight: Int = 20,
        val titleTopOffset: Int = 8,
        val spacing: EntryEditorSpacing = EntryEditorSpacing(),
        val actions: EntryEditorActions = EntryEditorActions(),
        val statusBottomGap: Int = 8,
    )

    data class EntryEditorSpacing(
        val titleToLabels: Int = 20,
        val labelToField: Int = 12,
        val sectionGap: Int = 16,
        val fieldToActions: Int = 28,
    )

    data class EntryEditorActions(
        val width: Int = 100,
        val height: Int = 20,
    )

    data class RenderingLayout(
        val textFieldInsetX: Int = 6,
        val textFieldInsetY: Int = 4,
        val textFieldMinWidth: Int = 24,
        val textFieldPlaceholderYOffset: Int = 2,
    )

    data class PanelLayouts(
        val credits: CenteredPanelLayout = CenteredPanelLayout(width = 380, height = 220),
        val settings: CenteredPanelLayout = CenteredPanelLayout(width = 360, height = 236),
        val autokickEditor: CenteredPanelLayout = CenteredPanelLayout(width = 380, height = 220),
        val tagPicker: CenteredPanelLayout = CenteredPanelLayout(width = 372, height = 236),
        val shareCode: CenteredPanelLayout = CenteredPanelLayout(width = 420, height = 168),
        val entryProfile: CenteredPanelLayout = CenteredPanelLayout(width = 496, height = 336),
        val scammerSettings: CenteredPanelLayout = CenteredPanelLayout(width = 420, height = 352),
    )

    data class CenteredPanelLayout(
        val width: Int = 320,
        val height: Int = 200,
    )
}
