package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.data.GraceButtonTarget
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState

@Composable
internal fun GraceButtonSettingsScreen(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    onChooseTarget: (longClick: Boolean, shortcut: Boolean) -> Unit) {
    val settings = uiState.settings.graceButton
    SettingsScaffold(stringResource(R.string.settings_grace_button), "settings_grace_button_page", onBack) { padding ->
        SettingsList(padding) {
            item { SettingsFeatureBanner(stringResource(R.string.grace_button_enable), settings.enabled, "grace_button_enabled") { value ->
                actions.updateSettings { it.copy(graceButton = it.graceButton.copy(enabled = value)) }
            } }
            listOf(false, true).forEach { longClick ->
                val selected = if (longClick) settings.longPress else settings.tap
                item { SettingsHeading(stringResource(if (longClick) R.string.grace_button_long_press else R.string.grace_button_tap)) }
                val options = listOf(if (longClick) GraceButtonAction.Settings else GraceButtonAction.Search,
                    GraceButtonAction.App, GraceButtonAction.Shortcut, GraceButtonAction.Disabled)
                options.forEachIndexed { index, action ->
                    item {
                        SettingsActionItem(stringResource(when (action) {
                            GraceButtonAction.Search -> R.string.settings_search
                            GraceButtonAction.Settings -> R.string.grace_settings
                            GraceButtonAction.App -> R.string.grace_button_open_app
                            GraceButtonAction.Shortcut -> R.string.grace_button_open_shortcut
                            GraceButtonAction.Disabled -> R.string.grace_button_disabled
                            // 上游同步 v1.1.8：枚举扩展项（Folder/Website/系统动作等）S4 批次接入，
                            // 旧页面先归入禁用文案避免 when 非穷举编译错
                            else -> R.string.grace_button_disabled
                        }), if (action == selected.action && action in listOf(GraceButtonAction.App, GraceButtonAction.Shortcut))
                            selected.itemKey?.let { uiState.findItem(it)?.label } ?: stringResource(R.string.popup_item_unavailable) else null,
                            index, options.size, "grace_${if (longClick) "long" else "tap"}_${action.name}",
                            leading = { RadioButton(selected.action == action, null) },
                        ) {
                            if (action == GraceButtonAction.App || action == GraceButtonAction.Shortcut)
                                onChooseTarget(longClick, action == GraceButtonAction.Shortcut)
                            else {
                            // 上游同步 v1.1.8：withTarget(longClick) → withTarget(gesture)
                            val gesture = if (longClick) GraceButtonGesture.LongPress else GraceButtonGesture.Tap
                            actions.updateSettings { it.copy(graceButton = it.graceButton.withTarget(gesture, GraceButtonTarget(action))) }
                        }
                        }
                    }
                }
            }
        }
    }
}
