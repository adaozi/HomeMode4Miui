/*
 * 上游同步 v1.1.8 S3（2026-10-11）—— 移植自 GraceLauncher v1.1.8 原版（GPL-3.0）。
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.GraceButtonSettings
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import kotlin.math.roundToInt

@Composable
internal fun AdvancedSettings(onBack: () -> Unit, navigate: (SettingsPage) -> Unit) {
    SettingsScaffold(stringResource(R.string.settings_advanced), "settings_advanced", onBack) { padding ->
        SettingsList(padding) {
            item { SettingsHeading(stringResource(R.string.settings_lab)) }
            item {
                SettingsActionItem(stringResource(R.string.settings_grace_button), null, 0, 2, "lab_open_grace_button",
                    leading = { Icon(painterResource(R.drawable.ic_grace_solid), null,
                        Modifier.size(24.dp).graphicsLayer { scaleX = 5f / 3f; scaleY = 5f / 3f }) }) {
                    navigate(SettingsPage.LabGraceButton)
                }
            }
            item {
                SettingsActionItem(stringResource(R.string.settings_gestures), null, 1, 2, "lab_open_gesture",
                    leading = { LauncherIcon(LauncherSymbol.Gesture) }) { navigate(SettingsPage.LabGesture) }
            }
        }
    }
}

@Composable
internal fun LabGraceButtonSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val settings = uiState.settings.graceButton
    val defaults = remember { GraceButtonSettings() }
    SettingsScaffold(stringResource(R.string.settings_grace_button), "lab_grace_button", onBack) { padding ->
        SettingsList(padding) {
            item {
                SettingsFeatureBanner(stringResource(R.string.lab_animation), settings.animationsEnabled, "lab_animation_enabled") { value ->
                    actions.updateSettings { it.copy(graceButton = it.graceButton.copy(animationsEnabled = value)) }
                }
            }
            item {
                LabSliderItem(stringResource(R.string.settings_search), settings.searchAnimationDurationMs,
                    defaults.searchAnimationDurationMs, 100..1500, 0, 2, "lab_search_duration", enabled = settings.animationsEnabled,
                    onReset = { actions.updateSettings { it.copy(graceButton = it.graceButton.copy(searchAnimationDurationMs = defaults.searchAnimationDurationMs)) } },
                    onChange = { value -> actions.updateSettings { it.copy(graceButton = it.graceButton.copy(searchAnimationDurationMs = value)) } })
            }
            item {
                LabSliderItem(stringResource(R.string.settings_folder_app_list), settings.appListAnimationDurationMs,
                    defaults.appListAnimationDurationMs, 100..1500, 1, 2, "lab_list_duration", enabled = settings.animationsEnabled,
                    onReset = { actions.updateSettings { it.copy(graceButton = it.graceButton.copy(appListAnimationDurationMs = defaults.appListAnimationDurationMs)) } },
                    onChange = { value -> actions.updateSettings { it.copy(graceButton = it.graceButton.copy(appListAnimationDurationMs = value)) } })
            }
        }
    }
}

@Composable
internal fun LabGestureSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val settings = uiState.settings.homeGestures
    val systemInterval = LocalViewConfiguration.current.doubleTapTimeoutMillis.toInt().coerceIn(150, 700)
    SettingsScaffold(stringResource(R.string.settings_gestures), "lab_gesture", onBack) { padding ->
        SettingsList(padding) {
            item { Spacer(Modifier.height(24.dp)) }
            item {
                LabSliderItem(stringResource(R.string.lab_swipe_sensitivity), settings.swipeSensitivity, 100,
                    50..200, 0, 2, "lab_swipe_sensitivity", valueFormat = R.string.lab_percentage,
                    description = stringResource(R.string.lab_swipe_sensitivity_summary),
                    onReset = { actions.updateSettings { it.copy(homeGestures = it.homeGestures.copy(swipeSensitivity = 100)) } },
                    onChange = { value -> actions.updateSettings { it.copy(homeGestures = it.homeGestures.copy(swipeSensitivity = value)) } })
            }
            item {
                LabSliderItem(stringResource(R.string.lab_double_tap_interval), settings.doubleTapIntervalMs ?: systemInterval,
                    systemInterval, 150..700, 1, 2, "lab_double_tap_interval",
                    description = stringResource(R.string.lab_double_tap_interval_summary),
                    hasOverride = settings.doubleTapIntervalMs != null,
                    onReset = { actions.updateSettings { it.copy(homeGestures = it.homeGestures.copy(doubleTapIntervalMs = null)) } },
                    onChange = { value -> actions.updateSettings { it.copy(homeGestures = it.homeGestures.copy(doubleTapIntervalMs = value)) } })
            }
        }
    }
}

/** Keep local drag feedback smooth and persist only the released value. */
@Composable
private fun LabSliderItem(title: String, value: Int, default: Int, range: IntRange, index: Int, count: Int, tag: String,
    enabled: Boolean = true, valueFormat: Int = R.string.lab_milliseconds, description: String? = null,
    hasOverride: Boolean = value != default,
    onReset: () -> Unit, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value.coerceIn(range).toFloat()) }
    val editable = enabled && LocalSettingsStorageState.current.canEdit
    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        content = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f))
                Text(stringResource(valueFormat, draft.roundToInt()), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge)
            }
        },
        supportingContent = {
            Column(Modifier.fillMaxWidth()) {
                if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Slider(draft, onValueChange = { draft = it }, enabled = editable,
                        onValueChangeFinished = { onChange(draft.roundToInt().coerceIn(range)) },
                        valueRange = range.first.toFloat()..range.last.toFloat(),
                        modifier = Modifier.weight(1f).testTag(tag).semantics { contentDescription = title })
                    IconButton(onClick = { draft = default.toFloat(); onReset() }, enabled = editable && (hasOverride || draft.roundToInt() != default),
                        modifier = Modifier.size(48.dp).testTag("${tag}_reset")) {
                        Icon(painterResource(R.drawable.ms_restart_alt), stringResource(R.string.clock_reset_value, title))
                    }
                }
            }
        })
}
