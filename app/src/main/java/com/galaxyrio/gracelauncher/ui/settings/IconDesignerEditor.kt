@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.icons.IconLayers
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import com.galaxyrio.gracelauncher.data.icons.ItemIconStore
import com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon
import com.galaxyrio.gracelauncher.data.icons.isGraceButton
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import kotlinx.coroutines.launch

private enum class DesignerPage { Single, All, Data }

@Composable
internal fun IconDesignerSettings(
    uiState: LauncherUiState, actions: LauncherActions, selectedKey: String?, onBack: () -> Unit,
    onChooseApp: () -> Unit, onSelectApp: (String) -> Unit, fallbackApp: LauncherApp? = null,
) {
    val context = LocalContext.current
    val repository = remember(context) { IconPackRepository(context) }
    val store = remember(context) { ItemIconStore(context, repository) }
    val scope = rememberCoroutineScope()
    var pageName by rememberSaveable { mutableStateOf(if (selectedKey == null) DesignerPage.All.name else DesignerPage.Single.name) }
    val page = DesignerPage.entries.firstOrNull { it.name == pageName } ?: DesignerPage.Single
    val app = selectedKey?.let(uiState::findItem) ?: fallbackApp?.takeIf { it.key == selectedKey }
    val button = app?.isGraceButton == true
    val initial = initialDesignerChoice(uiState, app)
    var special by rememberSaveable(selectedKey, app?.key) { mutableStateOf(initial.encode()) }
    var specialBaseline by rememberSaveable(selectedKey, app?.key) { mutableStateOf(initial.encode()) }
    var specialSaved by rememberSaveable(selectedKey, app?.key) { mutableStateOf(selectedKey in uiState.itemIcons) }
    var bulk by rememberSaveable { mutableStateOf(ItemIcon.Theme.copy(design = sharedDesignerDefaults(uiState)).encode()) }
    var bulkBaseline by rememberSaveable { mutableStateOf(bulk) }
    var pendingImages by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sourcePicker by rememberSaveable { mutableStateOf(false) }
    var colorPicker by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var saving by remember { mutableStateOf(false) }
    val specialChoice = remember(special) { ItemIcon.decode(special) ?: initial }
    val bulkChoice = remember(bulk) { ItemIcon.decode(bulk) ?: ItemIcon.Theme.copy(design = sharedDesignerDefaults(uiState)) }
    val choice = if (page == DesignerPage.All) bulkChoice else specialChoice
    val design = choice.design ?: IconDesign()
    val dirty = when (page) {
        DesignerPage.Single -> app != null && normalizedChoice(specialChoice) != normalizedChoice(ItemIcon.decode(specialBaseline) ?: initial)
        DesignerPage.All -> normalizedChoice(bulkChoice) != normalizedChoice(ItemIcon.decode(bulkBaseline) ?: ItemIcon.Theme)
        DesignerPage.Data -> false
    }
    fun change(value: ItemIcon) {
        if (saving) return
        if (page == DesignerPage.All) bulk = value.encode() else if (page == DesignerPage.Single && app != null) special = value.encode()
    }
    fun changeDesign(value: IconDesign) { change(choice.copy(design = value.normalized())) }
    suspend fun cleanImports(keep: String? = null) {
        val discarded = pendingImages.filterNot { it == keep }
        pendingImages = emptyList()
        discarded.forEach { store.deleteImage(ItemIcon("image", it)) }
    }
    fun perform(target: String) {
        when {
            target == "exit" -> scope.launch { cleanImports(); onBack() }
            target == "choose" -> onChooseApp()
            target.startsWith("page:") -> {
                colorPicker = null; sourcePicker = false; selection = emptyList()
                if (target == "page:Single" && !specialSaved) {
                    val savedBulk = ItemIcon.decode(bulkBaseline)
                    special = initialDesignerChoice(uiState.copy(settings = uiState.settings.copy(iconDesign = savedBulk)), app).encode()
                    specialBaseline = special
                }
                pageName = target.removePrefix("page:")
            }
            target.startsWith("edit:") -> {
                val key = target.removePrefix("edit:")
                if (uiState.findItem(key) == null && fallbackApp?.key != key) {
                    Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
                } else {
                    special = initialDesignerChoice(uiState, uiState.findItem(key) ?: fallbackApp).encode()
                    specialBaseline = special
                    specialSaved = true
                    onSelectApp(key); selection = emptyList(); pageName = DesignerPage.Single.name
                }
            }
        }
    }
    fun request(target: String) {
        if (saving || pendingAction != null) return
        if (dirty) pendingAction = target else perform(target)
    }
    fun save(then: String? = null) {
        if (saving || page == DesignerPage.Data || (page == DesignerPage.Single && app == null)) return
        val savedPage = page
        val saved = normalizedChoice(if (page == DesignerPage.All) ItemIcon.Theme.copy(design = design)
            else choice.copy(design = design.copy(iconSize = if (button) 100 else bulkChoice.design?.iconSize ?: 100)))
        val savedApp = app
        saving = true
        scope.launch {
            try {
                val success = if (savedPage == DesignerPage.All) actions.applyIconDesign(saved)
                    else savedApp?.let { actions.setItemIcon(it, saved) } == true
                if (success) {
                    if (savedPage == DesignerPage.All) { bulk = saved.encode(); bulkBaseline = bulk }
                    else { special = saved.encode(); specialBaseline = special; specialSaved = true }
                    cleanImports(saved.source.takeIf { saved.kind == "image" })
                    colorPicker = null; pendingAction = null
                    if (then != null) perform(then)
                    else Toast.makeText(context, R.string.icon_designer_saved, Toast.LENGTH_SHORT).show()
                } else Toast.makeText(context, R.string.icon_edit_failed, Toast.LENGTH_LONG).show()
            } finally { saving = false }
        }
    }
    val back: () -> Unit = {
        if (!saving) when {
            sourcePicker -> sourcePicker = false
            colorPicker != null -> colorPicker = null
            page == DesignerPage.Data && selection.isNotEmpty() -> selection = emptyList()
            else -> request("exit")
        }
    }
    // Own Back for every designer page so Android and the app bar use the same draft guard.
    BackHandler(onBack = back)
    val pending = pendingAction
    if (pending != null) AlertDialog(
        onDismissRequest = { if (!saving) pendingAction = null },
        title = { Text(stringResource(R.string.icon_designer_unsaved_title)) },
        text = { Text(stringResource(R.string.icon_designer_unsaved_message)) },
        confirmButton = { TextButton(onClick = { save(pending) }, enabled = !saving) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { pendingAction = null }, enabled = !saving) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = {
                    saving = true
                    scope.launch {
                        try {
                            if (page == DesignerPage.All) bulk = bulkBaseline else special = specialBaseline
                            cleanImports(); pendingAction = null; perform(pending)
                        } finally { saving = false }
                    }
                }, enabled = !saving) { Text(stringResource(R.string.icon_designer_discard)) }
            }
        }, modifier = Modifier.testTag("icon_designer_unsaved"),
    )
    if (sourcePicker) {
        IconDesignerSourceSettings(uiState, repository, store, app?.componentName,
            onSelect = { change(it.copy(design = design)); sourcePicker = false },
            onImport = { pendingImages = pendingImages + it.source }, onBack = { sourcePicker = false })
        return
    }
    val configuration = LocalConfiguration.current
    val layers by produceState<IconLayers?>(null, app?.key, specialChoice.kind, specialChoice.source, specialChoice.name,
        uiState.settings, configuration) {
        value = null
        if (app != null) value = store.layers(app, specialChoice, uiState.settings, size = 768)
    }
    val layered = layers?.layered ?: (specialChoice.kind != "image" && app?.isAdaptiveIcon == true)
    val dynamicColors = store.dynamicColors(uiState.settings)
    val themeColors = store.themeColors(uiState.settings)
    val shared = bulkChoice.design ?: sharedDesignerDefaults(uiState)
    LaunchedEffect(uiState.itemIcons.keys) { selection = selection.filter { it in uiState.itemIcons } }
    SettingsScaffold(stringResource(R.string.icon_designer_title), "icon_designer", back, fixedCollapsed = true,
        actions = {
            if (page == DesignerPage.Data) {
                if (selection.isNotEmpty()) Text(selection.size.toString(), style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = {
                    val keys = selection.toSet()
                    saving = true
                    scope.launch {
                        try {
                            if (actions.deleteIconDesigns(keys)) {
                                selection = emptyList()
                                if (selectedKey in keys) {
                                    special = initialDesignerChoice(uiState, app, includeSpecial = false).encode()
                                    specialBaseline = special
                                    specialSaved = false
                                }
                            } else Toast.makeText(context, R.string.icon_edit_failed, Toast.LENGTH_LONG).show()
                        } finally { saving = false }
                    }
                }, enabled = selection.isNotEmpty() && !saving, modifier = Modifier.testTag("icon_designer_delete")) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ms_delete), stringResource(R.string.icon_designer_delete_data))
                }
            } else Button(onClick = { save() }, enabled = !saving && (page == DesignerPage.All || app != null),
                modifier = Modifier.padding(end = 8.dp).testTag("icon_designer_save")) { Text(stringResource(R.string.icon_designer_save)) }
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val previewHeight = ((maxHeight - LocalSettingsBottomInset.current) * .40f).coerceIn(if (page == DesignerPage.All)
                (40.dp * (shared.iconSize / 100f) + 16.dp) * 3 + 24.dp
                else 160.dp * (if (button) 1f else shared.iconSize / 100f) + 24.dp, 264.dp)
            AnimatedContent(page, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).clipToBounds(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(300)) { it * direction } + fadeIn(tween(150))) togetherWith
                        (slideOutHorizontally(tween(300)) { -it * direction } + fadeOut(tween(150)))
                }, label = "iconDesignerPage") { displayed ->
                if (displayed == DesignerPage.Data) IconDesignerData(uiState, selection.toSet(), !saving && page == displayed,
                    onSelection = { selection = it.toList() }, onEdit = { request("edit:$it") })
                else {
                    val isAll = displayed == DesignerPage.All
                    val shownChoice = if (isAll) bulkChoice else specialChoice
                    val shownDesign = (shownChoice.design ?: shared).copy(iconSize = if (!isAll && button) 100 else shared.iconSize)
                    val sourceLabel = when (shownChoice.kind) {
                        "pack" -> uiState.iconPacks.firstOrNull { it.packageName == shownChoice.source }?.label ?: shownChoice.source
                        "image" -> stringResource(R.string.icon_edit_image)
                        "system" -> stringResource(R.string.icon_pack_system)
                        "symbol" -> stringResource(R.string.icon_designer_suggestions)
                        else -> stringResource(R.string.icon_edit_follow_theme)
                    }
                    Column(Modifier.fillMaxSize()) {
                        IconDesignerPreview(uiState, app, shownChoice, shownDesign, layers, store, dynamicColors, themeColors, isAll,
                            !saving && page == displayed, { request("choose") }, ::changeDesign,
                            Modifier.padding(top = 8.dp, bottom = 16.dp).height(previewHeight))
                        DesignerControlsPane(isAll, shownChoice, layered, !saving && page == displayed && (isAll || app != null),
                            app?.label, sourceLabel, dynamicColors, themeColors,
                            if (isAll) IconDesign.defaults() else if (button) GraceButtonIcon.defaults else shared, colorPicker,
                            onSwitch = { request("choose") }, onSource = { sourcePicker = true }, onColor = { colorPicker = it },
                            onCloseColor = { colorPicker = null }, onChange = ::changeDesign,
                            showSuggestions = !isAll && button,
                            onSuggestion = { change(ItemIcon("symbol", name = it, design = design)) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (colorPicker == null) HorizontalFloatingToolbar(expanded = true,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceBright),
                expandedShadowElevation = 3.dp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = FloatingToolbarDefaults.ScreenOffset + LocalSettingsBottomInset.current)
                    .testTag("icon_designer_toolbar").selectableGroup()) {
                val indicator by animateDpAsState(88.dp * page.ordinal, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "designerToolbarIndicator")
                Box {
                    Box(Modifier.offset(x = indicator).size(88.dp, 48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
                    Row {
                        DesignerPage.entries.forEach { destination ->
                            DesignerMode(stringResource(when (destination) {
                                DesignerPage.Single -> R.string.icon_designer_single; DesignerPage.All -> R.string.icon_designer_all
                                DesignerPage.Data -> R.string.icon_designer_data
                            }), page == destination, !saving, "icon_designer_${destination.name.lowercase()}") {
                                if (page != destination) request("page:${destination.name}")
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Shared bounds make the selected color row grow into the lower pane, leaving the preview visible. */
@Composable
private fun DesignerControlsPane(all: Boolean, choice: ItemIcon, layered: Boolean, enabled: Boolean,
    appLabel: String?, sourceLabel: String, dynamicColors: Pair<Int, Int>, themeColors: Pair<Int, Int>, defaults: IconDesign, color: String?,
    onSwitch: () -> Unit, onSource: () -> Unit, onColor: (String) -> Unit, onCloseColor: () -> Unit,
    onChange: (IconDesign) -> Unit, showSuggestions: Boolean, onSuggestion: (String) -> Unit, modifier: Modifier = Modifier) {
    val design = choice.design ?: IconDesign()
    val heroSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val heroBounds = remember(heroSpec) { BoundsTransform { _, _ -> heroSpec } }
    val shape = ListItemDefaults.segmentedShapes(0, 1).shape
    val list = rememberLazyListState()
    SharedTransitionLayout(modifier.clipToBounds()) {
        AnimatedContent(color, modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "iconDesignerColorHero") { activeColor ->
            val visibility = this
            if (activeColor == null) LazyColumn(Modifier.fillMaxSize().testTag("icon_designer_controls"), state = list,
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), contentPadding = PaddingValues(bottom = 88.dp + LocalSettingsBottomInset.current)) {
                iconDesignerControls(all, enabled, appLabel, sourceLabel, design, layered, dynamicColors, themeColors, defaults,
                    onSwitch, onSource, onColor, onChange,
                    showSuggestions = showSuggestions, suggestion = when (choice.kind) {
                        "symbol" -> choice.name
                        "system", "theme" -> GraceButtonIcon.Suggestion.Default.id
                        else -> null
                    }, onSuggestion = onSuggestion,
                    colorModifier = { field -> Modifier.sharedBounds(rememberSharedContentState("color:$field"), visibility,
                        boundsTransform = heroBounds, clipInOverlayDuringTransition = OverlayClip(shape)) })
            } else {
                val background = activeColor != "foreground"
                val title = stringResource(if (background) R.string.icon_designer_tray_color else R.string.icon_designer_symbol_color)
                IconDesignerColorPicker(title, if (background) design.background else design.foreground,
                    if (background) dynamicColors.first else dynamicColors.second,
                    if (background) themeColors.first else themeColors.second,
                    if (background) defaults.background else defaults.foreground,
                    onChange = { value -> if (enabled) onChange(if (background) design.copy(background = value)
                        else design.copy(foreground = value)) }, onClose = onCloseColor,
                    modifier = Modifier.sharedBounds(rememberSharedContentState("color:$activeColor"), visibility,
                        boundsTransform = heroBounds, clipInOverlayDuringTransition = OverlayClip(shape)))
            }
        }
    }
}

@Composable
private fun DesignerMode(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    TextButton(onClick, enabled = enabled, modifier = Modifier.width(88.dp).heightIn(min = 48.dp).testTag(tag)
        .semantics { this.selected = selected; role = Role.Tab },
        colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun normalizedChoice(value: ItemIcon): ItemIcon {
    val design = (value.design ?: IconDesign()).normalized()
    return value.copy(design = design)
}

private fun sharedDesignerDefaults(uiState: LauncherUiState): IconDesign =
    IconDesign.defaults(uiState.themedIcons).let { uiState.settings.iconDesign?.design?.withThemeDefaults(it) ?: it }

private fun initialDesignerChoice(uiState: LauncherUiState, app: LauncherApp?, includeSpecial: Boolean = true): ItemIcon {
    val shared = sharedDesignerDefaults(uiState)
    val saved = if (includeSpecial) app?.let { uiState.itemIcons[it.key] } else null
    if (app?.isGraceButton == true) return GraceButtonIcon.choice(saved)
    return (saved ?: ItemIcon.Theme).copy(design = (saved?.design?.withThemeDefaults(shared) ?: shared).copy(iconSize = shared.iconSize))
}
