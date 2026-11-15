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
import android.content.IntentFilter
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
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.PopupItem
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /** 上游同步 v1.1.8 S8b：图标渲染管线（Grace 按钮 artwork 面专用；settings 传入逐次构造）。
     *  context 必须是【模块资源 Context】：ItemIconStore/GraceButtonIcon 解析模块
     *  drawable/string——宿主 MiuiResources 无模块 R id（NotFound）或同 ID 错位
     *  （DualResources 只兜 string 族，drawable 错位静默换图）。filesDir 指向模块
     *  数据目录仅影响 image kind（当前设置侧 import 降级禁用，不可达）。 */
    private val moduleResourceContext: android.content.Context = runCatching {
        application.createPackageContext("com.homemode4miui", android.content.Context.CONTEXT_IGNORE_SECURITY)
    }.getOrElse { application }
    private val iconStore = com.galaxyrio.gracelauncher.data.icons.ItemIconStore(moduleResourceContext,
        com.galaxyrio.gracelauncher.data.icons.IconPackRepository(moduleResourceContext))

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
        updateSettings = { transform -> updateSettings(transform) },
    )

    /**
     * 快捷方式域（拆分第 6 步）：saved_shortcuts 对账 / 图标解码 / 查询 / 启动 / 系统回调。
     *
     * 注意 decorate 是 lambda 而非 AppsLoader 实例：buildShortcutApps 要调装饰，
     * 而装饰路径（applyItemIconSnapshot）又要回调 buildForRows —— 双向依赖，
     * 只能靠「谁调用谁传参」断开（见 docs/VIEWMODEL_SPLIT_PLAN.md §4-8）。
     */
    private val shortcuts = ShortcutSync(
        application = application,
        scope = viewModelScope,
        uiState = _uiState,
        shortcutRepository = ShortcutRepository(application),
        decorate = { apps -> decorateApps(apps) },
        onFavoriteKeyOrphaned = { key -> dropFavoriteKey(key) },
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

    private var appLoadJob: Job? = null
    /** 装载失败自愈重试计数：成功装载后归零，连败不重试（防失败-重试循环）。 */
    private var appLoadRetryCount = 0
    /** P3：设置流常驻收集（Room 行变化驱动 settings）；shutdown 显式取消 */
    private var settingsJob: Job? = null
    /** hotfix7：隐藏应用键流（Room hidden_apps 表；设置页 SET_HIDDEN_APPS 落库后实时到达） */
    private var hiddenAppsJob: Job? = null
    /** folders 阶段：文件夹流（Room folders 表 + popup items 成员 JSON，原版 snapshots 同构） */
    private var foldersJob: Job? = null
    private var iconsJob: Job? = null
    /** 壁纸跟随失效源（模块侧根因修复）：Grace 按钮/themed 图标的光栅化配色在 VM 层
     *  预渲染成位图，壁纸变化后必须重渲染才跟得上主题。双通道 bump（与组合层
     *  rememberWallpaperVersion 同语义）：OnColorsChangedListener（AOSP 对同色系
     *  壁纸去重不回调）+ ACTION_WALLPAPER_CHANGED 广播（每次设壁纸必发）。 */
    private val wallpaperRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val wallpaperColorsListener = android.app.WallpaperManager.OnColorsChangedListener { _, _ ->
        wallpaperRevision.value = wallpaperRevision.value + 1
    }
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_WALLPAPER_CHANGED) wallpaperRevision.value = wallpaperRevision.value + 1
        }
    }
    /** 上游同步补漏：item_icons 快照（iconsJob 主线程写，refreshApps/装饰读）。
     *  上游 LauncherViewModel.itemsSnapshot.icons 的宿主同构。 */
    private var iconsSnapshot: Map<String, com.galaxyrio.gracelauncher.data.ItemIcon> = emptyMap()
    /** 内存列表（apps + folderApps）当前已按哪个 icons 快照装饰完成的标记；
     *  null = 未装饰/上次装饰失败（下次快照到达强制重试）。 */
    private var itemsDecoratedWith: Map<String, com.galaxyrio.gracelauncher.data.ItemIcon>? = null
    /** 上次重装饰时的主题戳（壁纸 revision + 主题设置指纹，见 decorationThemeStampOf）；
     *  null = 从未记录。戳变化 = 光栅化配色来源变化，iconsJob 强制重装饰。 */
    private var decorationThemeStamp: String? = null
    /** 「读内存列表 → 装饰 → 原子换入」串行化：iconsJob 重装饰与 refreshApps 收尾互斥。 */
    private val decorateMutex = Mutex()
    /**
     * 应用装载代际（每次 refreshApps 自增）。
     *
     * 根因（2026-10-28 装机实锤：重启桌面后有概率"收藏有图标、抽屉全是字母占位"）：
     * 两段式装载的 phase1 把 isLoadingApps 置 false 后，iconsJob 的快照重装饰
     * （applyItemIconSnapshot）就能进来 —— 它读 [LauncherUiState.apps]（此刻是只解码了
     * 收藏子集的 phase1 partial）、在 IO 上装饰、再整体写回。若它的读发生在 phase2
     * 换入全量带图标列表之前、写发生在之后，就会把全量结果覆盖回 partial：
     * 收藏子集有图标、抽屉/搜索其余全是字母占位，且 itemsDecoratedWith 已被标记成
     * "已装饰"，此后不再自愈（要等图标设计/壁纸/包变化触发 refreshApps）。
     * 修复：decorateMutex 内做「读 → 校验代际 → 写」，装载换入同样在锁内进行，
     * 代际变化即说明有更新的一次装载接管，旧装饰结果直接丢弃。
     *
     * 2026-11-14：两段式已回归单段装载（phase1 不复存在，上述覆盖窗口自然消失），
     * 但代际 + 互斥仍保留 —— iconsJob 重装饰与包变化刷新并发时仍是同一类竞态。
     */
    @Volatile
    private var appLoadGeneration = 0
    /** P6：图标包开关变化 → 重刷应用列表（渲染装饰跟随包顺序）。 */
    private var lastIconPacks: List<String>? = null

    // ---- 桥快照摄入节流（v2.9.125-notif-burst）：已迁至 BridgeInbox（拆分第 4 步）----
    // 三个时间窗（80ms 合并 / 后台 pending / 回前台 700ms 宽限）与相关字段全部在新类内，
    // 不要拆散 —— 它们共同保证"冻结解冻后不回放历史通知"这一行为。

    // ---- 日程失效链（v2.9.126-agenda-live）：已迁至 AgendaFeed（拆分第 2 步）----

    private companion object {
        /** 回桌面触发的 pinned 对账最小间隔（系统推送走 force=true，不受限）。 */
        const val PINNED_SYNC_MIN_INTERVAL_MS = 5_000L
    }
    // 审计修缮（2026-10-16）：删除 iconPacksDecorating —— 全仓无置位（承诺的
    // "刷新完成后置 true" 逻辑不存在）也无收集，上游装饰中提示的移植半成品。

    /** 与原版 packageReceiver 同构：包变化（安装/卸载/更新/变更）触发应用列表刷新。 */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            refreshApps()
        }
    }

    init {
        ContextCompat.registerReceiver(application, packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // P6：桥快照广播。hotfix1：必须 EXPORTED —— NOT_EXPORTED 动态 receiver 在
        // Android 13+ 只收同 UID/系统广播，模块进程（异 UID）的显式 setPackage 广播
        // 被系统丢弃（授权后媒体行不出现的根因）。信任面见 GraceBridgeReceiver 头注。
        ContextCompat.registerReceiver(application, bridgeReceiver, GraceBridgeReceiver.intentFilter(),
            ContextCompat.RECEIVER_EXPORTED)
        // notif1：挂载即拉取快照 —— 引擎指纹去重是模块进程持久单例，桌面重启后
        // 重启前的通知/媒体快照不会重发（要等新通知/切歌），必须显式拉取。
        bridge.requestSnapshot()
        // 壁纸跟随失效源注册（见 wallpaperRevision 注释）；shutdown 侧解绑
        runCatching {
            android.app.WallpaperManager.getInstance(application)
                .addOnColorsChangedListener(wallpaperColorsListener,
                    android.os.Handler(android.os.Looper.getMainLooper()))
        }
        ContextCompat.registerReceiver(application, wallpaperReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // B6.2：天气首装载（本进程直读小米天气 provider；后续 ContentObserver 失效）
        weather.refresh("mount")
        // P3：Room 设置接管 —— SP seed 迁移（一次性）+ 行变化流常驻跟随
        settingsJob = viewModelScope.launch {
            val initial = settingsRepository.loadInitial()
            _uiState.update { it.copy(settings = initial) }
            settingsRepository.observeSettings().collect { observed ->
                // S8b 诊断：宿主 settings 流（graceButton.enabled 跟随验证）
                com.homemode4miui.util.ModLog.i(
                    "GraceSettings observe graceButton.enabled=" + observed.graceButton.enabled +
                        " tap=" + observed.graceButton.tap.action.name +
                        " longPress=" + observed.graceButton.longPress.action.name +
                        " doubleTap.enabled=" + observed.graceButton.doubleTap.enabled)
                _uiState.update { it.copy(settings = observed) }
                // B6.4：图标包顺序变化 → 重刷（设置流驱动，幂等）
                if (lastIconPacks != null && observed.enabledIconPackPackages != lastIconPacks) refreshApps()
            }
        }
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
        // hotfix7：隐藏应用键跟随（与 LauncherSettingsRepository 同源 DAO；
        // 观察失败不阻塞主链路 —— 抽屉退化为不过滤）
        hiddenAppsJob = viewModelScope.launch {
            try {
                val flow = LauncherDatabase.getInstance(application).settingsDao().observeHiddenApps()
                flow.collect { keys ->
                    _uiState.update { it.copy(hiddenAppKeys = keys.toSet()) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel observeHiddenApps failed", error)
            }
        }
        // folders 阶段：文件夹跟随（成员关系存于 popup items JSON，owner = "folder:<id>"，
        // 原版 LauncherSettingsRepository.snapshots :18-33 逐字映射；观察失败不阻塞主链路）
        foldersJob = viewModelScope.launch {
            try {
                val database = LauncherDatabase.getInstance(application)
                kotlinx.coroutines.flow.combine(
                    database.settingsDao().observeFolders(),
                    database.itemsDao().popups(),
                ) { folderRows, popupRows ->
                    // 上游同步 v1.1.8 S8b：popups 全量一并透出（Triple，openFirstItem 弹层版消费）
                    val contents = popupRows.associate { it.ownerKey to PopupItem.decode(it.itemsJson) }
                    // hotfix folders2：widget 项单独透出（文件夹弹层渲染用；appKeys 仍不含 widget）
                    val widgets = contents.mapNotNull { (ownerKey, items) ->
                        val layouts = items.mapNotNull { it.widget }
                        if (layouts.isEmpty() || !ownerKey.startsWith("folder:")) null
                        else ownerKey.removePrefix("folder:") to layouts
                    }.toMap()
                    val folders = folderRows.map { entry ->
                        LauncherFolder(
                            id = entry.id,
                            name = entry.name,
                            appKeys = contents["folder:${entry.id}"].orEmpty().filter { it.widget == null }.map { it.key },
                            placement = FolderPlacement.decode(entry.placement),
                            appListAtBottom = entry.appListAtBottom,
                            // 上游同步 v1.1.9：文件夹成员派生隐藏
                            autoHideApps = entry.autoHideApps,
                            // 上游同步 1.2.0：应用堆（主页 AppStack 渲染分支读这个字段）
                            isAppStack = entry.isAppStack,
                        )
                    }
                    Triple(folders, widgets, contents)
                }.collect { (folders, widgets, popups) -> _uiState.update {
                    it.copy(folders = folders, folderWidgets = widgets, popups = popups)
                } }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel observeFolders failed", error)
            }
        }
        // 上游同步 v1.1.8 S8b：图标设计器产出跟随（GraceButton design + artwork 面）
        // 模块侧根因修复：combine 壁纸 revision + 设置流 —— 光栅化配色（WallpaperColors
        // 种子）或主题设置变化时，Grace 按钮 artwork 重渲染 + themed 图标强制重装饰。
        iconsJob = viewModelScope.launch {
            try {
                val database = LauncherDatabase.getInstance(application)
                kotlinx.coroutines.flow.combine(
                    database.itemsDao().icons(),
                    settingsRepository.observeSettings(),
                    wallpaperRevision,
                ) { rows, _, _ -> rows }.collect { rows ->
                    val icons = rows.mapNotNull { row ->
                        com.galaxyrio.gracelauncher.data.ItemIcon.decode(row.iconJson)?.let { row.itemKey to it }
                    }.toMap()
                    // 上游 itemsSnapshot 同构（LauncherViewModel.kt:427 在 collect 内落快照字段）：
                    // 局部 icons 必须同步到 iconsSnapshot —— decorateApps/decorateFolders 的
                    // 两条消费链（refreshApps 冷启动 + 本 collect 即时重装饰）都从该字段读
                    // per-item 选择。漏赋值 = 单个图标/文件夹图标设计永不生效（装机实锤）。
                    iconsSnapshot = icons
                    // Grace 按钮位图随 icons 流产出（上游 LauncherViewModel.kt:450-451 同构）：
                    // ItemIconStore.apply 走 isGraceButton 分支渲染 256px artwork。
                    // 图标渲染设置统一走 currentIconSettings（补 iconDesign/iconPackPackages，
                    // 与 applyDesign 全局面 + theme kind 语义对齐）。
                    val buttonApp = try {
                        val iconSettings = currentIconSettings()
                        // moduleResourceContext：GraceButtonIcon.item 内部 getString
                        // (settings_grace_button) 必须解析模块资源——宿主 Application 的
                        // MiuiResources 无模块 R id（Resources$NotFoundException 实锤，
                        // 与 GraceHomeRoot LocalContext 覆盖同族问题，VM 非组合代码需手动过桥）。
                        iconStore.apply(
                            com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.item(moduleResourceContext),
                            com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.choice(icons[com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.key]),
                            iconSettings,
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ModLog.e("GraceHomeViewModel renderGraceButtonIcon failed", error)
                        null
                    }
                    // 上游同步补漏（LauncherViewModel.kt:305-310 reload 语义）：快照变化 → 内存
                    // 列表重装饰（设计器保存后桌面立即生效，不重查系统；refreshApps 侧同链路
                    // 覆盖冷启动/包变化路径）。去重 + 装载中让路：
                    // ① 快照与 itemsDecoratedWith 相同 = 内存列表已按该快照装饰过，跳过
                    //   （Room 流可能在失效追踪器初始化等场景重发；Grace 按钮 artwork 不受影响，
                    //   上方仍无条件产出）；
                    // ② isLoadingApps = refreshApps 在途，其 decorateApps 会读到刚落的最新
                    //   快照，无需（也不能）在此并发重装饰——否则读到的旧/空 apps 会在
                    //   update 时覆盖装载结果（空列表闪断竞态，v2.9.51 起存在）。
                    // ③ 模块侧补强：主题戳（壁纸 revision + 主题设置）变化也强制重装饰 ——
                    //   themed 图标/Grace 按钮的光栅化配色随壁纸取色种子即时刷新。
                    val iconSettingsForStamp = currentIconSettingsOrNull()
                    if (iconSettingsForStamp != null) {
                        val stamp = decorationThemeStampOf(iconSettingsForStamp)
                        if (icons != itemsDecoratedWith || stamp != decorationThemeStamp) {
                            decorationThemeStamp = stamp
                            applyItemIconSnapshot()
                        }
                    }
                    _uiState.update { it.copy(itemIcons = icons, graceButtonApp = buttonApp) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel observeItemIcons failed", error)
            }
        }
        // B6.2：天气首装载已由 init 的 weatherHostEngine.refresh("mount") 承接
        // （本进程直读 provider，不再向模块进程请求）
    }

    /** 补齐「桌面上的快捷方式」（实现见 ShortcutSync：系统 pinned 对账 + 图标解码 + upsert）。 */
    fun syncPinnedShortcuts(force: Boolean = false) = shortcuts.syncPinned(force)

    /** 用户主动移除快捷方式（dismiss + 系统 unpin + 清图标设计，见 ShortcutSync）。 */
    fun removeShortcut(app: LauncherApp) = shortcuts.removeShortcut(app)

    fun refreshApps() {
        // force：包变化/挂载/图标包切换后必须按系统 pinned 重新对账；
        // 回桌面（GraceHomeView）走默认节流，这里 force 正好覆盖 VM 首次装载那一次。
        syncPinnedShortcuts(force = true)
        appLoadJob?.cancel()
        // 代际自增必须在启动新 job 之前（旧 job 可能正在锁内换入，读到新代际即让位）
        val generation = ++appLoadGeneration
        appLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true, appLoadFailed = false) }
            try {
                // B6.4：图标包跟随设置（装饰在 loadApps 内完成；包加载失败自动回退系统图标）
                val packs = _uiState.value.settings.enabledIconPackPackages
                lastIconPacks = packs
                // 上游同步补漏（LauncherViewModel.kt:443 decorate 逐字语义）：
                // 应用图标设计器产出（item_icons）+ 全局面设计 + 主题化在入库前套用。
                // 快照在装饰前取定：装载期间 icons 流前进的场景由尾部补偿轮处理。
                val snapshotAtDecorate = iconsSnapshot
                // 单段装载（2026-11-14 User 实锤：重启手机后收藏图标正常、抽屉整屏字母占位）。
                //
                // 历史包袱：曾为"时钟/文件夹先出、应用晚 1-2 秒到"做两段式 —— phase1 只解
                // 收藏子集立即换入、phase2 后台解全量。两段各起一批 getBadgedIcon，冷启动
                // 在 MIUI IconCustomizer 的主题资源锁上互相踩踏 + 各自吃一份解码超时：
                // 收藏子集（20 个）先拿到锁、正常出图；全量（200+）撞上超时被整批判死 →
                // 抽屉永久骨架态（字母占位）。诊断实锤"收藏正常 / 抽屉全占位"正是这个形态。
                //
                // 回归单段：枚举 → 全量解码 → 装饰 → 一次换入。首帧晚 1-2 秒（时钟、文件夹、
                // 收藏一起出现），代价可接受；换来抽屉图标不再整批丢失。
                val inventory = appRepository.enumerateApps(packs)
                ModLog.i("refreshApps: enumerated=" + inventory.apps.size
                        + " at=" + android.os.SystemClock.uptimeMillis())
                var full = inventory.withIcons(inventory.apps.map(LauncherApp::key).toSet())
                ModLog.i("refreshApps: icons done at=" + android.os.SystemClock.uptimeMillis()
                        + " missing=" + full.count { it.icon == null } + "/" + full.size)
                // 解码瞬时失败自愈：MIUI 系统刚启动时 PackageManager 资源未就绪，
                // getBadgedIcon 可能整批抛错（解码器内 runCatching 静默为 null）。
                // 缺失超过 5% 时重试一次（解码是纯函数，重复调用无副作用，第二轮已暖
                // IconCustomizer 缓存，明显更快），仍失败则如实记录，便于日志定位。
                run {
                    val missing = full.filter { it.icon == null }.map(LauncherApp::key)
                    // 无条件打点：图标缺失是"抽屉字母占位"的唯一判据，缺 1 个也值得留痕
                    if (missing.isNotEmpty()) {
                        com.homemode4miui.util.ModLog.i(
                            "refreshApps: icon missing=" + missing.size + "/" + full.size)
                    }
                    if (missing.isNotEmpty() && missing.size * 20 >= full.size) {
                        com.homemode4miui.util.ModLog.i(
                            "refreshApps: retry icon decode missing=" + missing.size + "/" + full.size)
                        full = inventory.withIcons(missing.toSet(), parallelism = 2)
                        com.homemode4miui.util.ModLog.i(
                            "refreshApps: after retry missing=" + full.count { it.icon == null } +
                                "/" + full.size)
                    }
                }
                // 开机后首次装载的兜底（2026-11-02 User 实测："再重启一次桌面图标就回来"）：
                // 系统刚开机时内存紧张 + PackageManager/主题资源未就绪，整批解码失败；
                // 此时不折腾用户手动重启桌面，按 6s/15s 退避自动整表重装，最多两轮。
                val missingAfterRetry = full.count { it.icon == null }
                if (missingAfterRetry * 5 >= full.size && appLoadRetryCount < 2) {
                    appLoadRetryCount += 1
                    val delayMs = if (appLoadRetryCount == 1) 6_000L else 15_000L
                    ModLog.i("refreshApps: schedule auto-retry #" + appLoadRetryCount
                            + " in " + delayMs + "ms (missing=" + missingAfterRetry + "/" + full.size + ")")
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(delayMs)
                        if (generation == appLoadGeneration && !_uiState.value.isLoadingApps) refreshApps()
                    }
                } else if (missingAfterRetry == 0) {
                    appLoadRetryCount = 0
                }
                val apps = decorateApps(full)
                ModLog.i("refreshApps: decorated at=" + android.os.SystemClock.uptimeMillis()
                        + " iconMissing=" + apps.count { it.icon == null } + "/" + apps.size)
                val folderApps = decorateFolders(_uiState.value.folders, snapshotAtDecorate)
                // 原版语义：favoritesFor 负责首装默认收藏 / 旧格式迁移 / 去重；
                // 之后包变化刷新只补 key，不打乱用户已保存的顺序。
                val order = favoritesStore.favoritesFor(apps)
                // 上游同步 v1.1.8 S8a：工作资料枚举（无 managed profile 时零开销静默跳过）。
                val workProfiles = appRepository.workProfiles()
                val workProfileApps = if (workProfiles.isNotEmpty()) {
                    appRepository.loadWorkApps(workProfiles, packs)
                } else emptyList()
                // 锁内换入（与 applyItemIconSnapshot 串行，见 appLoadGeneration 注释）
                decorateMutex.withLock {
                    if (generation != appLoadGeneration) return@launch
                    _uiState.update {
                        it.copy(apps = apps, folderApps = folderApps, favoriteOrder = order,
                            isLoadingApps = false, homeReady = true,
                            workProfiles = workProfiles, workProfileApps = workProfileApps)
                    }
                }
                // appLoadRetryCount 的归零放在 missing 判定分支（本文件上部）：
                // 只有"零缺失"的成功装载才清零，否则退避重试计数会被每次装载重置成 0。
                itemsDecoratedWith = snapshotAtDecorate
                // 装载期间 icons 流前进过（iconsJob 因 isLoadingApps 让路）：
                // 以最新快照补一轮内存重装饰，否则新快照要等下次 emission 才生效。
                if (iconsSnapshot != snapshotAtDecorate) applyItemIconSnapshot()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel loadApps failed", error)
                // 已有列表（上一轮装载结果）时不得置 appLoadFailed —— 那会让
                // favorites/appListApps 双双空化，比"图标未解出"严重得多。
                val hasList = _uiState.value.apps.isNotEmpty()
                _uiState.update { it.copy(isLoadingApps = false, appLoadFailed = !hasList, homeReady = true) }
                // 半装载自愈：枚举/解码中途抛错会留下旧列表，且没有任何兜底能唤醒 ——
                // 延迟重试一次（仅一次，避免失败-重试循环）。
                if (hasList && appLoadRetryCount < 1 && generation == appLoadGeneration) {
                    appLoadRetryCount += 1
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (generation == appLoadGeneration && !_uiState.value.isLoadingApps) refreshApps()
                    }
                }
            }
        }
    }

    /**
     * 用当前 iconsSnapshot 重装饰内存中的应用/文件夹图标列表（不重查系统），原子换入。
     * icons 快照到达（iconsJob）与 refreshApps 装载尾部补偿共用；装载在途时直接让路
     * （在途 refreshApps 的 decorateApps 会读到刚落的最新快照）。成功后落 itemsDecoratedWith
     * 标记；失败不落（下次快照到达强制重试）。
     */
    /** 主题戳：光栅化配色的全部来源指纹（壁纸取色 revision + 主题设置 + 夜间模式）。
     *  任一变化 = ItemIconStore.themeColors/dynamicColors 会产出不同配色 → 强制重装饰。 */
    private fun decorationThemeStampOf(settings: com.galaxyrio.gracelauncher.data.LauncherSettings): String {
        val nightMode = getApplication<Application>().resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return wallpaperRevision.value.toString() + "|" + settings.useDynamicColors + "|" + settings.themeColor +
            "|" + settings.darkMode.name + "|" + settings.paletteStyle.name + "|" +
            settings.enabledIconPackPackages + "|" + nightMode
    }

    /** currentIconSettings 的容错版（主题戳计算用；失败返回 null 跳过本次重装饰判定）。 */
    private suspend fun currentIconSettingsOrNull(): com.galaxyrio.gracelauncher.data.LauncherSettings? =
        runCatching { currentIconSettings() }.getOrNull()

    private suspend fun applyItemIconSnapshot(): Boolean {
        if (_uiState.value.isLoadingApps) return false
        return decorateMutex.withLock {
            try {
                val generation = appLoadGeneration
                val state = _uiState.value
                val apps = decorateApps(state.apps)
                val folderApps = decorateFolders(state.folders, iconsSnapshot)
                // v2.9.154：快捷方式同样重装饰（图标设计器改快捷方式图标后即时生效，
                // 不必等下一次 refreshApps / 重开抽屉）
                val (shortcutApps, shownKeys) = runCatching {
                    val rows = LauncherDatabase.getInstance(getApplication()).itemsDao().readShortcuts()
                    shortcuts.buildForRows(rows)
                }.getOrElse { state.savedShortcutApps to state.shownShortcutKeys }
                // 装饰期间新一次装载接管（代际前进 / isLoadingApps 已置位）：本轮基于旧
                // apps 的结果直接丢弃 —— 写回去会把在途装载的换入结果盖成旧快照的骨架。
                if (generation != appLoadGeneration || _uiState.value.isLoadingApps) return@withLock false
                _uiState.update {
                    it.copy(apps = apps, folderApps = folderApps,
                        savedShortcutApps = shortcutApps, shownShortcutKeys = shownKeys)
                }
                itemsDecoratedWith = iconsSnapshot
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel applyItemIconSnapshot failed", error)
                false
            }
        }
    }

    // ---- 上游同步补漏：应用图标装饰链（LauncherViewModel.kt:430/443 + LauncherSettingsRepository 主题三参同源）----

    /**
     * 图标渲染设置（上游 iconSettings = _uiState.value.settings 的宿主同构）：
     * 主题三参从 Room 行直取（与设计器预览同源），iconDesign = 全局面「全部」页
     * 设计（Room iconDesignJson），iconPackPackages = theme kind 跟随的主题包。
     */
    private suspend fun currentIconSettings(): com.galaxyrio.gracelauncher.data.LauncherSettings {
        val row = LauncherDatabase.getInstance(getApplication()).settingsDao().readSettings()
        return com.galaxyrio.gracelauncher.data.LauncherSettings(
            useDynamicColors = row?.useDynamicColors ?: true,
            themeColor = row?.themeColor ?: com.galaxyrio.gracelauncher.data.LauncherSettings.DEFAULT_THEME_COLOR,
            darkMode = row?.darkMode
                ?.let { runCatching { com.galaxyrio.gracelauncher.data.ThemeMode.valueOf(it) }.getOrNull() }
                ?: com.galaxyrio.gracelauncher.data.ThemeMode.System,
            iconDesign = row?.iconDesignJson?.let(com.galaxyrio.gracelauncher.data.ItemIcon::decode),
            iconPackPackages = com.galaxyrio.gracelauncher.data.GraceHomeSettings.decodeStringList(row?.iconPackPackagesJson),
        )
    }

    /**
     * 应用图标装饰（上游 LauncherViewModel.decorate 逐字语义：applyDesign(app,
     * icons[key], settings.iconDesign, settings, themedIcons)）。快速路径：无自定义
     * 设计 + 无全局面 + 未开主题化时零渲染（宿主进程省 200+ 次 144px 位图渲染）。
     */
    private suspend fun decorateApps(apps: List<LauncherApp>): List<LauncherApp> {
        val icons = iconsSnapshot
        val iconSettings = currentIconSettings()
        val themed = com.galaxyrio.gracelauncher.data.LauncherPreferences(
            getApplication<Application>()).themedIcons
        if (!themed && icons.isEmpty() && iconSettings.iconDesign == null) return apps
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            apps.map { app ->
                runCatching {
                    val special = icons[app.key]
                    // 骨架应用（系统图标解码失败/未解出）：无 per-item 选择 + 无全局面设计
                    // 时直接跳过 —— 否则 systemDrawable 兜底会对 200+ 骨架触发全量重渲染
                    // （风暴复现）；解码成功的应用走下面正常装饰。
                    // 宿主性能适配（装机实锤 v2.9.51 后每次重启桌面字母条滑动卡顿）：
                    // 无 per-item 选择 + 无全局面设计 + 未开主题化的应用，applyDesign 仍会走
                    // "默认设计全量重渲染"——systemDrawable binder 往返 + 144px 多层位图渲染 +
                    // monochrome 逐像素扫描，冷启动 200+ 次，IO 线程与宿主 UI/Render 线程抢
                    // CPU 且 ~20MB 位图分配引发 GC 抖动。上游独立进程扛得住，宿主
                    // com.miui.home 进程（MIUI 自身 UI 同线程竞争）扛不住。
                    // 快速路径与重渲染视觉等价：保留枚举原图标，仅按设计分支语义清空
                    // monochromeIcon（桌面 themed 圆显隐一致）；iconPackPackage 保持枚举原值
                    // （theme kind 本就原样保留语义）。
                    when {
                        // v2.9.158：快捷方式图标还没解码出来（saved_shortcuts 行先到、
                        // LauncherApps 图标后到）→ 跳过装饰。此时渲染只会产出"无图标"
                        // 的结果，既白费一次渲染又会污染图标渲染缓存；等 shortcutIcons
                        // 到位后 buildShortcutApps 会重建并正常装饰。
                        app.shortcut != null && app.icon == null -> app
                        special == null && iconSettings.iconDesign == null && app.icon == null -> app
                        special == null && iconSettings.iconDesign == null && !themed ->
                            if (app.monochromeIcon == null) app else app.copy(monochromeIcon = null)
                        else -> iconStore.applyDesign(app, special, iconSettings.iconDesign, iconSettings, themed)
                    }
                }.getOrElse { app }
            }
        }
    }

    /**
     * 上游 LauncherViewModel.kt:445-447 逐字语义：文件夹图标项装饰 —— item_icons 里
     * key "folder:<id>" 的自定义选择（图标设计器 FolderEditor 入口产出）经 ItemIconStore
     * .apply 渲染成位图。无自定义选择的文件夹不进 map（folderItem 回落 asApp() 默认圆标）。
     */
    private suspend fun decorateFolders(
        folders: List<LauncherFolder>,
        icons: Map<String, com.galaxyrio.gracelauncher.data.ItemIcon>,
    ): Map<String, LauncherApp> {
        if (icons.isEmpty() || folders.isEmpty()) return emptyMap()
        val iconSettings = currentIconSettings()
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            folders.mapNotNull { folder ->
                val choice = icons[folder.key] ?: return@mapNotNull null
                runCatching { iconStore.apply(folder.asApp(), choice, iconSettings) }
                    .getOrNull()?.takeIf { it.icon != null }?.let { folder.id to it }
            }.toMap()
        }
    }

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

    /** 原版 LauncherViewModel.toggleFavorite 的 P1 同构：SP 落盘 + StateFlow 即时刷新。 */
    fun toggleFavorite(app: LauncherApp) {
        val current = _uiState.value.let { it.favoriteOrder + it.favorites.map(LauncherApp::key) }.distinct()
        val updated = favoritesStore.toggle(app.key, current)
        ModLog.i("toggleFavorite: ${app.key} shortcut=${app.shortcut != null} size=${updated.size}")
        _uiState.update { it.copy(favoriteOrder = updated) }
    }

    /** 收藏顺序里摘掉某个 key（快捷方式被移除时清理脏 key；ShortcutSync 的回调）。 */
    private fun dropFavoriteKey(key: String) {
        val current = _uiState.value.favoriteOrder
        if (key !in current) return
        val updated = favoritesStore.toggle(key, current)
        _uiState.update { it.copy(favoriteOrder = updated) }
    }

    /** hotfix8：编辑收藏页重排（原版 LauncherViewModel.reorderFavorites：Store 只重排现有成员）。 */    fun reorderFavorites(keys: List<String>) {
        val updated = favoritesStore.reorder(keys, _uiState.value.favoriteOrder)
        _uiState.update { it.copy(favoriteOrder = updated) }
    }

    /**
     * folders 阶段：编辑收藏页的文件夹收藏开关（原版 LauncherSettingsRepository.
     * setFolderFavorites 直通 —— 宿主与库同进程，无需走桥；文件夹流实时跟随）。
     */
    suspend fun setFolderFavorites(selection: Map<String, Boolean>): Boolean {
        if (selection.isEmpty()) return true
        return try {
            com.galaxyrio.gracelauncher.data.LauncherSettingsRepository(
                LauncherDatabase.getInstance(getApplication())).setFolderFavorites(selection)
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ModLog.e("GraceHomeViewModel setFolderFavorites failed", error); false
        }
    }

    /**
     * C 组（C3）：模块设置页命令入口（GraceBridgeReceiver 转发）。
     * 落库与快照回推都在引擎协程内，onReceive 主线程零阻塞。
     */
    fun onSettingsCommand(cmd: String?, arg1: String?, arg2: String?, json: String?) {
        settingsHostEngine.handle(cmd, arg1, arg2, json)
    }

    /** C 组（C3）：引擎落库回调 —— 设置行走 Room 观察流自动生效，这里补 SP 面（收藏序）。 */
    private fun onSettingsMutated() {
        val refreshed = favoritesStore.currentOrder().orEmpty()
        _uiState.update { it.copy(favoriteOrder = refreshed, settingsRevision = it.settingsRevision + 1) }
    }


    // 审计修缮（2026-10-16）：删除 cycleClockLayout —— P1 演示入口，唯一调用点
    // （GracePopupActions.cycleClockStyle 死接线）已删；时钟样式走设置页 ClockStyle 面板。

    /** P3：写路径切 Room（GraceSettingsRepository.mutateSettings 事务；DB 故障内部降级 SP）。 */
    fun updateSettings(transform: (GraceHomeSettings) -> GraceHomeSettings) {
        viewModelScope.launch {
            val next = settingsRepository.mutateSettings(transform)
            _uiState.update { it.copy(settings = next) }
        }
    }

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

    /** null = 加载中。原版 installedPacks（adw/nova/go/lawnchair 等 discovery intents）。 */
    private val _iconPacks = MutableStateFlow<List<com.galaxyrio.gracelauncher.data.icons.IconPackInfo>?>(null)
    val iconPacks = _iconPacks.asStateFlow()

    fun loadIconPacks() {
        if (_iconPacks.value != null) return
        viewModelScope.launch {
            val result = runCatching { appRepository.iconPacks.installedPacks() }
                .onFailure { ModLog.e("GraceHomeViewModel loadIconPacks failed", it) }
                .getOrDefault(emptyList())
            _iconPacks.value = result
        }
    }

    /** 单选简化（原版多选 fallback 顺序随 C 组设置页恢复）：null = 系统图标。 */
    fun setIconPack(pkg: String?) {
        updateSettings { it.copy(iconPackPackages = listOfNotNull(pkg)) }
    }

    /** GraceHomeView.onHomeDetached 显式调用（手动构造的 ViewModel 不走 onCleared）。 */
    fun shutdown() {
        appLoadJob?.cancel()
        settingsJob?.cancel()
        iconsJob?.cancel()
        hiddenAppsJob?.cancel()
        foldersJob?.cancel()
        // 快捷方式域：取消流跟随 + 解绑系统回调 + 关闭仓储（实现见 ShortcutSync.dispose）
        shortcuts.dispose()
        // v2.9.126：日程失效监听解绑（实现已迁 AgendaFeed，dispose 负责 job + observer）
        agenda.dispose()
        // B6.2：天气引擎停摆（反注册 ContentObserver；VM 每次 attach 新建，dispose 即终态）
        weather.dispose()
        // 桥快照域：取消宽限期收尾 job + 清 pending
        bridge.dispose()
        runCatching { getApplication<Application>().unregisterReceiver(packageReceiver) }
            .onFailure { ModLog.e("GraceHomeViewModel unregister receiver failed", it) }
        runCatching { getApplication<Application>().unregisterReceiver(bridgeReceiver) }
            .onFailure { ModLog.e("GraceHomeViewModel unregister bridge receiver failed", it) }
        // 壁纸跟随失效源解绑（init 侧注册，见 wallpaperRevision 注释）
        runCatching {
            android.app.WallpaperManager.getInstance(getApplication())
                .removeOnColorsChangedListener(wallpaperColorsListener)
        }
        runCatching { getApplication<Application>().unregisterReceiver(wallpaperReceiver) }
            .onFailure { ModLog.e("GraceHomeViewModel unregister wallpaper receiver failed", it) }
    }
}
