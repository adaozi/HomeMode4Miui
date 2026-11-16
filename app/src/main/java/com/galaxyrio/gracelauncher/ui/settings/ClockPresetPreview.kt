package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.weather.WeatherCondition
import com.galaxyrio.gracelauncher.data.weather.WeatherCurrent
import com.galaxyrio.gracelauncher.data.weather.WeatherDay
import com.galaxyrio.gracelauncher.data.weather.WeatherLocation
import com.galaxyrio.gracelauncher.data.weather.WeatherMeasure
import com.galaxyrio.gracelauncher.data.weather.WeatherSnapshot
import com.galaxyrio.gracelauncher.data.weather.WeatherTemperature
import com.galaxyrio.gracelauncher.data.weather.WeatherWind
import com.galaxyrio.gracelauncher.ui.home.HomeClockHeader
import com.galaxyrio.gracelauncher.ui.theme.LauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.LauncherBatteryState
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherTypography
import com.galaxyrio.gracelauncher.ui.theme.LocalWallpaperFontColor
import java.time.Instant
import com.galaxyrio.gracelauncher.data.tasks.TaskItem
import java.time.LocalDateTime
import java.time.ZoneId

internal data class ClockPresetPreviewContent(
    val now: Instant,
    val events: List<ScheduleEvent>,
    val weather: WeatherSnapshot,
    val battery: LauncherBatteryState,
    val tasks: List<TaskItem> = emptyList(),
)

/** Every preset uses the same fixed content, independent of providers and clock ticks. */
@Composable
internal fun rememberClockPresetPreviewContent(): ClockPresetPreviewContent {
    val zone = ZoneId.systemDefault()
    val title = stringResource(R.string.clock_preset_agenda_title)
    val eventLocation = stringResource(R.string.clock_agenda_preview_location)
    val calendarName = stringResource(R.string.clock_agenda_preview_calendar)
    val weatherLocation = stringResource(R.string.clock_weather_preview_location)
    val weatherDescription = stringResource(R.string.weather_clear)
    val calendarColor = MaterialTheme.colorScheme.primary.toArgb()
    val taskTitle = stringResource(R.string.clock_task_preview_title)
    val taskList = stringResource(R.string.clock_task_preview_list)
    return remember(zone, title, eventLocation, calendarName, weatherLocation, weatherDescription, calendarColor, taskTitle, taskList) {
        val now = LocalDateTime.of(2026, 11, 3, 9, 30).atZone(zone).toInstant()
        ClockPresetPreviewContent(
            now = now,
            events = listOf(ScheduleEvent(
                id = -1L,
                title = title,
                startsAt = now.plusSeconds(86_400),
                endsAt = now.plusSeconds(90_000),
                isAllDay = false,
                location = eventLocation,
                calendarColor = calendarColor,
                calendarName = calendarName,
            )),
            weather = WeatherSnapshot(
                location = WeatherLocation("preset_preview", weatherLocation, zone),
                current = WeatherCurrent(
                    temperature = WeatherTemperature(22.0, "c"),
                    condition = WeatherCondition.Clear,
                    isDaylight = true,
                    description = weatherDescription,
                    feelsLike = WeatherTemperature(23.0, "c"),
                    humidity = WeatherMeasure(60.0, "percent"),
                    wind = WeatherWind(90.0, WeatherMeasure(8.0, "kph")),
                    airQuality = WeatherMeasure(35.0, "aqi"),
                    uv = WeatherMeasure(3.0, "uvi"),
                ),
                hourly = emptyList(),
                daily = listOf(WeatherDay(
                    now.atZone(zone).toLocalDate(), WeatherTemperature(25.0, "c"),
                    WeatherTemperature(17.0, "c"), WeatherCondition.Clear, weatherDescription,
                )),
                updatedAt = now,
                attribution = "",
            ),
            battery = LauncherBatteryState(percent = 80, isCharging = false),
            tasks = listOf(TaskItem(id = -1L, title = taskTitle, dueAt = now.plusSeconds(7200),
                listName = taskList, listColor = calendarColor)),
        )
    }
}

/** Measure once at desktop width; scale the complete widget without intrinsic queries or cropping. */
@Composable
internal fun ClockPresetPreview(
    style: ClockStyle,
    content: ClockPresetPreviewContent,
    appearance: LauncherAppearance,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    val previewAppearance = remember(appearance, contentColor) {
        appearance.copy(darkText = contentColor.luminance() < 0.5f, textColor = contentColor)
    }
    CompositionLocalProvider(
        LocalLauncherAppearance provides previewAppearance,
        LocalWallpaperFontColor provides contentColor,
    ) {
        MaterialTheme(typography = LocalLauncherTypography.current) {
            Layout(
                modifier = modifier.clearAndSetSemantics {},
                content = {
                    HomeClockHeader(
                        now = content.now,
                        event = content.events.firstOrNull(),
                        agendaEvents = content.events,
                        tasks = content.tasks,
                        showTasks = true,
                        onDateClick = {},
                        onClockClick = {},
                        showBattery = true,
                        clockStyle = style,
                        weather = content.weather.current,
                        weatherSnapshot = content.weather,
                        modifier = Modifier.fillMaxWidth(),
                        interactive = false,
                        clockActive = false,
                        batteryOverride = content.battery,
                    )
                },
            ) { measurables, constraints ->
                val desktopWidth = 360.dp.roundToPx().coerceAtLeast(1)
                val child = measurables.single().measure(Constraints.fixedWidth(desktopWidth))
                val width = constraints.constrainWidth(if (constraints.hasBoundedWidth) constraints.maxWidth else desktopWidth)
                val height = constraints.constrainHeight(if (constraints.hasBoundedHeight) constraints.maxHeight else child.height)
                val scale = minOf(1f, width.toFloat() / child.width.coerceAtLeast(1),
                    height.toFloat() / child.height.coerceAtLeast(1))
                layout(width, height) {
                    child.placeWithLayer(0, 0) {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale
                        scaleY = scale
                        translationX = (width - child.width * scale) / 2f
                        translationY = (height - child.height * scale) / 2f
                    }
                }
            }
        }
    }
}
