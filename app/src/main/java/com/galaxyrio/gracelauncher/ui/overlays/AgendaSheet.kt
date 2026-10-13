/*
 * P6 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，
 * https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/overlays/AgendaSheet.kt
 *
 * 保留：日程按日分组（weatherAgendaDays）、当日逐时预报（HourlyWeather）、
 *   每日高低温（DailyWeather）、事件行（AgendaEvent：日历色条 + 时间 + 剩余）、
 *   天气不可用引导行（WeatherConnectionNotice）、日历权限/错误行（降级版）。
 *
 * 裁剪与降级（对齐 PORTING_GraceLauncher.md B6.2）：
 *   - LauncherUiState/LauncherActions 聚合 → 显式参数 + 独立回调（本模块惯例）
 *   - 原版 ModalBottomSheet（独立窗口，hotfix2 资源错位坑）→ 同窗口面板
 *     （LauncherOverlays 分发时已置于宿主 Compose 树内）
 *   - newEvent/openEvent → 系统日历 Intent（原版日历写路径的降级等价）；
 *     requestWeatherAccess → 引导刷新（provider 无权限门槛，本动作即重拉快照）；
 *     openWeatherApp → 打开小米天气（未装走市场兜底），失败 Toast。
 */
package com.galaxyrio.gracelauncher.ui.overlays

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.weather.WeatherSnapshot
import com.galaxyrio.gracelauncher.data.weather.WeatherStatus
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.components.eventRemainingText
import com.galaxyrio.gracelauncher.ui.components.rememberMinuteTick
import com.galaxyrio.gracelauncher.ui.weather.DailyWeather
import com.galaxyrio.gracelauncher.ui.weather.HourlyWeather
import com.galaxyrio.gracelauncher.ui.weather.WeatherSourceNote
import com.galaxyrio.gracelauncher.ui.weather.weatherAgendaDays
import com.homemode4miui.grace.GraceScheduleStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

// Keep a modest indent beneath each date, with one shared column for the plus
// and calendar-color marker and another for all agenda text.
private val AgendaItemInset = 4.dp
private val AgendaLeadingWidth = 24.dp
private val AgendaContentGap = 8.dp

@Composable
fun AgendaSheet(
    events: List<ScheduleEvent>,
    weather: com.galaxyrio.gracelauncher.data.weather.WeatherState,
    weatherEnabled: Boolean,
    weatherForecastDays: Int,
    calendarAgenda: Boolean,
    scheduleStatus: GraceScheduleStatus,
    onRefreshAgenda: () -> Unit,
    onRefreshWeather: () -> Unit,
    onOpenWeather: () -> Unit,
    onRequestCalendar: () -> Unit,
    onOpenEvent: (ScheduleEvent) -> Unit,
    onNewEvent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val zone = ZoneId.systemDefault()
    // perf3 公共组件（v2.9.41）：repeatOnLifecycle(STARTED) 节拍（原实现裸 while(true)）
    val now = rememberMinuteTick()
    val today = now.atZone(zone).toLocalDate()
    val snapshot = weather.snapshot.takeIf { weatherEnabled }
    val forecastDays = weatherForecastDays.coerceIn(1, 14)
    val forecasts = remember(snapshot?.daily, today, forecastDays) {
        snapshot?.daily.orEmpty().filter { it.date >= today && it.date < today.plusDays(forecastDays.toLong()) }.associateBy { it.date }
    }
    val agendaEvents = events.takeIf { calendarAgenda }.orEmpty()
    val groups = remember(agendaEvents, forecasts, today, zone, forecastDays) {
        weatherAgendaDays(agendaEvents, forecasts.keys.toList(), today, forecastDays, zone)
    }
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale) }
    val compactDateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    val numericDateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "Md"), locale) }
    Box(Modifier.fillMaxSize().testTag("launcher_sheet")) {
        // Scrim（对齐 HomeWidgetPanel；原版 ModalBottomSheet 独立窗口 → 同窗口面板）
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onDismiss).clearAndSetSemantics {})
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = panelWindowHeight() * 0.9f).testTag("agenda_sheet"),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            // 原版 Agenda sheet 用 surfaceContainer 区分主面板
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().height(panelWindowHeight() * 0.66f).padding(horizontal = 30.dp)) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.your_agenda), fontSize = 25.sp, fontWeight = FontWeight.Medium)
                }
                if ((calendarAgenda && scheduleStatus == GraceScheduleStatus.Loading && agendaEvents.isEmpty()) ||
                    (weatherEnabled && weather.status == WeatherStatus.Loading && snapshot == null)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("agenda_list"), contentPadding = PaddingValues(bottom = 20.dp)) {
                    groups.forEach { (date, dayEvents) ->
                        item(key = "day:$date") {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val forecast = forecasts[date]
                                val current = snapshot?.current.takeIf { date == today }
                                val hasWeather = forecast != null || current != null
                                val compact = hasWeather && (maxWidth < 300.dp || configuration.fontScale >= 1.3f)
                                val rowDateFormat = when {
                                    compact && maxWidth.value / configuration.fontScale < 150f -> numericDateFormat
                                    compact -> compactDateFormat
                                    else -> dateFormat
                                }
                                val difference = ChronoUnit.DAYS.between(today, date).toInt()
                                val relativeDay = if (difference == 0) stringResource(R.string.today) else stringResource(R.string.event_in_days, difference)
                                val spokenDate = "${date.format(dateFormat)}, $relativeDay"
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp).testTag("agenda_day:$date"), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(date.format(rowDateFormat), modifier = Modifier.weight(1f, fill = false).testTag("agenda_date:$date")
                                            .semantics { contentDescription = spokenDate },
                                            fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!compact) {
                                            Spacer(Modifier.width(8.dp))
                                            Text(relativeDay, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                        }
                                    }
                                    if (hasWeather) {
                                        Spacer(Modifier.width(10.dp))
                                        DailyWeather(forecast, current, Modifier.testTag("agenda_weather:$date"))
                                    }
                                }
                            }
                        }
                        if (date == today) {
                            if (snapshot != null) item(key = "today_weather") {
                                HourlyWeather(snapshot, now)
                                WeatherSourceNote(snapshot, now, onOpenWeather)
                            }
                            else if (weatherEnabled && weather.status != WeatherStatus.Loading) item(key = "weather_unavailable") {
                                WeatherConnectionNotice(weather.status, onOpenWeather, onRefreshWeather)
                            }
                            // Calendar access is independent of weather access: keep the forecast visible
                            // when the user has not connected a calendar, or its provider fails.
                            if (calendarAgenda) {
                                when (scheduleStatus) {
                                    GraceScheduleStatus.PermissionRequired -> item(key = "calendar_permission") {
                                        Text(stringResource(R.string.calendar_permission_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(8.dp))
                                        Button(onClick = onRequestCalendar) { Text(stringResource(R.string.connect_calendar)) }
                                    }
                                    GraceScheduleStatus.Error -> item(key = "calendar_error") {
                                        Text(stringResource(R.string.calendar_error))
                                        TextButton(onClick = onRefreshAgenda) { Text(stringResource(R.string.retry)) }
                                    }
                                    else -> Unit
                                }
                                item(key = "new_event") {
                                    NewAgendaEvent(onNewEvent)
                                }
                            }
                        }
                        items(dayEvents, key = { "$date:${it.id}:${it.startsAt}" }) { event ->
                            AgendaEvent(event, now) { onOpenEvent(event) }
                        }
                        item(key = "space:$date") { Spacer(Modifier.height(20.dp)) }
                    }
                    if (calendarAgenda && agendaEvents.isEmpty() && scheduleStatus == GraceScheduleStatus.Ready) item {
                        Text(stringResource(R.string.no_upcoming_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherConnectionNotice(status: WeatherStatus, onOpen: () -> Unit, onRefresh: () -> Unit) {
    // 小米天气（com.miui.weather2）为系统预装：NotInstalled/UnsupportedVersion 实际不可达，
    // 统一收敛到"打开天气"动作（未装时 openWeatherApp 内部走市场兜底）。
    val (message, label, onClick) = when (status) {
        WeatherStatus.Disabled, WeatherStatus.Loading -> return
        WeatherStatus.NotInstalled -> Triple(R.string.weather_settings_not_installed, R.string.weather_settings_open, onOpen)
        WeatherStatus.PermissionRequired -> Triple(R.string.weather_settings_permission, R.string.weather_settings_open, onOpen)
        WeatherStatus.UnsupportedVersion -> Triple(R.string.weather_settings_incompatible, R.string.weather_settings_open, onOpen)
        WeatherStatus.NoLocations -> Triple(R.string.weather_settings_no_locations, R.string.weather_settings_open, onOpen)
        WeatherStatus.NoWeather, WeatherStatus.Ready -> Triple(R.string.weather_settings_no_data, R.string.weather_settings_open, onOpen)
        WeatherStatus.Error -> Triple(R.string.weather_settings_unavailable, R.string.retry, onRefresh)
    }
    Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    TextButton(onClick = onClick, modifier = Modifier.testTag("weather_connection_action")) { Text(stringResource(label)) }
}

@Composable
private fun NewAgendaEvent(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new_event")
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AgendaItemInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(AgendaLeadingWidth), contentAlignment = Alignment.Center) {
            LauncherIcon(LauncherSymbol.Plus, modifier = Modifier.testTag("new_event:icon"), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(AgendaContentGap))
        Text(stringResource(R.string.new_event), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AgendaEvent(event: ScheduleEvent, now: Instant, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val clockPattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    val timeFormat = remember(locale, clockPattern) { DateTimeFormatter.ofPattern(clockPattern, locale).withZone(ZoneId.systemDefault()) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (event.isAllDay) 48.dp else 66.dp).testTag("agenda_event:${event.id}")
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = AgendaItemInset, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(AgendaLeadingWidth), contentAlignment = Alignment.Center) {
            Box(Modifier.width(5.dp).height(if (event.isAllDay) 22.dp else 40.dp)
                .testTag("agenda_event_indicator:${event.id}")
                .background(event.calendarColor?.let { Color(it).copy(alpha = 1f) } ?: MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)))
        }
        Spacer(Modifier.width(AgendaContentGap))
        Column(Modifier.weight(1f)) {
            Text(event.title, fontSize = 16.sp)
            if (!event.isAllDay) {
                Text("${timeFormat.format(event.startsAt)}–${timeFormat.format(event.endsAt)} · ${eventRemainingText(event, now)}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** B6.2 降级动作：系统日历意图（新建/查看），失败 Toast（宿主无日历 app 时）。 */
object AgendaIntents {
    fun openEvent(context: android.content.Context, event: ScheduleEvent) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW)
                .setData(CalendarContract.Events.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.Events._ID, event.id.toString()).build())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) com.homemode4miui.util.ModLog.e("AgendaIntents openEvent failed", error)
            Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun newEvent(context: android.content.Context) {
        runCatching {
            // EDIT 快速创建路径：begin 指向 1 小时后的整点（原版 newEvent 语义近似）。
            val begin = (System.currentTimeMillis() + 3_600_000L) / 900_000L * 900_000L
            context.startActivity(Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) com.homemode4miui.util.ModLog.e("AgendaIntents newEvent failed", error)
            Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    fun openWeatherApp(context: android.content.Context) {
        runCatching {
            context.packageManager.getLaunchIntentForPackage(
                com.galaxyrio.gracelauncher.data.weather.MiuiWeatherRepository.PACKAGE_NAME)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            } ?: Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            if (error !is ActivityNotFoundException) com.homemode4miui.util.ModLog.e("AgendaIntents openWeatherApp failed", error)
            Toast.makeText(context, R.string.action_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
