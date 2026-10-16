package com.galaxyrio.gracelauncher.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MediaPlayerSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit, onAppearance: () -> Unit = {}) {
    val context = LocalContext.current
    val settings = uiState.settings
    var requestAccess by rememberSaveable { mutableStateOf(false) }
    val musicPackages by produceState<Set<String>>(emptySet(), uiState.allApps) {
        value = withContext(Dispatchers.IO) {
            val intents = listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
                Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse("content://media/external/audio/media/1"), "audio/*"))
            intents.flatMap { intent ->
                @Suppress("DEPRECATION")
                runCatching { context.packageManager.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
            }.mapNotNull { it.activityInfo?.packageName }.toSet()
        }
    }
    AppSelectionSettings(uiState, actions, onBack,
        title = stringResource(R.string.settings_media_player), tag = "media_player",
        appBarActions = { SettingsAppearanceAction(onAppearance) },
        selectedKey = settings.mediaAppKey, showDefault = true, defaultLabel = stringResource(R.string.settings_none),
        apps = uiState.allApps.filter { it.packageName in musicPackages || it.key == settings.mediaAppKey },
        onSelect = { key -> actions.updateSettings { it.copy(mediaAppKey = key) } },
        header = {
            item { SettingsFeatureBanner(stringResource(R.string.media_enable), settings.mediaPlayer, "media_enabled") { enabled ->
                actions.updateSettings { it.copy(mediaPlayer = enabled) }
                if (enabled && !uiState.media.hasAccess) requestAccess = true
            } }
            item { SettingsToggleItem(stringResource(R.string.media_always_visible), stringResource(R.string.media_always_visible_summary),
                settings.mediaAlwaysVisible, 0, 1, "media_always_visible") { value ->
                actions.updateSettings { it.copy(mediaAlwaysVisible = value) }
            } }
            if (!uiState.media.hasAccess) item {
                Spacer(Modifier.height(12.dp))
                SettingsActionItem(stringResource(R.string.media_allow_controls), stringResource(R.string.media_access_required),
                    0, 1, "media_access") { requestAccess = true }
            }
            item { SettingsHeading(stringResource(R.string.media_fallback_app)) }
        },
    )
    if (requestAccess) AlertDialog(onDismissRequest = { requestAccess = false },
        title = { Text(stringResource(R.string.media_allow_controls)) },
        text = { Text(stringResource(R.string.media_access_explanation)) },
        confirmButton = { TextButton(onClick = { requestAccess = false; actions.requestMediaAccess() }) { Text(stringResource(R.string.media_open_settings)) } },
        dismissButton = { TextButton(onClick = { requestAccess = false }) { Text(stringResource(R.string.settings_cancel)) } })
}
