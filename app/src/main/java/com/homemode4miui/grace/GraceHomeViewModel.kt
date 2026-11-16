/*
 * P1 裁剪版 ViewModel —— 新建文件（非搬运），语义对齐 GraceLauncher v1.1.4 的
 * ui/LauncherViewModel.kt（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）。
 *
 * 与原版 LauncherViewModel 的关系（PORTING_GraceLauncher.md B1.3/B3.x/B4.x）：
 *   - 保留：应用加载（AppRepository：LauncherApps 查询 + PM 兜底 + LauncherAppOrder 排序）、
 *     favorites（FavoritesStore SP 收藏/排序/迁移，原版 v1.1.4 favorites 即走 SP）、
 *     settings（GraceSettingsRepository：Room launcher_settings 表 + SP seed 迁移，
 *     P3 起 Room 为准）、包变化广播自刷新、时钟打开路径（Android 标准 SHOW_ALARMS，
 *     对齐原版 LauncherSettings.clockAppKey == null 的默认语义）
 *   - P4 新增：ShortcutRepository（弹层快捷方式查询/缓存/启动）、
 *     widget 管理链路（allocate → bind → 写库 → configure 启动 → 删除；
 *     原版此链路在 WidgetSetupActivity —— 模块 App 进程 Room 与宿主不互通，
 *     故本模块在宿主进程内完成，picker UI 为宿主 Compose 全屏浮层）、
 *     homeLayout 读写（topOffset/widgetHeight，Room launcher_settings.homeLayoutJson）
 *   - 裁剪：folders、Private Space UI 载体（folders 阶段）
 *   - P6 新增（B6.x，PORTING_GraceLauncher.md）：附属特性接线 ——
 *     ① 桥快照接收（GraceBridgeReceiver：媒体/通知/天气 JSON → 状态流）；
 *     ② 命令上行（媒体控制/通知打开与清除/天气刷新 → 模块 App 进程执行）；
 *     ③ 日程（CalendarRepository 宿主进程直读 + READ_CALENDAR 能力探测降级）；
 *     ④ 图标包（AppRepository.loadApps(iconPackPackages) 渲染装饰）；
 *     ⑤ 通知监听权限探测（无权限 = 媒体/通知组件隐藏，不阻塞主链路）
 *
 * 宿主进程注意：本类在 com.miui.home 进程内运行，AndroidViewModel 持有的
 * Application 即宿主 Application；receiver 注销由 GraceHomeView.onHomeDetached
 * 显式调用 shutdown()（手动构造的 ViewModel 不走 onCleared）。
 */
package com.homemode4miui.grace

import android.app.Application
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyrio.gracelauncher.data.AppRepository
import com.galaxyrio.gracelauncher.data.FavoritesStore
import com.galaxyrio.gracelauncher.data.GraceHomeSettings
import com.galaxyrio.gracelauncher.data.WorkProfileFolderId
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.data.GraceSettingsRepository
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherAppOrder
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.ShortcutRepository
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.media.MediaSessionRepository
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.weather.WeatherState
import com.galaxyrio.gracelauncher.data.weather.WeatherStatus
import com.homemode4miui.bridge.GraceBridge
import com.homemode4miui.bridge.GraceSettingsBridge
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GraceHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val favoritesStore = FavoritesStore(application)
    private val appRepository = AppRepository(application)
    private val settingsRepository = GraceSettingsRepository(application)
    /** P6（B6.2）：日程仓储已迁至 AgendaFeed（2026-11-15 拆分第 2 步）。 */
    /** P6（B6.1/B6.2/B6.3）：桥快照接收（模块 App 进程 → 宿主进程显式广播）。 */
    private val bridgeReceiver = GraceBridgeReceiver(this)
    /** C 组（C3）：设置命令引擎（模块设置页 → 本进程落 Room/SP → 快照回推）。 */
    private val settingsHostEngine: GraceSettingsHostEngine by lazy {
        GraceSettingsHostEngine(application, viewModelScope, ::onSettingsMutated, ::onBridgeMoveWidget,
            ::onBridgeAddWidget, ::onBridgeAddPopupWidget, ::onBridgeShortcutQuery,
            weatherJson = {
                val w = _uiState.value.weather
                if (w.status == WeatherStatus.Disabled) null else GraceBridge.encodeWeatherWire(w)
            },
            onWeatherRefresh = { refreshWeatherForSettings() },
            appCatalog = {
                _uiState.value.apps.map { app ->
                    com.homemode4miui.bridge.GraceSettingsBridge.AppWire(
                        component = app.componentName.flattenToString(),
                        label = app.label,
                        serial = app.userSerial,
                    )
                }
            })
    }
    /**
     * B6.2 天气宿主引擎**已迁至 WeatherFeed**（2026-11-15 拆分第 3 步）。
     * 原先那两个引擎互相引用（设置刷新 ↔ 天气回推快照）靠 by lazy 断开初始化环，
     * 现在由 VM 注入两个 lambda（preferredLocationId / onSnapshotInvalidated）实现同样的解耦。
     */
    /**
     * 上游同步 v1.1.8 S8b：图标渲染管线（moduleResourceContext / ItemIconStore）
     * **已迁至 AppsLoader**（拆分第 8 步）—— 它只被图标装饰链路消费。
     */

    // ---- 设置页 widget 命令入口（moveWidget / addWidget / addPopupWidget 三个 request flow）
    //      已随 widget 域迁至 WidgetSlot（拆分第 5 步）；VM 保留 onBridge* 私有转发，
    //      因为 GraceSettingsHostEngine 的构造参数是方法引用。 ----

    /** hotfix2：设置页"移动小部件"命令 → 桌面进入 widget 编辑模式（原版同进程直改 editingHome）。 */
    private fun onBridgeMoveWidget() = widget.onMoveWidget()

    /** 上游同步补漏：设置页"添加自定义小组件"命令 → 桌面开 WidgetPicker（跨进程 UI 动作）。 */
    private fun onBridgeAddWidget() = widget.onAddWidget()

    /** folders 阶段（hotfix folders2）：设置页"添加新的小组件"命令 → 桌面开 WidgetPicker 弹层模式。 */
    private fun onBridgeAddPopupWidget(ownerKey: String) = widget.onAddPopupWidget(ownerKey)

    private val _uiState = MutableStateFlow(GraceHomeUiState(
        // Room 首读前的瞬时默认（loadInitial 在 init 即触发，正常先于首帧组合到达）
        settings = GraceHomeSettings(),
    ))
    val uiState = _uiState.asStateFlow()

    /** 日程域（2026-11-15 拆分第 2 步）：events / scheduleStatus 与日历失效链的持有者。 */
    private val agenda = AgendaFeed(application, viewModelScope, _uiState)

    /**
     * 设置域（拆分第 7 步）：settings / hidden_apps / folders 三条 Room 观察流、
     * 收藏序、文件夹收藏、图标包面板。全仓的**公共写通道** —— 其他域通过
     * `settings.update(lambda)` 落库（WidgetSlot 的 homeLayout 即走这里）。
     */
    private val settings = SettingsSync(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        settingsRepository = settingsRepository,
        favoritesStore = favoritesStore,
        onIconPacksChanged = { refreshApps() },
        installedIconPacks = { appRepository.iconPacks.installedPacks() },
    )

    /** 天气域（拆分第 3 步）：weather 状态 + 小米天气 provider 直读引擎。 */
    private val weather = WeatherFeed(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        // 首选城市是设置域的数据（宿主 Room 行），以 lambda 注入避免域间持有
        preferredLocationId = { settingsHostEngine.currentWeatherLocationId() },
        onSnapshotInvalidated = { settingsHostEngine.pushSnapshot() },
    )

    /** 桥快照域（拆分第 4 步）：通知/媒体状态、回放抑制三段时间窗、命令上行。 */
    private val bridge = BridgeInbox(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        // 回前台顺带补读日程（后台期间日历可能被改）
        onReturnedToForeground = { refreshAgenda() },
    )

    /** 部件域（拆分第 5 步）：picker 数据、增删/配置、homeLayout 编辑把手。 */
    private val widget = WidgetSlot(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        settingsRepository = settingsRepository,
        updateSettings = { transform -> settings.update(transform) },
    )

    /**
     * 应用装载域（拆分第 8 步）：枚举 / 全量解码 / 图标装饰 / decorateMutex + 代际。
     *
     * 对 ShortcutSync **零直接依赖**：装载前的 pinned 对账、装饰尾部的快捷方式重建
     * 都用 lambda 注入 —— 因为 ShortcutSync 反过来要调本类的 decorate（双向依赖，
     * 见 docs/VIEWMODEL_SPLIT_PLAN.md §4-8）。
     */
    private val appsLoader: AppsLoader = AppsLoader(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        appRepository = appRepository,
        settingsRepository = settingsRepository,
        favoritesStore = favoritesStore,
        onRefreshPinned = { shortcuts.syncPinned(force = true) },
        buildShortcuts = { rows -> shortcuts.buildForRows(rows) },
    )

    /**
     * 快捷方式域（拆分第 6 步）：saved_shortcuts 对账 / 图标解码 / 查询 / 启动 / 系统回调。
     *
     * 注意 decorate 是 lambda 而非 AppsLoader 实例：buildShortcutApps 要调装饰，
     * 而装饰路径（applyItemIconSnapshot）又要回调 buildForRows —— 双向依赖，
     * 只能靠「谁调用谁传参」断开（见 docs/VIEWMODEL_SPLIT_PLAN.md §4-8）。
     */
    private val shortcuts: ShortcutSync = ShortcutSync(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        shortcutRepository = ShortcutRepository(application),
        decorate = { list -> appsLoader.decorate(list) },
        onFavoriteKeyOrphaned = { key -> settings.dropFavoriteKey(key) },
    )

    // ---- Route 访问面（转发到 WidgetSlot，保持 UI 零改动）----
    val widgetProviders = widget.widgetProviders
    val widgetPickerBusy = widget.widgetPickerBusy
    val widgetSetupResults = widget.widgetSetupResults
    val moveWidgetRequests = widget.moveWidgetRequests
    val addWidgetRequests = widget.addWidgetRequests
    val addPopupWidgetRequests = widget.addPopupWidgetRequests

    /**
     * P2：Home 键回桌面请求流（GraceHomeView.onHomeIntent 转发）。路由层收集后
     * 复位两态 UI（关抽屉/搜索/字母 scrub），对齐原版 returnHomeRequests 语义。
     */
    private val _returnHomeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val returnHomeRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _returnHomeRequests

    /**
     * hotfix return-anim：HOME intent 透传流（返回动画恢复）。Intent 原样携带
     * Quickstep 注入的 GestureNavContract extra（gesture_nav_contract_v1）；null =
     * 已在桌面复位路径（onNewIntentWhenAlreadyOnHome，无合约）。路由层收集后转发
     * LauncherAppTransitions.onHomeIntent —— 上游 MainActivity 同构接线。
     */
    private val _homeIntentRequests = MutableSharedFlow<Intent?>(extraBufferCapacity = 4)
    val homeIntentRequests: kotlinx.coroutines.flow.SharedFlow<Intent?> = _homeIntentRequests

    // ---- P4：widget picker 状态 / WidgetController 已迁至 WidgetSlot（拆分第 5 步）----

    // 应用装载域（appLoadJob / iconsJob / 装饰互斥代际 / 壁纸与包变化广播 /
    //  iconStore）已迁至 AppsLoader（拆分第 8 步）。

    init {
        // 装载域（拆分第 8 步）：注册包变化/壁纸广播 + 启动 item_icons 跟随
        appsLoader.start()
        // P6：桥快照广播。hotfix1：必须 EXPORTED —— NOT_EXPORTED 动态 receiver 在
        // Android 13+ 只收同 UID/系统广播，模块进程（异 UID）的显式 setPackage 广播
        // 被系统丢弃（授权后媒体行不出现的根因）。信任面见 GraceBridgeReceiver 头注。
        ContextCompat.registerReceiver(application, bridgeReceiver, GraceBridgeReceiver.intentFilter(),
            ContextCompat.RECEIVER_EXPORTED)
        // notif1：挂载即拉取快照 —— 引擎指纹去重是模块进程持久单例，桌面重启后
        // 重启前的通知/媒体快照不会重发（要等新通知/切歌），必须显式拉取。
        bridge.requestSnapshot()
        // B6.2：天气首装载（本进程直读小米天气 provider；后续 ContentObserver 失效）
        weather.refresh("mount")
        // 设置域（拆分第 7 步）：启动 settings / hidden_apps / folders 三条 Room 观察流
        settings.start()
        // 快捷方式域（拆分第 6 步）：启动 saved_shortcuts 流跟随 + 注册系统 shortcut 回调
        shortcuts.start()
        refreshApps()
        // perf2（功耗/冷启动专项）：shortcut/media 权限探测各含一次【同步 binder 往返】
        // （hasShortcutHostPermission / isNotificationListenerAccessGranted 查询），
        // 原实现串行跑在挂载主线程（GraceHomeView.onHomeAttached 内构造本 VM），
        // 直接计入桌面冷启动关键路径。改为协程延后一拍：首帧后立即执行，两个状态
        // 位到达前 UI 按"无权限"默认渲染，有权限时随后点亮 —— 无视觉回退。
        viewModelScope.launch {
            refreshShortcutAccess()
            refreshMediaAccess()
        }
        refreshAgenda()
        // B6.2：天气首装载已由 init 的 weatherHostEngine.refresh("mount") 承接
        // （本进程直读 provider，不再向模块进程请求）
    }

    /** 补齐「桌面上的快捷方式」（实现见 ShortcutSync：系统 pinned 对账 + 图标解码 + upsert）。 */
    fun syncPinnedShortcuts(force: Boolean = false) = shortcuts.syncPinned(force)

    /** 用户主动移除快捷方式（dismiss + 系统 unpin + 清图标设计，见 ShortcutSync）。 */
    fun removeShortcut(app: LauncherApp) = shortcuts.removeShortcut(app)

    /**
     * 应用装载入口（实现见 AppsLoader：枚举 → 全量解码 → 装饰 → 锁内原子换入）。
     * 包变化广播、图标包切换、挂载首次装载都走这里，内部按代际丢弃过期结果。
     */
    fun refreshApps() = appsLoader.refreshApps()

    /**
     * 原版 AppRepository.launch 对齐：LauncherApps.startMainActivity（宿主即默认桌面）。
     * P5（B5.1）：透传 sourceBounds/options —— AppLaunchTransition 的动画请求
     * （thumbnail scale-up），由 GraceLauncherRoute.launchApp 构造。
     */
    fun launch(app: LauncherApp, sourceBounds: Rect? = null, options: Bundle? = null): Boolean {
        if (app.folderId != null) return false
        // 上游 LauncherViewModel.launch 逐字：快捷方式项走 startShortcut（抽屉里
        // showInAppList 的 pin 快捷方式、弹层快捷方式同路径），不能按主 Activity 启动
        app.shortcut?.let { return launchShortcut(it, sourceBounds, options) }
        return appRepository.launch(app, sourceBounds, options).also { success ->
            if (!success) ModLog.e("GraceHomeViewModel launch failed: ${app.key}", null)
        }
    }

    /** P1 应用信息入口（长按收藏行菜单）；原版走 AppDetails 弹层，P4 恢复。 */
    fun openAppDetails(app: LauncherApp): Boolean = runCatching {
        val context = getApplication<Application>()
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { ModLog.e("GraceHomeViewModel openAppDetails failed: ${app.key}", it) }.isSuccess

    // ---- P4：快捷方式（实现见 ShortcutSync，签名对齐原版 LauncherActions 字段）----

    suspend fun loadShortcuts(app: LauncherApp): ShortcutResult = shortcuts.loadShortcuts(app)

    fun cachedShortcuts(app: LauncherApp): ShortcutResult? = shortcuts.cachedShortcuts(app)

    fun prepareShortcuts(app: LauncherApp) = shortcuts.prepareShortcuts(app)

    fun launchShortcut(
        shortcut: LauncherShortcut,
        sourceBounds: Rect? = null,
        options: Bundle? = null,
    ): Boolean = shortcuts.launchShortcut(shortcut, sourceBounds, options)

    private fun refreshShortcutAccess() = shortcuts.refreshAccess()

    // ---- widget 增删 / 配置（拆分第 5 步：实现见 WidgetSlot）----

    fun loadWidgetProviders() = widget.loadProviders()

    fun addHomeWidget(info: AppWidgetProviderInfo) = widget.addHome(info)

    fun removeHomeWidget() = widget.removeHome()

    fun addPopupWidgetTo(ownerKey: String, info: AppWidgetProviderInfo) =
        widget.addPopupTo(ownerKey, info)

    /** folders 阶段：模块设置页快捷方式代查（实现见 ShortcutSync）。 */
    private fun onBridgeShortcutQuery(json: String) = shortcuts.onBridgeQuery(json)

    // ---- homeLayout 编辑把手 / 配置页（实现见 WidgetSlot）----

    fun configureHomeWidget() = widget.configure()

    fun updateHomeLayoutTopOffset(offset: Float) = widget.updateTopOffset(offset)

    fun updateHomeLayoutWidgetHeight(height: Int) = widget.updateWidgetHeight(height)

    fun updateHomeOrder(order: List<String>) = widget.updateHomeOrder(order)

    /**
     * P11b 一次性迁移：把 FavoritesStore 里保存的收藏顺序并入 `homeLayout.homeOrder`。
     *
     * 背景：上游 1.2.2+ 首页顺序是**单一来源** homeOrder（区块 / 类别 / 收藏都是其中条目）；
     * 模块此前双源 —— FavoritesStore(SP) 存收藏顺序，homeOrder 只存区块顺序。
     * 本函数幂等（homeOrder 非空即跳过），且写失败不影响旧读取路径（收藏仍从 Store 读）。
     */
    fun migrateFavoritesToHomeOrder() {
        val state = _uiState.value
        if (!state.homeReady) return
        if (!state.settings.homeLayout.homeOrder.isNullOrEmpty()) return
        if (state.favoriteOrder.isEmpty()) return
        val blocks = buildList {
            if (state.settings.clockEnabled || state.settings.calendarAgenda) {
                add(com.galaxyrio.gracelauncher.data.GraceWidget.ClockCalendar.key)
            }
            if (state.settings.homeLayout.hasWidget) add("widget:${state.settings.homeLayout.widgetId}")
            if (state.settings.mediaPlayer) add(com.galaxyrio.gracelauncher.data.GraceWidget.Media.key)
        }
        val order = (blocks + state.favoriteOrder).distinct()
        ModLog.i("favorites→homeOrder migrate: blocks=" + blocks.size
            + " favorites=" + state.favoriteOrder.size + " total=" + order.size)
        updateHomeOrder(order)
    }

    fun updatePopupWidget(
        ownerKey: String,
        layout: com.galaxyrio.gracelauncher.data.HomeLayout,
        save: Boolean,
    ) = widget.updatePopupWidget(ownerKey, layout, save)

    /** 原版 clockAppKey == null 的默认语义：Android 标准 SHOW_ALARMS。 */
    fun openClock(): Boolean = runCatching {
        val context = getApplication<Application>()
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { ModLog.e("GraceHomeViewModel openClock failed", it) }.isSuccess

    // ---- 收藏 / 设置写通道 / 图标包（实现见 SettingsSync）----

    fun toggleFavorite(app: LauncherApp) = settings.toggleFavorite(app)

    fun reorderFavorites(keys: List<String>) = settings.reorderFavorites(keys)

    suspend fun setFolderFavorites(selection: Map<String, Boolean>): Boolean =
        settings.setFolderFavorites(selection)

    /** 设置页命令入口（落库与快照回推都在引擎协程内，onReceive 主线程零阻塞）。 */
    fun onSettingsCommand(cmd: String?, arg1: String?, arg2: String?, json: String?) {
        settingsHostEngine.handle(cmd, arg1, arg2, json)
    }

    /** 引擎落库回调（设置行走 Room 观察流自动生效，这里补 SP 面收藏序）。 */
    private fun onSettingsMutated() = settings.onMutated()

    fun updateSettings(transform: (GraceHomeSettings) -> GraceHomeSettings) = settings.update(transform)

    /** Home 键回桌面（GraceHomeView.onHomeIntent 转发）：复位抽屉/搜索两态。 */
    fun requestReturnHome() {
        // hotfix1：hasMediaAccess 是启动时的一次性探测 —— 授权发生在桌面运行期，
        // Home 键回桌面是唯一稳定的刷新点（面板开关行 summary 依赖此状态）
        refreshMediaAccess()
        _returnHomeRequests.tryEmit(Unit)
    }

    /** hotfix return-anim：复位 + HOME intent 透传（上游 MainActivity.onNewIntent 同构）。 */
    fun onHomeIntent(intent: Intent?) {
        requestReturnHome()
        _homeIntentRequests.tryEmit(intent)
    }

    /** P2：搜索结果打开应用后记录最近使用（原版 LauncherViewModel.launchSearchApp 语义）。 */
    fun recordSearchApp(appKey: String) {
        updateSettings { it.copy(searchSettings = it.searchSettings.recordApp(appKey)) }
    }

    // ================= P6（B6.x）附属特性 =================

    /** B6.1：通知监听权限探测（实现见 BridgeInbox：模块 App 的 listener 组件，公开 API 任意进程可查）。 */
    fun refreshMediaAccess() = bridge.refreshMediaAccess()

    /**
     * B6.2：日程刷新（拆分第 2 步 —— 实现搬到 AgendaFeed：权限探测 / 静默刷新 /
     * ContentObserver 失效链 / 查询降级全在那边）。本方法保留为对外签名。
     */
    fun refreshAgenda() = agenda.refresh()

    /**
     * B6.2 权限降级：宿主 manifest 不可改、无法在宿主进程内弹 READ_CALENDAR 授权
     * ——"Connect calendar" 降级为打开系统日历应用（实现见 AgendaFeed）。
     */
    fun openCalendarApp(): Boolean = agenda.openCalendarApp()

    // ---- 桥快照摄入（GraceBridgeReceiver 转发；实现见 BridgeInbox）----

    fun onBridgeMedia(media: NowPlaying?, artChanged: Boolean) = bridge.onMedia(media, artChanged)

    fun onBridgeNotifications(notifications: Map<String, List<AppNotification>>) =
        bridge.onNotifications(notifications)

    /** 宿主桌面退后台 / 回前台（实现在 BridgeInbox：后台只滚动 pending，回前台一次性应用 + 700ms 宽限）。 */
    fun onHostPaused() = bridge.onHostPaused()

    fun onHostResumed() = bridge.onHostResumed()

    // ---- 命令上行（宿主 → 模块 App 进程；命令在持有 token 的进程内执行）----

    /** B6.1：媒体控制（上一首/播放暂停/下一首/打开播放器）。 */
    fun controlMedia(sessionId: String, command: MediaCommand) = bridge.controlMedia(sessionId, command)

    /** B6.1：本地划走（原版 dismiss 语义：只影响本桌面显示，不取消系统通知）。 */
    fun dismissMedia(sessionId: String, revision: Long): Boolean = bridge.dismissMedia(sessionId, revision)

    /** B6.3：打开通知（contentIntent 由模块 App 进程以 BAL 选项发送）。 */
    fun openNotification(key: String, revision: Long): Boolean = bridge.openNotification(key, revision)

    /** B6.3：清除通知（系统侧 cancel，原版"等 onNotificationRemoved"语义经快照回流）。 */
    fun dismissNotification(key: String, revision: Long): Boolean = bridge.dismissNotification(key, revision)

    /** B6.2：天气刷新请求（实现见 WeatherFeed；宿主进程内直读小米天气 provider）。 */
    fun refreshWeather() = weather.refresh("manual")

    /** 设置页「刷新天气」命令入口（reason 独立，排障时能和手动刷新区分开）。 */
    private fun refreshWeatherForSettings() = weather.refresh("settings")

    // ---- B6.4：图标包选择（面板数据 + 单选落库）----

    /** 图标包面板数据（实现见 SettingsSync：null = 加载中）。 */
    val iconPacks = settings.iconPacks

    fun loadIconPacks() = settings.loadIconPacks()

    /** 单选简化（原版多选 fallback 顺序随 C 组设置页恢复）：null = 系统图标。 */
    fun setIconPack(pkg: String?) = settings.setIconPack(pkg)

    /** GraceHomeView.onHomeDetached 显式调用（手动构造的 ViewModel 不走 onCleared）。 */
    fun shutdown() {
        // 装载域：取消装载/图标流 + 解绑包变化/壁纸广播（实现见 AppsLoader.dispose）
        appsLoader.dispose()
        // 设置域：取消三条 Room 观察流（实现见 SettingsSync.dispose）
        settings.dispose()
        // 快捷方式域：取消流跟随 + 解绑系统回调 + 关闭仓储（实现见 ShortcutSync.dispose）
        shortcuts.dispose()
        // v2.9.126：日程失效监听解绑（实现已迁 AgendaFeed，dispose 负责 job + observer）
        agenda.dispose()
        // B6.2：天气引擎停摆（反注册 ContentObserver；VM 每次 attach 新建，dispose 即终态）
        weather.dispose()
        // 桥快照域：取消宽限期收尾 job + 清 pending
        bridge.dispose()
        // 桥命令接收器（仍在 VM 内注册，见 init）
        runCatching { getApplication<Application>().unregisterReceiver(bridgeReceiver) }
            .onFailure { ModLog.e("GraceHomeViewModel unregister bridge receiver failed", it) }
    }
}
