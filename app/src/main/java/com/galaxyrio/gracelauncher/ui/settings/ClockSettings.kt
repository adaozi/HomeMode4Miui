package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AppSelectionList
import com.galaxyrio.gracelauncher.ui.components.AppSelectionRow

@Composable
internal fun clockAppSummary(uiState: LauncherUiState): String =
    if (uiState.settings.clockAppKey == null) stringResource(R.string.clock_default_app)
    else uiState.allApps.firstOrNull { it.key == uiState.settings.clockAppKey }?.label
        ?: stringResource(R.string.clock_selected_unavailable)

/** Choosing a target persists a preference; it never launches the selected app. */
@Composable
internal fun ClockSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    AppSelectionSettings(
        uiState, actions, onBack, title = stringResource(R.string.settings_clock), tag = "clock",
        selectedKey = uiState.settings.clockAppKey, showDefault = true,
        description = stringResource(R.string.clock_choose_app_description),
        onSelect = { key -> actions.updateSettings { it.copy(clockAppKey = key) } },
        header = {
            item { SettingsFeatureBanner(stringResource(R.string.clock_enable), uiState.settings.clockEnabled, "clock_enabled") { enabled ->
                actions.updateSettings { it.copy(clockEnabled = enabled) }
            } }
        },
    )
}

/** Shared searchable app page for clock targets and the Icon designer preview. */
@Composable
internal fun AppSelectionSettings(
    uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    title: String, tag: String, selectedKey: String?, onSelect: (String?) -> Unit,
    showDefault: Boolean = false, description: String? = null,
    defaultLabel: String? = null,
    apps: List<com.galaxyrio.gracelauncher.data.LauncherApp> = uiState.allApps,
    excludeOwnApp: Boolean = true,
    header: LazyListScope.() -> Unit = {},
) {
    val queryState = rememberTextFieldState()
    val ownPackage = LocalContext.current.packageName
    // A profile-qualified activity key keeps private and personal copies distinct.
    val available = remember(apps, ownPackage, excludeOwnApp) { apps.filter { (!excludeOwnApp || it.packageName != ownPackage) && it.shortcut == null } }
    val enabled = LocalSettingsStorageState.current.canEdit
    val ready = !uiState.isLoadingApps && !uiState.appLoadFailed
    SettingsScaffold(title, "settings_${tag}_page", onBack) { padding ->
        AppSelectionList(
            apps = if (ready) available else emptyList(), selectedKeys = setOfNotNull(selectedKey),
            query = queryState, onSelect = { onSelect(it.key) },
            modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),
            searchTag = "${tag}_app_query", itemTagPrefix = "${tag}_app", singleChoice = true, enabled = enabled && ready,
            emptyContent = {
                when {
                    uiState.appLoadFailed -> {
                        Text(stringResource(R.string.clock_apps_load_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = actions.refreshApps) { Text(stringResource(R.string.retry)) }
                    }
                    else -> Text(stringResource(if (uiState.isLoadingApps) R.string.clock_apps_loading else R.string.search_no_results),
                        Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        ) {
            header()
            if (description != null) item {
                Text(description,
                    Modifier.padding(horizontal = 8.dp, vertical = 20.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showDefault) item {
                AppSelectionRow(
                    label = defaultLabel ?: stringResource(R.string.clock_default_app),
                    selected = selectedKey == null,
                    onClick = { onSelect(null) },
                    enabled = enabled, singleChoice = true,
                    modifier = Modifier.testTag("${tag}_default_app"),
                )
            }
            if (selectedKey != null && !uiState.isLoadingApps && !uiState.appLoadFailed && available.none { it.key == selectedKey }) item {
                Text(stringResource(R.string.clock_selected_unavailable), Modifier.padding(16.dp).testTag("${tag}_missing_app"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
