@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ThemeMode
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.data.icons.IconPackStatus
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.toDynamicScheme
import com.materialkolor.ktx.toneColor
import kotlin.math.roundToInt

@Composable
internal fun ThemeSettings(
    uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    onClockStyle: () -> Unit, onIconPacks: () -> Unit, onFonts: () -> Unit, onListAppearance: () -> Unit, onAlphabet: () -> Unit,
) {
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val settings = uiState.settings
    when (dialog) {
        "theme" -> SettingsSelectionDialog(
            title = stringResource(R.string.settings_theme_mode),
            items = ThemeMode.entries, selectedItem = settings.darkMode,
            tag = "theme_mode", itemKey = { it.name }, itemLabel = { it.label() },
            onSelect = { mode -> actions.updateSettings { current -> current.copy(darkMode = mode) }; dialog = null },
            onDismiss = { dialog = null },
        )
        "text" -> SettingsSelectionDialog(
            title = stringResource(R.string.settings_wallpaper_text),
            items = WallpaperTextMode.entries, selectedItem = uiState.textMode,
            tag = "wallpaper_text", itemKey = { it.name }, itemLabel = { it.label() },
            onSelect = { actions.textMode(it); dialog = null }, onDismiss = { dialog = null },
        )
    }
    SettingsScaffold(stringResource(R.string.settings_themes), "settings_themes", onBack) { padding ->
        SettingsList(padding) {
            item { SettingsHeading(stringResource(R.string.settings_theme_section)) }
            item {
                AccentColorItem(settings.useDynamicColors, settings.themeColor) { color ->
                    actions.updateSettings { current -> current.copy(useDynamicColors = false, themeColor = color.toArgb()) }
                }
            }
            item {
                SettingsToggleItem(
                    stringResource(R.string.settings_dynamic_colors), stringResource(R.string.settings_dynamic_colors_summary),
                    settings.useDynamicColors, 1, 5, "settings_dynamic_colors",
                ) { value -> actions.updateSettings { current -> current.copy(useDynamicColors = value) } }
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_theme_mode), settings.darkMode.label(), 2, 5, "settings_theme_mode") { dialog = "theme" }
            }
            item {
                SettingsToggleItem(
                    stringResource(R.string.settings_amoled_mode), stringResource(R.string.settings_amoled_mode_summary),
                    settings.amoledMode, 3, 5, "settings_amoled_mode",
                ) { value -> actions.updateSettings { it.copy(amoledMode = value) } }
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_wallpaper_text), uiState.textMode.label(), 4, 5, "settings_wallpaper_text") { dialog = "text" }
            }
            item { SettingsHeading(stringResource(R.string.settings_personalization)) }
            item {
                SettingsActionItem(stringResource(R.string.settings_clock_style), uiState.settings.clockStyle.layout.label(), 0, 5, "settings_clock_style", onClick = onClockStyle)
            }
            item {
                val packs = settings.enabledIconPackPackages
                val packNames = packs.map { pkg -> uiState.iconPacks.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                val summary = when {
                    packs.isEmpty() -> stringResource(R.string.icon_pack_system)
                    uiState.iconPackStatus == IconPackStatus.Unavailable -> stringResource(R.string.icon_pack_unavailable)
                    else -> packNames.joinToString(" → ")
                }
                SettingsActionItem(stringResource(R.string.settings_icons), summary, 1, 5, "settings_icon_pack") {
                    onIconPacks()
                    actions.refreshIconPacks()
                }
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_favorites_app_list), null, 2, 5, "settings_list_appearance", onClick = onListAppearance)
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_alphabet), null, 3, 5, "settings_alphabet", onClick = onAlphabet)
            }
            item {
                val fonts = settings.fontLibrary.selected.map { appFontLabel(it) }
                val systemFont = appFontLabel("system")
                SettingsActionItem(stringResource(R.string.settings_font), fonts.joinToString(" → ").ifEmpty { systemFont },
                    4, 5, "settings_font", onClick = onFonts)
            }
            item { SettingsHeading(stringResource(R.string.settings_misc)) }
            item {
                SettingsToggleItem(
                    stringResource(R.string.settings_hide_status_bar), stringResource(R.string.settings_hide_status_bar_summary),
                    settings.hideStatusBar, 0, 1, "settings_hide_status_bar",
                ) { value -> actions.updateSettings { it.copy(hideStatusBar = value) } }
            }
        }
    }
}

@Composable
private fun AccentColorItem(isDynamic: Boolean, selectedColor: Int, onSelect: (Color) -> Unit) {
    val pages = remember { AccentColors.chunked(5) }
    val initialPage = (AccentColors.indexOfFirst { it.toArgb() == selectedColor }.coerceAtLeast(0) / 5)
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { pages.size })
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(0, 5),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.testTag("settings_accent_color"),
        content = {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_accent_color))
                Text(
                    stringResource(R.string.settings_dynamic_or_custom),
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalPager(pager, Modifier.fillMaxWidth().padding(top = 18.dp)) { page ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        pages[page].forEachIndexed { index, color ->
                            PaletteOption(color, !isDynamic && selectedColor == color.toArgb(), page * 5 + index) { onSelect(color) }
                        }
                    }
                }
                Row(
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(pages.size) { page ->
                        Box(Modifier.size(8.dp).clip(CircleShape).background(
                            if (pager.settledPage == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ))
                    }
                }
            }
        },
    )
}

/** Keep the toggle and its expanding controls in one unchanged segmented outline. */
@Composable
internal fun WallpaperEffectItem(
    title: String, summary: String, checked: Boolean, amount: Int, range: IntRange,
    sliderLabel: String, index: Int, tag: String, defaultAmount: Int,
    count: Int = 3, amountEnabled: Boolean = true, suffix: String = "%",
    onToggle: (Boolean) -> Unit, onAmount: (Int) -> Unit,
    extraContent: @Composable ColumnScope.() -> Unit = {},
) {
    val enabled = LocalSettingsStorageState.current.canEdit
    var draft by remember(amount) { mutableFloatStateOf(amount.coerceIn(range).toFloat()) }
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        content = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().testTag(tag)
                        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title)
                        Text(summary, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = checked, onCheckedChange = null, enabled = enabled,
                        modifier = Modifier.testTag("${tag}_switch"))
                }
                AnimatedVisibility(visible = checked) {
                    Column {
                        DesignerSlider(
                            label = sliderLabel, value = draft, default = defaultAmount.coerceIn(range).toFloat(),
                            // Do not enqueue a Room transaction for every drag frame.
                            onFinished = { onAmount(it.roundToInt().coerceIn(range)) },
                            range = range.first.toFloat()..range.last.toFloat(),
                            enabled = enabled && amountEnabled, tag = "${tag}_amount", suffix = suffix,
                            onChange = { draft = it },
                        )
                        extraContent()
                    }
                }
            }
        },
    )
}

@Composable
private fun PaletteOption(seed: Color, selected: Boolean, index: Int, onClick: () -> Unit) {
    val palette = remember(seed) {
        seed.toDynamicScheme(isDark = false, style = PaletteStyle.TonalSpot, specVersion = ColorSpec.SpecVersion.SPEC_2025)
    }
    val description = stringResource(R.string.settings_color_option, "#%06X".format(seed.toArgb() and 0xFFFFFF))
    Box(
        Modifier.size(48.dp).testTag("settings_palette_$index")
            .semantics { contentDescription = description; this.selected = selected }
            .clip(CircleShape).clickable(enabled = LocalSettingsStorageState.current.canEdit, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(palette.primaryPalette.toneColor(60)))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.weight(1f).fillMaxHeight().background(palette.secondaryPalette.toneColor(80)))
                Box(Modifier.weight(1f).fillMaxHeight().background(palette.tertiaryPalette.toneColor(40)))
            }
        }
        if (selected) Surface(
            modifier = Modifier.size(24.dp), shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) { LauncherIcon(LauncherSymbol.Check, Modifier.padding(4.dp)) }
    }
}

@Composable
internal fun <T> SettingsSelectionDialog(
    title: String,
    items: List<T>,
    selectedItem: T,
    tag: String,
    itemKey: (T) -> String,
    itemLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                items.forEach { item ->
                    val enabled = LocalSettingsStorageState.current.canEdit
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("$tag:${itemKey(item)}")
                            .selectable(
                                selected = item == selectedItem, enabled = enabled,
                                role = Role.RadioButton, onClick = { onSelect(item) },
                            )
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = item == selectedItem, onClick = null, enabled = enabled)
                        Text(itemLabel(item), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun ThemeMode.label(): String = stringResource(when (this) {
    ThemeMode.System -> R.string.settings_follow_system
    ThemeMode.Light -> R.string.settings_light_mode
    ThemeMode.Dark -> R.string.settings_dark_mode
})

@Composable
private fun WallpaperTextMode.label(): String = stringResource(when (this) {
    WallpaperTextMode.Auto -> R.string.settings_text_auto
    WallpaperTextMode.Light -> R.string.settings_text_light
    WallpaperTextMode.Dark -> R.string.settings_text_dark
})

internal val AccentColors = listOf(
    Color(0xFF6750A4), Color(0xFFB3261E), Color(0xFFE27C33), Color(0xFF7D5260), Color(0xFF3F51B5),
    Color(0xFF009688), Color(0xFF4CAF50), Color(0xFFF9A825), Color(0xFF0288D1), Color(0xFFC2185B),
)
