package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.weather.WeatherStatus
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.weather.WeatherAttribution

@Composable
internal fun WeatherSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val settings = uiState.settings
    val weather = uiState.weather
    val status = if (settings.weatherEnabled) weather.status else WeatherStatus.Disabled
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val locationName = weather.locations.firstOrNull { it.id == settings.weatherLocationId }?.name
        ?: stringResource(R.string.weather_settings_first_location)

    when (dialog) {
        "location" -> WeatherChoiceDialog(
            title = stringResource(R.string.weather_settings_location),
            choices = listOf<String?>(null) + weather.locations.map { it.id },
            selected = settings.weatherLocationId.takeIf { id -> weather.locations.any { it.id == id } },
            tag = "weather_location",
            label = { id -> weather.locations.firstOrNull { it.id == id }?.name ?: stringResource(R.string.weather_settings_first_location) },
            key = { it ?: "automatic" },
            onSelect = { id ->
                actions.updateSettings { it.copy(weatherLocationId = id) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        "days" -> WeatherChoiceDialog(
            title = stringResource(R.string.weather_settings_forecast_days),
            choices = listOf(3, 5, 7, 10, 14),
            selected = settings.weatherForecastDays,
            tag = "weather_days",
            label = { pluralStringResource(R.plurals.weather_settings_days_value, it, it) },
            key = { it.toString() },
            onSelect = { days ->
                actions.updateSettings { it.copy(weatherForecastDays = days) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }

    SettingsScaffold(stringResource(R.string.settings_weather), "settings_weather_page", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingsFeatureBanner(
                    stringResource(R.string.weather_settings_enable), settings.weatherEnabled, "weather_enabled",
                ) { enabled ->
                    actions.updateSettings { it.copy(weatherEnabled = enabled) }
                    if (enabled) actions.requestWeatherAccess()
                }
            }
            item { SettingsHeading(stringResource(R.string.weather_settings_connection)) }
            if (status != WeatherStatus.Ready && status != WeatherStatus.Disabled) item {
                Text(
                    stringResource(status.messageResource()),
                    Modifier.padding(horizontal = 4.dp, vertical = 12.dp).testTag("weather_connection_status"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status == WeatherStatus.Ready) item {
                Text(
                    stringResource(R.string.weather_settings_update_notifier),
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp).testTag("weather_update_notifier_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (settings.weatherEnabled) {
                when (status) {
                    // 小米天气为系统预装，NotInstalled 实际不可达；无安装入口（固化行为）
                    WeatherStatus.NotInstalled -> Unit
                    WeatherStatus.PermissionRequired -> item {
                        SettingsActionItem(
                            stringResource(R.string.weather_settings_allow), null, 0, 1, "weather_allow_access",
                            onClick = actions.requestWeatherAccess,
                        )
                    }
                    WeatherStatus.Disabled, WeatherStatus.Loading -> Unit
                    else -> {
                        item {
                            SettingsActionItem(
                                stringResource(R.string.weather_settings_open), null, 0, 2, "weather_open_app",
                                onClick = actions.openWeatherApp,
                            )
                        }
                        item {
                            SettingsActionItem(
                                stringResource(R.string.weather_settings_refresh), null, 1, 2, "weather_refresh",
                                onClick = actions.refreshWeather,
                            )
                        }
                    }
                }
            }
            item { SettingsHeading(stringResource(R.string.weather_settings_display)) }
            if (weather.locations.isNotEmpty()) item {
                SettingsActionItem(
                    stringResource(R.string.weather_settings_location), locationName,
                    0, 2, "weather_location",
                ) { dialog = "location" }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.weather_settings_forecast_days),
                    pluralStringResource(R.plurals.weather_settings_days_value, settings.weatherForecastDays, settings.weatherForecastDays),
                    if (weather.locations.isEmpty()) 0 else 1, if (weather.locations.isEmpty()) 1 else 2,
                    "weather_forecast_days",
                ) { dialog = "days" }
            }
            if (settings.weatherEnabled && weather.snapshot != null) {
                item { SettingsHeading(stringResource(R.string.weather_settings_attribution)) }
                item { WeatherAttribution(weather.snapshot, Modifier.padding(horizontal = 4.dp, vertical = 12.dp)) }
            }
        }
    }
}

private fun WeatherStatus.messageResource(): Int = when (this) {
    WeatherStatus.Disabled -> R.string.weather_settings_disabled
    WeatherStatus.Loading -> R.string.weather_settings_loading
    WeatherStatus.NotInstalled -> R.string.weather_settings_not_installed
    WeatherStatus.PermissionRequired -> R.string.weather_settings_permission
    WeatherStatus.UnsupportedVersion -> R.string.weather_settings_incompatible
    WeatherStatus.NoLocations -> R.string.weather_settings_no_locations
    WeatherStatus.NoWeather -> R.string.weather_settings_no_data
    WeatherStatus.Ready -> R.string.weather_settings_ready
    WeatherStatus.Error -> R.string.weather_settings_unavailable
}

@Composable
private fun <T> WeatherChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T,
    tag: String,
    label: @Composable (T) -> String,
    key: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                choices.forEach { choice ->
                    val enabled = LocalSettingsStorageState.current.canEdit
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .testTag("$tag:${key(choice)}")
                            .selectable(
                                selected = choice == selected, enabled = enabled,
                                role = Role.RadioButton, onClick = { onSelect(choice) },
                            )
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = choice == selected, onClick = null, enabled = enabled)
                        Text(label(choice), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
