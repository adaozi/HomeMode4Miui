package com.galaxyrio.gracelauncher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.overlays.TextEntryDialog
import com.galaxyrio.gracelauncher.ui.theme.LocalFontColors
import com.galaxyrio.gracelauncher.ui.theme.resolvedFontColor
import java.util.UUID

@Composable
internal fun fontColorLabel(entry: FontColorEntry): String = when (entry.id) {
    FontColors.DefaultId -> stringResource(R.string.font_color_default)
    FontColors.ThemeId -> stringResource(R.string.font_color_theme)
    FontColors.WallpaperId -> stringResource(R.string.font_color_wallpaper)
    FontColors.PrimaryId -> if (entry.name == "Primary") stringResource(R.string.material_color_primary) else entry.name
    FontColors.SecondaryId -> if (entry.name == "Secondary") stringResource(R.string.material_color_secondary) else entry.name
    FontColors.TertiaryId -> if (entry.name == "Tertiary") stringResource(R.string.material_color_tertiary) else entry.name
    else -> entry.name
}

@Composable
internal fun fontColorSummary(color: IconColor?, onWallpaper: Boolean = false): String? {
    if (color == null) return stringResource(if (onWallpaper) R.string.font_color_follow_wallpaper else R.string.list_color_default)
    val entry = LocalFontColors.current.entries.firstOrNull { it.id == color.fontColorId }
    return entry?.let { fontColorLabel(it) } ?: color.materialRole?.localizedLabel()
}

@Composable
internal fun FontColorSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val palette = uiState.settings.fontColors
    val scheme = MaterialTheme.colorScheme
    val enabled = LocalSettingsStorageState.current.canEdit
    var creating by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<IconColor?>(null) }
    val entry = palette.entries.firstOrNull { it.id == editingId }
    // Restore the saved color after process recreation; live slider edits remain local.
    LaunchedEffect(editingId, entry?.id) { if (draft == null) draft = entry?.color }
    fun closeColor() {
        val id = editingId
        val color = draft
        if (enabled && id != null && id != FontColors.WallpaperId && color != null) actions.updateSettings { settings ->
            settings.copy(fontColors = settings.fontColors.copy(entries = settings.fontColors.entries.map {
                if (it.id == id) it.copy(color = color) else it
            }).normalized())
        }
        editingId = null
        draft = null
    }
    BackHandler(editingId != null) { closeColor() }
    if (editingId == FontColors.WallpaperId) {
        WallpaperFontColorSettings(uiState, actions, ::closeColor)
        return
    }
    SettingsScaffold(stringResource(R.string.list_font_color), "font_colors",
        { if (editingId != null) closeColor() else onBack() }) { padding ->
        if (entry != null) {
            val default = FontColors.defaults().firstOrNull { it.id == entry.id }?.color
                ?: IconColor(0, materialRole = MaterialColorRole.OnSurface)
            IconDesignerColorPicker(fontColorLabel(entry), draft ?: entry.color, scheme.primary.toArgb(), default,
                onChange = { if (enabled) draft = it ?: default }, onClose = ::closeColor,
                modifier = Modifier.padding(padding).consumeWindowInsets(padding).padding(16.dp))
        } else SettingsList(padding) {
            item { Spacer(Modifier.height(24.dp)) }
            itemsIndexed(palette.entries, key = { _, color -> color.id }) { index, color ->
                SettingsActionItem(fontColorLabel(color),
                    if (color.id == FontColors.WallpaperId) palette.wallpaperBrightness.label() else color.color.materialRole?.localizedLabel(),
                    index, palette.entries.size, "font_color:${color.id}", enabled = enabled,
                    trailing = {
                        Surface(Modifier.size(28.dp), shape = CircleShape,
                            color = resolvedFontColor(IconColor(0, fontColorId = color.id))) {}
                    }) { draft = color.color; editingId = color.id }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                SettingsActionItem(stringResource(R.string.font_color_add), null, 0, 1, "font_color_add",
                    enabled = enabled, leading = { LauncherIcon(LauncherSymbol.Plus) }) { creating = true }
            }
        }
    }
    if (creating) TextEntryDialog(stringResource(R.string.font_color_add), "", { creating = false }, { name ->
        if (enabled) {
            val color = FontColorEntry(UUID.randomUUID().toString(), name, IconColor(0, materialRole = MaterialColorRole.OnSurface))
            actions.updateSettings { it.copy(fontColors = it.fontColors.copy(entries = it.fontColors.entries + color).normalized()) }
            draft = color.color
            editingId = color.id
        }
        creating = false
    })
}
