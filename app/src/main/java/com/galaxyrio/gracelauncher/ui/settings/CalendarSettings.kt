package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.ScheduleStatus

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
            if (uiState.scheduleStatus == ScheduleStatus.PermissionRequired) item {
                SettingsHeading(stringResource(R.string.widget_calendar_access))
                SettingsActionItem(stringResource(R.string.connect_calendar), stringResource(R.string.calendar_permission_description),
                    0, 1, "calendar_access", onClick = actions.requestCalendarAccess)
            }
        }
    }
}
