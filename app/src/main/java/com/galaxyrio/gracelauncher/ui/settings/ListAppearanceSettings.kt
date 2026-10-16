@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.content.ComponentName
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.*
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberWallpaperBlurAvailable
import com.galaxyrio.gracelauncher.ui.theme.wallpaperTint
import kotlin.math.roundToInt

@Composable
internal fun ListAppearanceSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit, onManageFonts: () -> Unit) {
    val settings = uiState.settings
    val defaults = remember { LauncherSettings() }
    var draft by remember(settings.listAppearance) { mutableStateOf(settings.listAppearance) }
    val savedSize = settings.iconDesign?.design?.iconSize ?: 100
    var iconSize by remember(savedSize) { mutableIntStateOf(savedSize) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val colorPage = dialog?.takeIf { it == "font_color" || it == "dim_color" }
    // Keep the source row in place while its color picker occupies the controls pane.
    val controlsState = rememberLazyListState()
    val enabled = LocalSettingsStorageState.current.canEdit
    val blurAvailable = rememberWallpaperBlurAvailable()
    val wallpaper = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, iconSize)
    val primary = MaterialTheme.colorScheme.primary
    val automaticDimTint = remember(primary, wallpaper.darkText) { wallpaper.wallpaperTint(primary) }
    val heroSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val heroBounds = remember(heroSpec) { BoundsTransform { _, _ -> heroSpec } }
    val heroShape = ListItemDefaults.segmentedShapes(0, 1).shape
    fun change(transform: (ListAppearance) -> ListAppearance) {
        draft = transform(draft).normalized()
        actions.updateSettings { it.copy(listAppearance = transform(it.listAppearance).normalized()) }
    }
    fun saveSize() {
        val value = iconSize
        actions.updateSettings { current ->
            val defaults = IconDesign.defaults(uiState.themedIcons)
            val icon = current.iconDesign ?: ItemIcon.Theme
            // Change only this property: per-app designs and every other All option are retained.
            current.copy(iconDesign = icon.copy(design = (icon.design?.withThemeDefaults(defaults) ?: defaults).copy(iconSize = value)))
        }
    }
    fun closeColor() {
        if (enabled) when (colorPage) {
            "font_color" -> {
                val color = draft.fontColor
                change { it.copy(fontColor = color) }
            }
            "dim_color" -> {
                val color = draft.wallpaperDimColor
                change { it.copy(wallpaperDimColor = color) }
            }
        }
        dialog = null
    }
    BackHandler(colorPage != null) { closeColor() }
    when (dialog) {
        "font" -> FontPickerDialog(settings.fontLibrary, draft.fontId,
            onSelect = { id -> change { it.copy(fontId = id) }; dialog = null },
            onManage = { dialog = null; onManageFonts() }, onDismiss = { dialog = null }, tag = "list_font_picker")
        "names" -> SettingsSelectionDialog(stringResource(R.string.list_show_names), AppNameVisibility.entries, draft.names,
            "list_names", { it.name }, { it.label() }, { value -> change { it.copy(names = value) }; dialog = null }, { dialog = null })
    }
    SettingsScaffold(stringResource(R.string.settings_favorites_app_list), "list_appearance",
        { if (colorPage != null) closeColor() else onBack() }, fixedCollapsed = true) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val previewHeight by animateDpAsState(((40.dp * iconSize / 100f + draft.appSpacing.dp) * 3 + 32.dp)
                .coerceIn(176.dp, ((maxHeight - LocalSettingsBottomInset.current) * .45f).coerceAtLeast(176.dp)), label = "listPreviewHeight")
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                ListAppearancePreview(uiState, draft, iconSize, Modifier.padding(top = 8.dp, bottom = 16.dp).height(previewHeight))
                SharedTransitionLayout(Modifier.weight(1f).clipToBounds()) {
                    AnimatedContent(colorPage, modifier = Modifier.fillMaxSize(),
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "listAppearanceColorHero") { activeColor ->
                        val visibility = this
                        val colorModifier: @Composable (String) -> Modifier = { field ->
                            Modifier.sharedBounds(rememberSharedContentState("list_color:$field"), visibility,
                                boundsTransform = heroBounds, clipInOverlayDuringTransition = OverlayClip(heroShape))
                        }
                        if (activeColor != null) {
                            val dim = activeColor == "dim_color"
                            val fallback = if (dim) automaticDimTint else wallpaper.text
                            IconDesignerColorPicker(stringResource(if (dim) R.string.settings_wallpaper_dim_color else R.string.list_font_color),
                                if (dim) draft.wallpaperDimColor else draft.fontColor, primary.toArgb(), null,
                                onChange = { if (enabled && activeColor == colorPage) {
                                    draft = if (dim) draft.copy(wallpaperDimColor = it) else draft.copy(fontColor = it)
                                } }, onClose = { if (activeColor == colorPage) closeColor() },
                                modifier = Modifier.padding(bottom = 16.dp).then(colorModifier(activeColor)), fallback = fallback.toArgb())
                        } else LazyColumn(Modifier.fillMaxSize().testTag("list_appearance_controls"), state = controlsState,
                            contentPadding = PaddingValues(bottom = 24.dp + LocalSettingsBottomInset.current), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                            item("icon_size") {
                                DesignerSegment(0, 9, "list_icon_size") {
                                    Text(stringResource(R.string.icon_designer_icon_size_title))
                                    DesignerSlider(stringResource(R.string.icon_designer_icon_size), iconSize.toFloat(), 100f, 80f..150f,
                                        enabled, "list_icon_size_slider", onFinished = { saveSize() }) { iconSize = it.roundToInt() }
                                }
                            }
                            item("app_spacing") {
                                ListSpacingSlider(stringResource(R.string.list_app_spacing), draft.appSpacing, 16, 0..64, 1, "list_app_spacing",
                                    { draft = draft.copy(appSpacing = it) }) { value -> change { it.copy(appSpacing = value) } }
                            }
                            item("side_padding") {
                                ListSpacingSlider(stringResource(R.string.list_side_padding), draft.sidePadding, 44, 8..80, 2, "list_side_padding",
                                    { draft = draft.copy(sidePadding = it) }) { value -> change { it.copy(sidePadding = value) } }
                            }
                            item("icon_gap") {
                                ListSpacingSlider(stringResource(R.string.list_icon_name_gap), draft.iconNameGap, 20, 0..64, 3, "list_icon_name_gap",
                                    { draft = draft.copy(iconNameGap = it) }) { value -> change { it.copy(iconNameGap = value) } }
                            }
                            item("font") { SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(draft.fontId),
                                4, 9, "list_font", onClick = { dialog = "font" }) }
                            item("font_size") {
                                ListSpacingSlider(stringResource(R.string.clock_size), draft.fontSize, 16, 10..32, 5, "list_font_size",
                                    { draft = draft.copy(fontSize = it) }, suffix = "sp") { value -> change { it.copy(fontSize = value) } }
                            }
                            item("font_color") {
                                SettingsActionItem(stringResource(R.string.list_font_color),
                                    if (draft.fontColor == null) stringResource(R.string.list_color_default) else null,
                                    6, 9, "list_font_color", modifier = colorModifier("font_color"), trailing = {
                                        Surface(Modifier.size(24.dp), shape = MaterialTheme.shapes.small,
                                            color = draft.fontColor?.resolve(MaterialTheme.colorScheme.primary.toArgb())?.let(::Color) ?: wallpaper.text) {}
                                    }, onClick = { dialog = "font_color" })
                            }
                            item("names") { SettingsActionItem(stringResource(R.string.list_show_names), draft.names.label(), 7, 9,
                                "list_show_names", onClick = { dialog = "names" }) }
                            item("left_alphabet") {
                                SettingsToggleItem(stringResource(R.string.list_left_alphabet), stringResource(R.string.list_left_alphabet_summary),
                                    draft.leftAlphabet, 8, 9, "list_left_alphabet") { value -> change { it.copy(leftAlphabet = value) } }
                            }
                            item("misc") { SettingsHeading(stringResource(R.string.settings_misc)) }
                            item("alphabet") {
                                SettingsToggleItem(stringResource(R.string.settings_hide_alphabet), stringResource(R.string.settings_hide_alphabet_summary),
                                    settings.hideAlphabet, 0, 3, "settings_hide_alphabet") { value -> actions.updateSettings { it.copy(hideAlphabet = value) } }
                            }
                            item("dim") {
                                WallpaperEffectItem(stringResource(R.string.settings_dim_wallpaper), stringResource(R.string.settings_dim_wallpaper_summary),
                                    settings.dimWallpaper, settings.wallpaperDimAmount, 0..100,
                                    stringResource(R.string.settings_wallpaper_opacity), 1, "settings_dim_wallpaper",
                                    defaultAmount = defaults.wallpaperDimAmount,
                                    onToggle = { value -> actions.updateSettings { it.copy(dimWallpaper = value) } },
                                    onAmount = { value -> actions.updateSettings { it.copy(wallpaperDimAmount = value) } }) {
                                    SettingsActionItem(stringResource(R.string.settings_wallpaper_dim_color),
                                        if (draft.wallpaperDimColor == null) stringResource(R.string.settings_wallpaper_dim_color_auto) else null,
                                        0, 1, "settings_dim_wallpaper_color", modifier = colorModifier("dim_color"), trailing = {
                                            Surface(Modifier.size(24.dp), shape = MaterialTheme.shapes.small,
                                                color = draft.wallpaperDimColor?.resolve(primary.toArgb())?.let(::Color) ?: automaticDimTint) {}
                                        }, onClick = { dialog = "dim_color" })
                                }
                            }
                            item("blur") {
                                WallpaperEffectItem(stringResource(R.string.settings_blur_wallpaper),
                                    stringResource(if (blurAvailable) R.string.settings_blur_wallpaper_summary else R.string.settings_blur_unavailable),
                                    settings.blurWallpaper, settings.wallpaperBlurRadius, 0..48,
                                    stringResource(R.string.settings_wallpaper_blur_amount), 2, "settings_blur_wallpaper", amountEnabled = blurAvailable,
                                    defaultAmount = defaults.wallpaperBlurRadius, suffix = "dp",
                                    onToggle = { value -> actions.updateSettings { it.copy(blurWallpaper = value) } },
                                    onAmount = { value -> actions.updateSettings { it.copy(wallpaperBlurRadius = value) } })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListSpacingSlider(label: String, value: Int, default: Int, range: IntRange, index: Int, tag: String,
    onPreview: (Int) -> Unit, suffix: String = "dp", onCommit: (Int) -> Unit) {
    DesignerSegment(index, 9, tag) {
        DesignerSlider(label, value.toFloat(), default.toFloat(), range.first.toFloat()..range.last.toFloat(),
            LocalSettingsStorageState.current.canEdit, "${tag}_slider", suffix,
            onFinished = { onCommit(it.roundToInt()) }, onChange = { onPreview(it.roundToInt()) })
    }
}

@Composable
private fun AppNameVisibility.label(): String = stringResource(when (this) {
    AppNameVisibility.Both -> R.string.list_names_both
    AppNameVisibility.Favorites -> R.string.list_names_favorites
    AppNameVisibility.AppList -> R.string.list_names_app_list
})

@Composable
private fun ListAppearancePreview(uiState: LauncherUiState, list: ListAppearance, iconSize: Int, modifier: Modifier) {
    val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, iconSize)
    val primary = MaterialTheme.colorScheme.primary
    val tint = remember(primary, appearance.darkText, list.wallpaperDimColor) { appearance.wallpaperTint(primary, list.wallpaperDimColor) }
    val dimAlpha = if (uiState.settings.dimWallpaper) uiState.settings.wallpaperDimAmount.coerceIn(0, 100) / 100f else 0f
    val shape = ListItemDefaults.segmentedShapes(0, 1).shape
    val example = stringResource(R.string.icon_designer_example)
    val favorites = uiState.favoriteItems.take(3)
    val rows = remember(favorites, example) { List(3) { index -> favorites.getOrNull(index)
        ?: LauncherApp(ComponentName("list.preview", "Sample$index"), example, null) } }
    BoxWithConstraints(modifier.fillMaxWidth().testTag("list_appearance_preview").drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, Color.Transparent, blendMode = BlendMode.Clear)
        drawOutline(outline, tint.copy(alpha = dimAlpha))
        drawContent()
    }.clip(shape), contentAlignment = Alignment.Center) {
        // Large accessibility text or row spacing should still show all three examples.
        val lineHeight = (list.fontSize * 1.5f * LocalDensity.current.fontScale).dp
        val rowHeight = (maxOf(40.dp * iconSize / 100f, lineHeight) + list.appSpacing.dp).coerceAtLeast(48.dp)
        val scale = (maxHeight / (rowHeight * 3)).coerceAtMost(1f)
        CompositionLocalProvider(LocalLauncherAppearance provides appearance, LocalListAppearance provides list,
            LocalLauncherInputEnabled provides false) {
            Column(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true).graphicsLayer { scaleX = scale; scaleY = scale }
                .padding(start = list.startPadding, end = list.endPadding), verticalArrangement = Arrangement.Center) {
                rows.forEach { app -> LauncherAppRow(app, {}, {}, showLabel = list.showNames(favorites = true)) }
            }
        }
    }
}
