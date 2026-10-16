package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState

@Composable
internal fun CalendarSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    SettingsScaffold(stringResource(R.string.widget_calendar), "settings_calendar", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingsFeatureBanner(stringResource(R.string.calendar_enable), uiState.settings.calendarAgenda, "calendar_agenda") { enabled ->
                    actions.updateSettings { it.copy(calendarAgenda = enabled) }
                }
            }
            item {
                SettingsToggleItem(stringResource(R.string.calendar_above_clock), null, uiState.settings.calendarAboveClock,
                    0, 2, "calendar_above_clock") { value -> actions.updateSettings { it.copy(calendarAboveClock = value) } }
            }
            item {
                SettingsToggleItem(stringResource(R.string.settings_show_battery), null, uiState.settings.showBatteryPercentage,
                    1, 2, "show_battery") { value -> actions.updateSettings { it.copy(showBatteryPercentage = value) } }
            }
            // 审计修缮（2026-10-16）：移除「连接日历」死按钮 —— scheduleStatus 在模块进程
            // 永不更新（恒 PermissionRequired → 行无条件常驻），requestCalendarAccess 为
            // no-op；日历权限实际在宿主授予（B6.2），此入口对用户只有误导。
        }
    }
}
