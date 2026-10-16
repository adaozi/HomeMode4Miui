package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.SearchSettings
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState

// 审计修缮（2026-10-16）：裁剪后端已删的死设置行 —— 联系人搜索（SearchContacts 索引
// P2 裁剪）、网络搜索与引擎选择（SearchResult.Internet / SearchLauncher.internet P2 裁剪）。
// 对应 SearchSettings.contacts/internet/engine/customEngine* 字段保留（JSON 兼容旧数据），
// 仅 UI 行移除；恢复入口见 AppSearchScreen.kt 头注 P6 评估。
@Composable
internal fun SearchSettingsScreen(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val settings = uiState.settings.search
    fun update(change: (SearchSettings) -> SearchSettings) = actions.updateSettings { it.copy(search = change(it.search)) }
    SettingsScaffold(stringResource(R.string.settings_search), "settings_search", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingsFeatureBanner(stringResource(R.string.search_enable), settings.enabled, "search_enabled") { value ->
                    update { it.copy(enabled = value) }
                }
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_suggestions), stringResource(R.string.search_suggestions_summary),
                    settings.suggestions, 0, 4, "search_suggestions", enabled = settings.enabled) { value -> update { it.copy(suggestions = value) } }
            }
            item {
                SettingsActionItem(stringResource(R.string.search_files), stringResource(R.string.settings_coming_soon),
                    1, 4, "search_files", enabled = false, onClick = {})
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_fuzzy), stringResource(R.string.search_fuzzy_summary),
                    settings.fuzzy, 2, 4, "search_fuzzy", enabled = settings.enabled) { value -> update { it.copy(fuzzy = value) } }
            }
            item {
                SettingsToggleItem(stringResource(R.string.search_hidden_apps), stringResource(R.string.search_hidden_apps_summary),
                    settings.hiddenApps, 3, 4, "search_hidden_apps", enabled = settings.enabled) { value -> update { it.copy(hiddenApps = value) } }
            }
        }
    }
}
