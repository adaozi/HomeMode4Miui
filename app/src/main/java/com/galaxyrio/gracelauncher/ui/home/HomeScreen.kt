/*
 * P4 裁剪版 → P6 恢复版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，
 * https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/home/HomeScreen.kt
 *
 * P4 升级（相对 P1 裁剪版）：widget item / editingLayout 编辑态 / topOffset/widgetHeight
 *   草稿持久化 / 时钟长按 onWidgetMenu / favorites 行右滑 ShortcutPopup。
 *
 * P6 恢复（PORTING_GraceLauncher.md B6.1/B6.2/B6.3，对齐原版 HomeScreen）：
 *   - media item（HomeMediaPlayer + IdleMediaSessionId 常驻占位，mediaAlwaysVisible 开关）
 *   - HomeClockHeader 恢复：calendarAgenda 日程行（nextVisibleEvent + ScheduleLine）、
 *     calendarAboveClock 位置切换、电量百分比、天气（HomeWeather 温度行）
 *   - favorites 行通知预览（notification 参数恢复，渲染见 LauncherComponents）
 *
 * 仍裁剪：FolderRow（folders 阶段）；LauncherUiState 聚合参数 → 显式小参数（本模块惯例）。
 */
package com.galaxyrio.gracelauncher.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInWindow
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.data.GraceButtonSettings
import com.galaxyrio.gracelauncher.ui.components.observeBlankDoubleTaps
import com.galaxyrio.gracelauncher.ui.components.observeListGestures
import com.galaxyrio.gracelauncher.ui.components.rememberLauncherListGestureConnection
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.homemode4miui.R // P1：模块 namespace 为 com.homemode4miui，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.ListAppearance
import com.galaxyrio.gracelauncher.data.ShortcutsFoldersSettings
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.nextVisibleEvent
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.data.media.IdleMediaSessionId
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.ui.components.AppRowGestures
import com.galaxyrio.gracelauncher.ui.components.FolderRow
import com.galaxyrio.gracelauncher.ui.components.LauncherAppRow
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.startPadding
import com.galaxyrio.gracelauncher.ui.components.endPadding
import com.galaxyrio.gracelauncher.ui.components.eventRemainingText
import com.galaxyrio.gracelauncher.ui.components.stableStatusBarInset
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberBatteryPercent
import com.galaxyrio.gracelauncher.ui.weather.HomeWeather
import com.galaxyrio.gracelauncher.ui.widgets.HomeEditHandle
import com.galaxyrio.gracelauncher.ui.widgets.HomeWidget
import com.galaxyrio.gracelauncher.ui.widgets.HostedWidget
import com.galaxyrio.gracelauncher.ui.widgets.rememberHostedWidget
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
// perf3（功耗专项）：时钟 60s 循环 lifecycle-aware 化（桌面退后台挂起）
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    favorites: List<LauncherApp>,
    homeLayout: HomeLayout,
    clockEnabled: Boolean,
    clockStyle: ClockStyle,
    // 上游同步 v1.1.8：hideFavoriteNames 并入 listAppearance（names 可见性 + 内边距/字号）
    listAppearance: ListAppearance,
    shortcutsFolders: ShortcutsFoldersSettings = ShortcutsFoldersSettings(),
    // 上游同步 v1.1.8 S3：桌面手势（上滑/下滑/双击；homeGestures 配置面 = Lab 页）
    homeGestures: GraceButtonSettings = GraceButtonSettings(),
    onHomeGesture: (GraceButtonGesture, Offset) -> Unit = { _, _ -> },
    allowHapticFeedback: Boolean,
    onLaunchApp: (LauncherApp) -> Unit,
    onAppDetails: (LauncherApp) -> Unit,
    onAppShortcuts: (LauncherApp, Rect) -> Unit,
    onDateClick: () -> Unit,
    onClockClick: () -> Unit,
    onWidgetMenu: () -> Unit,
    onCustomWidgetMenu: () -> Unit = {},
    modifier: Modifier = Modifier,
    rowGestures: AppRowGestures = AppRowGestures(),
    // folders 阶段：收藏区文件夹项（原版 HomeScreen.kt:214-224 逐字语义；
    // reveal 拖拽 morph 随后续阶段，先 no-op 默认参）
    folders: List<LauncherFolder> = emptyList(),
    onOpenFolder: (LauncherFolder, Rect) -> Unit = { _, _ -> },
    onEditFolder: (LauncherFolder) -> Unit = {},
    editingLayout: Boolean = false,
    widgetInputEnabled: Boolean = true,
    topSpace: Dp = 24.dp,
    onTopOffsetChange: (Float) -> Unit = {},
    onWidgetHeightChange: (Int) -> Unit = {},
    // ---- P6（B6.x）附属特性 ----
    /** B6.1：当前播放（null = 无会话/桥断/开关关）。 */
    homeMedia: NowPlaying? = null,
    onMediaCommand: (String, MediaCommand) -> Unit = { _, _ -> },
    onDismissMedia: (String, Long) -> Boolean = { _, _ -> false },
    mediaAlwaysVisible: Boolean = false,
    /** B6.3：包名 → 通知预览（favorites 行渲染首条）。 */
    notifications: Map<String, List<AppNotification>> = emptyMap(),
    /** B6.2：日程/天气头部开关面。 */
    calendarAgenda: Boolean = true,
    calendarAboveClock: Boolean = false,
    showBatteryPercentage: Boolean = true,
    /** B6.2：日程事件（14 天窗口，CalendarRepository 宿主进程直读）。 */
    events: List<ScheduleEvent> = emptyList(),
    /** B6.2：天气当前况（null = 未启用/未就绪）。 */
    weather: com.galaxyrio.gracelauncher.data.weather.WeatherCurrent? = null,
) {
    val clockLifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(initialValue = Instant.now()) {
        // perf3（功耗专项）：原实现 while(true) 永续 —— 桌面（宿主 Launcher）退到
        // 后台后仍每 60s 醒主线程 + 触发重组。改为 repeatOnLifecycle(STARTED)：
        // 生命周期 ≤ CREATED 时挂起整个循环（零唤醒），回前台自动重启。
        // 宿主生命周期同步链路：MainHook hook 5b（Launcher.onPause）→
        // HomeController.notifyHomePaused → GraceHomeView.onHomePaused（推 STOP）；
        // hook 未命中的宿主版本上 lifecycle 恒为 RESUMED，行为与旧版一致（安全降级）。
        clockLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
    }
    val event = if (calendarAgenda) nextVisibleEvent(events, now) else null
    var idleMediaDismissed by rememberSaveable(mediaAlwaysVisible) { mutableStateOf(false) }
    LaunchedEffect(homeMedia?.sessionId, homeMedia?.revision) {
        if (homeMedia != null) idleMediaDismissed = false
    }
    // 原版语义：真实会话优先；无会话且 mediaAlwaysVisible 时展示常驻占位播放器
    val media = homeMedia ?: if (mediaAlwaysVisible && !idleMediaDismissed) NowPlaying(
        sessionId = IdleMediaSessionId, playerName = stringResource(R.string.settings_media_player),
        title = stringResource(R.string.media_idle_title), artist = stringResource(R.string.media_idle_summary),
        playing = false, canToggle = true, canPrevious = false, canNext = false,
    ) else null
    val listState = rememberLazyListState()
    val hostedWidget = if (homeLayout.hasWidget) rememberHostedWidget(homeLayout) else null
    var topOffset by remember(homeLayout.topOffsetDp) { mutableFloatStateOf(homeLayout.topOffsetDp) }
    var widgetHeight by remember(homeLayout.widgetId, homeLayout.widgetHeightDp) { mutableFloatStateOf(homeLayout.widgetHeightDp.toFloat()) }
    var headerHeightPx by remember { mutableIntStateOf(0) }
    var anchorOffsetPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val minimumTop = stableStatusBarInset() + 8.dp
    val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    LaunchedEffect(editingLayout) { if (editingLayout) listState.scrollToItem(0) }
    val overscroll = rememberOverscrollEffect()
    // 上游同步 v1.1.8 S3：HomeOverscrollConnection → LauncherListGestureConnection
    // （原 overscroll 转发逻辑并入连接器；边界剩余动量驱动的上滑/下滑手势，零消费不抢行内拖拽）
    val gesturesEnabled = widgetInputEnabled && !editingLayout && homeGestures.enabled
    val doubleTapEnabled = gesturesEnabled && homeGestures.doubleTap.active
    val latestGestures by rememberUpdatedState(homeGestures)
    val latestEnabled by rememberUpdatedState(gesturesEnabled)
    val latestOnGesture by rememberUpdatedState(onHomeGesture)
    val context = LocalContext.current
    val doubleTapSlop = remember(context, density) {
        android.view.ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()
    }
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    var pointerPosition by remember { mutableStateOf(Offset.Zero) }
    val scrollConnection = rememberLauncherListGestureConnection(
        overscroll, listState, homeGestures.swipeSensitivity,
        canTrigger = { latestEnabled && latestGestures.target(it).active },
        onGesture = { latestOnGesture(it, windowOffset + pointerPosition) },
    )
    LaunchedEffect(gesturesEnabled) { if (!gesturesEnabled) scrollConnection.cancelGesture() }

    BoxWithConstraints(modifier.fillMaxSize()
        .onGloballyPositioned { windowOffset = it.positionInWindow() }
        .observeListGestures(scrollConnection) { pointerPosition = it }
        // 弹层禁用手势时保持节点挂载：移除 pointer-input 祖先会连坐取消后代，
        // 导致 app/folder 行刚拉起的弹层被打断 —— 仅重启本观察器即可清掉未决 tap。
        .pointerInput(doubleTapEnabled, doubleTapSlop, latestGestures.doubleTapIntervalMs) {
            if (!doubleTapEnabled) return@pointerInput
            observeBlankDoubleTaps(doubleTapSlop, latestGestures.doubleTapIntervalMs) { position ->
                if (latestEnabled && latestGestures.doubleTap.active) {
                    latestOnGesture(GraceButtonGesture.DoubleTap, windowOffset + position)
                }
            }
        }) {
    val availableHeight = (maxHeight - bottomInset).coerceAtLeast(0.dp)
    val headerHeight = with(density) { headerHeightPx.toDp() }
    // Only content ABOVE the calendar anchor constrains its position. Agenda,
    // media, widgets and favorites grow downwards without moving this anchor.
    // Measure the boundary, not a hard-coded clock height, so it can be optional later.
    val anchorOffset = with(density) { anchorOffsetPx.toDp() }
    val maximumTop = (availableHeight - anchorOffset - 56.dp).coerceAtLeast(minimumTop)
    val resolvedTop = (topSpace + topOffset.dp).coerceIn(minimumTop, maximumTop)
    val minWidgetHeight = hostedWidget?.minHeight ?: 0
    val maximumWidgetHeight = minOf(hostedWidget?.maxHeight ?: 0,
        (availableHeight - minimumTop - headerHeight - 56.dp).value.toInt()).coerceAtLeast(minWidgetHeight)
    val resolvedWidgetHeight = if (hostedWidget == null) 0 else
        (if (widgetHeight == 0f) hostedWidget.defaultHeight else widgetHeight.roundToInt()).coerceIn(minWidgetHeight, maximumWidgetHeight)
    val resizeLimit = (availableHeight - resolvedTop - headerHeight - 56.dp).value.toInt()
        .coerceIn(minWidgetHeight, maximumWidgetHeight)
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
            .overscroll(overscroll)
            .nestedScroll(scrollConnection)
            .testTag("home_content"),
        // The row's 8dp inset keeps icons aligned at 44dp while giving its
        // rounded touch surface breathing room around the icon.
        contentPadding = PaddingValues(start = listAppearance.startPadding, end = listAppearance.endPadding, top = resolvedTop, bottom = 72.dp + bottomInset),
        userScrollEnabled = !editingLayout,
        // Feed the native effect at both boundaries even when every row fits.
        // LazyColumn's own effect is disabled to avoid stretching twice.
        overscrollEffect = null,
    ) {
        item(key = "date", contentType = "date") {
            HomeClockHeader(
                now = now,
                event = event,
                onDateClick = onDateClick,
                onClockClick = onClockClick,
                onLongClick = onWidgetMenu,
                onAnchorOffsetChange = { anchorOffsetPx = it },
                showClock = clockEnabled,
                clockStyle = clockStyle,
                interactive = !editingLayout,
                showCalendar = calendarAgenda,
                calendarAboveClock = calendarAboveClock,
                showBattery = showBatteryPercentage,
                weather = weather,
                modifier = Modifier.fillMaxWidth().onSizeChanged { headerHeightPx = it.height },
            )
            Spacer(Modifier.height(2.dp))
        }
        if (hostedWidget != null) item(key = "widget:${homeLayout.widgetId}", contentType = "widget") {
            HomeWidget(homeLayout, hostedWidget, resolvedWidgetHeight, editingLayout,
                enabled = widgetInputEnabled, hapticsEnabled = allowHapticFeedback,
                onLongPress = onCustomWidgetMenu)
            Spacer(Modifier.height(2.dp))
        }
        if (media != null) item(key = "media", contentType = "media") {
            HomeMediaPlayer(media, onMediaCommand, onDismiss = { id, revision ->
                if (id == IdleMediaSessionId) { idleMediaDismissed = true; true }
                else onDismissMedia(id, revision).also { if (it) idleMediaDismissed = true }
            })
            Spacer(Modifier.height(2.dp))
        }
        items(favorites, key = LauncherApp::key, contentType = { if (it.folderId == null) "app" else "folder" }) { app ->
            val folder = app.folderId?.let { id -> folders.firstOrNull { it.id == id } }
            if (folder != null) FolderRow(
                folder = folder,
                app = app,
                onOpen = { onOpenFolder(folder, it) },
                onLongClick = { onEditFolder(folder) },
                showLabel = listAppearance.showNames(favorites = true),
            ) else LauncherAppRow(
                app = app,
                onClick = { onLaunchApp(app) },
                onLongClick = { onAppDetails(app) },
                onSwipeRight = { onAppShortcuts(app, it) },
                gestures = rowGestures,
                // 上游同步 v1.1.8：通知预览按 shortcutsFolders 显示策略过滤（原 notificationsFor 语义内联）
                notification = notifications[app.packageName].orEmpty()
                    .filter { shortcutsFolders.showsNotification(it.silent, expanded = false) }
                    .firstOrNull().takeUnless { app.shortcut != null || app.user != null || app.folderId != null },
                showLabel = listAppearance.showNames(favorites = true),
            )
        }
    }
    // The visual move handle stays BELOW the agenda, independently of the
    // actual anchor above the clock. Its position never drives the layout.
    // Overlay the handles in the viewport, not outside a lazy item's bounds:
    // both halves of each circular control must remain inside its hit-test area.
    if (editingLayout) {
        val handleModifier = Modifier.padding(start = listAppearance.startPadding, end = listAppearance.endPadding)
        HomeEditHandle(
            label = stringResource(R.string.widget_move_home), changed = topOffset != 0f, tag = "home_position_handle",
            onReset = { topOffset = 0f; onTopOffsetChange(0f) },
            onDelta = { delta ->
                val currentTop = (topSpace.value + topOffset).coerceIn(minimumTop.value, maximumTop.value)
                topOffset = (currentTop + delta).coerceIn(minimumTop.value, maximumTop.value) - topSpace.value
            },
            onFinished = { onTopOffsetChange(topOffset) },
            modifier = handleModifier.offset(y = resolvedTop + headerHeight - 20.dp),
        )
        if (hostedWidget != null) HomeEditHandle(
            label = stringResource(R.string.widget_resize), changed = widgetHeight != 0f && widgetHeight.roundToInt() != hostedWidget.defaultHeight,
            tag = "home_widget_size_handle",
            onReset = { widgetHeight = 0f; onWidgetHeightChange(0) },
            onDelta = { delta -> widgetHeight = ((if (widgetHeight == 0f) resolvedWidgetHeight.toFloat() else widgetHeight) + delta)
                .coerceIn(minWidgetHeight.toFloat(), resizeLimit.toFloat()) },
            onFinished = { onWidgetHeightChange(if (widgetHeight.roundToInt() == hostedWidget.defaultHeight) 0 else widgetHeight.roundToInt()) },
            modifier = handleModifier.offset(y = resolvedTop + headerHeight + 2.dp + resolvedWidgetHeight.dp - 20.dp),
        )
    }
    }
}

// 上游同步 v1.1.8 S3：HomeOverscrollConnection 已被 LauncherListGestureConnection 取代（ui/components/LauncherListGestures.kt）。

@Composable
internal fun HomeClockHeader(
    now: Instant,
    onDateClick: () -> Unit,
    onClockClick: () -> Unit,
    clockStyle: ClockStyle,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    clockTag: String = "home_clock",
    dateTag: String = "home_date",
    onLongClick: () -> Unit = {},
    onAnchorOffsetChange: (Int) -> Unit = {},
    showClock: Boolean = true,
    showCalendar: Boolean = true,
    calendarAboveClock: Boolean = false,
    showBattery: Boolean = true,
    event: ScheduleEvent? = null,
    weather: com.galaxyrio.gracelauncher.data.weather.WeatherCurrent? = null,
) {
    val appearance = LocalLauncherAppearance.current
    val battery = if (showCalendar && showBattery) rememberBatteryPercent() else null
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val clockText = formatHomeClock(now, DateFormat.is24HourFormat(context), locale)
    val week = showClock && clockStyle.layout.week
    // perf5（功耗专项）：日期格式化器 remember 缓存 —— getBestDateTimePattern 内部
    // 走 ICU/资源查询，原实现每次重组重建三个格式化器（时钟每分钟 tick、通知/媒体
    // 变化都会触发本组合体重组）。
    val weekday = remember(locale) { SimpleDateFormat("EEE", locale) }.format(Date.from(now))
    val datePattern = remember(locale, week) {
        DateFormat.getBestDateTimePattern(locale, if (week) "MMMd" else "MMMEd")
    }
    val dateText = remember(datePattern) { SimpleDateFormat(datePattern, locale) }.format(Date.from(now))
    val dateDescription = stringResource(if (weather != null) R.string.weather_agenda_action else R.string.date_calendar_action, dateText)
    val clockDescription = stringResource(R.string.clock_action, clockText)

    val clockContent: @Composable () -> Unit = {
        if (showClock) ClockFace(
            time = if (week) weekday else clockText,
            style = clockStyle,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(clockTag)
                .semantics { contentDescription = if (week) dateDescription else clockDescription }
                .then(if (interactive) Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = if (week) onDateClick else onClockClick,
                    onLongClick = onLongClick,
                ) else Modifier)
                .padding(horizontal = LauncherLayout.ContentInset),
            color = appearance.text,
        )
    }
    val calendarContent: @Composable () -> Unit = {
        val shape = LauncherLayout.RowShape
        if (showCalendar || weather?.temperature != null) Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(dateTag)
                .semantics { contentDescription = dateDescription }
                .clip(shape)
                .then(if (interactive) Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = appearance.text),
                    role = Role.Button,
                    onClick = onDateClick,
                    onLongClick = onLongClick,
                ) else Modifier),
            shape = shape,
            color = Color.Transparent,
            contentColor = appearance.text,
        ) {
            val dateStyle = MaterialTheme.typography.bodyLarge.copy(
                fontWeight = FontWeight.Normal,
                shadow = appearance.textShadow,
            )
            Column(Modifier.padding(vertical = LauncherLayout.ContentInset)) {
                Row(Modifier.padding(horizontal = LauncherLayout.ContentInset, vertical = 2.dp)) {
                    if (showCalendar && week) {
                        ClockTimeLabel(
                            time = clockText, style = clockStyle, textStyle = dateStyle,
                            modifier = Modifier.alignByBaseline()
                                .semantics { contentDescription = clockDescription }
                                .then(if (interactive) Modifier.combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                                    onClick = onClockClick,
                                    onLongClick = onLongClick,
                                ) else Modifier),
                            color = appearance.text,
                        )
                        Text(" · ", modifier = Modifier.alignByBaseline(), color = appearance.text, style = dateStyle)
                    }
                    if (showCalendar) Text(
                        text = dateText + (battery?.let { "  $it%" } ?: ""),
                        modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("${dateTag}_text"),
                        color = appearance.text,
                        style = dateStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (weather?.temperature != null) {
                        if (showCalendar) Spacer(Modifier.width(10.dp))
                        HomeWeather(weather, appearance.text, dateStyle, Modifier.alignByBaseline())
                    }
                }
                if (showCalendar && event != null) {
                    ScheduleLine(event = event, now = now)
                }
            }
        }
    }
    Column(modifier) {
        if (calendarAboveClock) calendarContent() else clockContent()
        Spacer(Modifier.height(if (showClock && (showCalendar || weather != null)) 2.dp else 0.dp)
            .onGloballyPositioned { onAnchorOffsetChange(it.positionInParent().y.roundToInt()) })
        if (calendarAboveClock) clockContent() else calendarContent()
    }
}

@Composable
private fun ScheduleLine(event: ScheduleEvent, now: Instant) {
    val appearance = LocalLauncherAppearance.current
    val remaining = eventRemainingText(event, now)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontWeight = FontWeight.Normal,
        shadow = appearance.textShadow,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_line")
            .padding(horizontal = LauncherLayout.ContentInset, vertical = 2.dp),
    ) {
        Text(
            text = event.title,
            // CJK fallback and Latin fonts can have different ascents at the same size.
            modifier = Modifier.weight(1f, fill = false).alignByBaseline().testTag("schedule_title"),
            style = style,
            color = appearance.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "· $remaining",
            modifier = Modifier.alignByBaseline().testTag("schedule_remaining"),
            style = style,
            color = appearance.text.copy(alpha = 0.93f),
            maxLines = 1,
        )
    }
}
