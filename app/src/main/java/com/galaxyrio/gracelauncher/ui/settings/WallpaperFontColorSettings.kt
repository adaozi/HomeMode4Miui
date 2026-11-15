package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.WallpaperBrightness
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState

@Composable
internal fun WallpaperBrightness.label(): String = stringResource(when (this) {
    WallpaperBrightness.Auto -> R.string.wallpaper_brightness_auto
    WallpaperBrightness.Dark -> R.string.wallpaper_brightness_dark
    WallpaperBrightness.Light -> R.string.wallpaper_brightness_light
})

@Composable
internal fun WallpaperFontColorSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val colors = uiState.settings.fontColors
    val enabled = LocalSettingsStorageState.current.canEdit
    var requestingAccess by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(stringResource(R.string.font_color_wallpaper), "wallpaper_font_color", onBack) { padding ->
        Box(Modifier.selectableGroup()) {
            SettingsList(padding) {
                item { Spacer(Modifier.height(24.dp)) }
                WallpaperBrightness.entries.forEachIndexed { index, mode ->
                    item(mode.name) {
                        val checked = mode == colors.wallpaperBrightness
                        SettingsActionItem(mode.label(),
                            if (mode == WallpaperBrightness.Auto && !colors.wallpaperColorAccess)
                                stringResource(R.string.wallpaper_color_access_unavailable) else null,
                            index, WallpaperBrightness.entries.size, "wallpaper_brightness:${mode.name}", enabled = enabled,
                            leading = { RadioButton(checked, onClick = null, enabled = enabled) },
                            modifier = Modifier.semantics { role = Role.RadioButton; selected = checked }) {
                            actions.updateSettings { it.copy(fontColors = it.fontColors.copy(wallpaperBrightness = mode)) }
                            if (mode == WallpaperBrightness.Auto && !colors.wallpaperColorAccess) requestingAccess = true
                        }
                    }
                }
            }
        }
    }
    if (requestingAccess) AlertDialog(
        onDismissRequest = { requestingAccess = false },
        title = { Text(stringResource(R.string.wallpaper_color_access_title)) },
        text = { Text(stringResource(R.string.wallpaper_color_access_summary)) },
        confirmButton = {
            TextButton(enabled = enabled, modifier = Modifier.testTag("wallpaper_color_allow"), onClick = {
                actions.updateSettings { it.copy(fontColors = it.fontColors.copy(wallpaperColorAccess = true)) }
                requestingAccess = false
            }) { Text(stringResource(R.string.wallpaper_color_access_allow)) }
        },
        dismissButton = {
            TextButton(modifier = Modifier.testTag("wallpaper_color_decline"), onClick = { requestingAccess = false }) {
                Text(stringResource(R.string.wallpaper_color_access_decline))
            }
        },
    )
}
