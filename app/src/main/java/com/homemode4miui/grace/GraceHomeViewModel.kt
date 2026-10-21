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

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyrio.gracelauncher.data.AppRepository
import com.galaxyrio.gracelauncher.data.CalendarRepository
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
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.ShortcutRepository
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.media.MediaAccess
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

/** B6.2：日程加载状态（语义对齐原版 ScheduleStatus）。 */
enum class GraceScheduleStatus { PermissionRequired, Loading, Ready, Error }

data class GraceHomeUiState(
    val apps: List<LauncherApp> = emptyList(),
    /** 收藏顺序（LauncherApp.key）；favorites 列表由此派生，保证增删即时刷新。 */
    val favoriteOrder: List<String> = emptyList(),
    val settings: GraceHomeSettings = GraceHomeSettings(),
    /** 审计修缮（2026-10-16）：宿主设置突变信号（onSettingsMutated 自增）。TEXT_MODE/
     *  THEMED_ICONS 等仅落 SP 的桥命令不触发 Room 设置流，外观层读 SP 需以此驱动重组。 */
    val settingsRevision: Int = 0,
    val isLoadingApps: Boolean = true,
    val appLoadFailed: Boolean = false,
    /** 同步揭示门控（装机反馈：时钟/文件夹先出、应用后跳的分层加载观感）——
     *  收藏子集就绪（两段式 phase 1）/ 全量就绪（无已存收藏序）/ 装载失败（空态兜底）
     *  三条路径置 true；后续包变化刷新不重置（桌面已显示，不闪断）。首页内容
     *  （时钟/widget/收藏区/Grace 按钮）在 Route 侧按此门控统一出现。 */
    val homeReady: Boolean = false,
    /** P4：LauncherApps.hasShortcutHostPermission（弹层快捷方式可用性，HOME 角色决定）。 */
    val hasShortcutAccess: Boolean = false,
    // ---- P6（B6.x）附属特性状态（全部独立降级：桥断/无权限 = 空缺隐藏）----
    /** B6.1：当前播放会话（模块 App 进程快照；null = 无播放/桥断）。 */
    val homeMedia: NowPlaying? = null,
    /** B6.3：应用通知预览（包名 → 最新在前）。 */
    val notifications: Map<String, List<AppNotification>> = emptyMap(),
    /** B6.1/B6.3 通道可用性（模块 App 通知监听权限探测）。 */
    val hasMediaAccess: Boolean = false,
    /** B6.2：日程（宿主进程直读 CalendarProvider）。 */
    val events: List<ScheduleEvent> = emptyList(),
    val scheduleStatus: GraceScheduleStatus = GraceScheduleStatus.PermissionRequired,
    /** B6.2：天气（模块 App 进程读小米天气 provider 快照桥接）。 */
    val weather: WeatherState = WeatherState(),
    // ---- hotfix7：隐藏应用（Room hidden_apps 表直读）----
    val hiddenAppKeys: Set<String> = emptySet(),
    // ---- folders 阶段：文件夹（Room folders 表 + popup items 成员 JSON 直读）----
    val folders: List<LauncherFolder> = emptyList(),
    /** hotfix folders2：弹层 widget 项（folderId → HomeLayout；原版 popup items widget 分支）。 */
    val folderWidgets: Map<String, List<com.galaxyrio.gracelauncher.data.HomeLayout>> = emptyMap(),
    /** hotfix folders2：已保存快捷方式（saved_shortcuts 表 → LauncherApp，文件夹成员解析用）。 */
    val savedShortcutApps: List<LauncherApp> = emptyList(),
    /** 上游 LauncherUiState.folderApps 同构：装饰后的文件夹图标项（key = folder id）。
     *  图标设计器（FolderEditor → IconDesigner）对文件夹的产出存 item_icons
     *  （key "folder:<id>"），经 ItemIconStore.apply 渲染成位图 —— 主页/抽屉文件夹行消费。 */
    val folderApps: Map<String, LauncherApp> = emptyMap(),
    // 上游同步 v1.1.8 S8a：工作资料数据面（仅 managed profile；MIUI 双开不受影响）
    val workProfiles: List<com.galaxyrio.gracelauncher.data.WorkProfile> = emptyList(),
    val workProfileApps: List<LauncherApp> = emptyList(),
    // 上游同步 v1.1.8 S8b：弹层项（per ownerKey；openFirstItem 弹层版消费）
    val popups: Map<String, List<PopupItem>> = emptyMap(),
    // 上游同步 v1.1.8 S8b：图标设计器产出（GraceButton artwork/design 消费）
    val itemIcons: Map<String, com.galaxyrio.gracelauncher.data.ItemIcon> = emptyMap(),
    /**
     * 上游同步 v1.1.8 S8b（LauncherViewModel.kt:450-451 同构）：Grace 按钮虚拟项，
     * icon = ItemIconStore.apply 渲染后的 256px 位图（Suggestion glyph/图片/托盘色+
     * 圆形 mask）。null = itemIcons 尚未产出；Route 层 takeIf(key in itemIcons) 决定
     * artwork 是否生效（上游 LauncherScreen.kt:1054 语义）。
     */
    val graceButtonApp: com.galaxyrio.gracelauncher.data.LauncherApp? = null,
) {
    /** 原版 LauncherUiState.favoriteItems 的 P1 同构：按 favoriteOrder 排出已装应用。
     *  刷新期不清空（上游 favoriteApps 只挡 settings 加载/失败）——安装/卸载触发的
     *  refreshApps 后台跑，旧列表保持显示，新列表就绪后原位换入（装机实锤：整列表
     *  消失几秒是移植引入的 isLoadingApps 空化，非上游设计）。 */
    val favorites: List<LauncherApp>
        get() {
            if (appLoadFailed) return emptyList()
            val byKey = apps.associateBy(LauncherApp::key)
            return favoriteOrder.mapNotNull(byKey::get)
        }

    /** 文件夹图标项（原版 LauncherViewModel.folderItem 语义；label = 文件夹名）。
     *  上游 LauncherViewModel.kt:166-169：优先取装饰后的 folderApps（图标设计器对
     *  文件夹的产出 key "folder:<id>" 已渲染成位图），无自定义设计回落 asApp() 默认圆标。 */
    fun folderItem(folder: LauncherFolder): LauncherApp =
        folderApps[folder.id] ?: folder.asApp()

    /**
     * 原版 LauncherViewModel.favoriteItems 逐字语义（LauncherViewModel.kt:183-195）：
     * "Folder placement owns membership; the shared order only determines position."
     * favoriteApps + inFavorites 文件夹项合并；文件夹初次加入时排在末尾，
     * 重排后跟随 favoriteOrder。
     */
    val favoriteItems: List<LauncherApp>
        get() {
            if (appLoadFailed) return emptyList()
            val items = (favorites + folders.filter { it.placement.inFavorites }.map(::folderItem))
                .associateBy(LauncherApp::key)
            // Existing folders keep their old position at the end until reordered.
            return (favoriteOrder + items.keys).distinct().mapNotNull(items::get)
        }

    /**
     * 原版 LauncherViewModel.appListApps 逐字语义（hotfix7）：
     * "Hide Apps affects only the alphabetical app list, not other launcher surfaces."
     * 加载中/失败 = 空列表；favorites 等其余表面仍用全量 apps。
     * 上游仅挡 settings 加载（appListApps: isLoadingSettings || settingsLoadFailed）；
     * isLoadingApps 空化会让安装/卸载刷新期整列表消失（装机实锤），去掉 —— 刷新期
     * 旧列表保持显示。appLoadFailed 保留：加载失败且无旧数据时空列表即失败态。
     */
    val appListApps: List<LauncherApp>
        get() = if (appLoadFailed) emptyList()
        // 上游同步 v1.1.9：手动隐藏表 + autoHideApps 文件夹成员派生集合求并集
        else apps.filterNot { it.key in effectiveHiddenAppKeys }
            // 上游同步 v1.1.8 S8a：工作资料应用从主列表收编进独立文件夹（仅 managed profile，
            // MIUI 双开/clone 永不进入——见 AppRepository.workProfiles 严格过滤）
            .let { list -> if (workAppsOrganized) list.filterNot { it.isWorkProfile } else list }

    /** 上游同步 v1.1.9：手动隐藏 ∪ autoHideApps 文件夹成员（LauncherStorageSnapshot 同构语义）。 */
    val folderHiddenAppKeys: Set<String>
        get() = com.galaxyrio.gracelauncher.data.hiddenAppsFromFolders(folders)
    val effectiveHiddenAppKeys: Set<String>
        get() = hiddenAppKeys + folderHiddenAppKeys

    /** 上游同步 v1.1.8 S8a：工作资料是否已组织为独立文件夹（enabled + 存在 managed profile + 非 NormalApp 展示）。 */
    val workAppsOrganized: Boolean
        get() = settings.workProfile.enabled && workProfiles.isNotEmpty() &&
            settings.workProfile.display != PrivateSpaceDisplay.NormalApp

    /** 原版 LauncherViewModel.workProfileApps 语义：appOrder 排序 + 文件夹收编。 */
    val workProfileAppsOrdered: List<LauncherApp>
        get() = settings.workProfile.orderedApps(workProfileApps)

    /** 上游 workFolder：桌面抽屉底部工作资料文件夹载体（无应用时仍显示空态行）。 */
    val workFolder: LauncherFolder?
        get() = settings.workProfile.folder(workProfileAppsOrdered, WorkProfileFolderId)
            .takeIf { workAppsOrganized || workProfileApps.isNotEmpty() }
}

class GraceHomeViewModel(application: Application) : AndroidViewModel(application) {
    private val favoritesStore = FavoritesStore(application)
    private val appRepository = AppRepository(application)
    private val settingsRepository = GraceSettingsRepository(application)
    /** P4：弹层快捷方式查询/缓存/启动（AutoCloseable，shutdown 显式关闭）。 */
    private val shortcutRepository = ShortcutRepository(application)
    /** P4：widget id 管理（allocate/delete）。startListening 由 UI 侧 rememberWidgetHost 负责。 */
    /** P6（B6.2）：日历仓储（宿主进程直读 CalendarProvider；无权限降级 PermissionRequired）。 */
    private val calendarRepository = CalendarRepository(application)
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
            onWeatherRefresh = { weatherHostEngine.refresh("settings") })
    }
    /**
     * B6.2 天气宿主引擎：小米天气 provider 白名单放行 com.miui.home、拒绝模块
     * App 进程（装机实锤 hasAccessProvider 日志），读取在【本进程】进行，
     * 结果进程内直推 uiState；设置页经快照 weatherJson 回带。
     * 两个引擎互相引用（设置刷新 ↔ 天气回推快照），by lazy 断开初始化环。
     */
    private val weatherHostEngine: GraceWeatherHostEngine by lazy {
        GraceWeatherHostEngine(
            application, viewModelScope,
            preferredLocationId = { settingsHostEngine.currentWeatherLocationId() },
            onState = ::onWeatherState,
        )
    }
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

    /** hotfix2：设置页"移动小部件"命令 → 桌面进入 widget 编辑模式（原版同进程直改 editingHome）。 */
    private val _moveWidgetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val moveWidgetRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _moveWidgetRequests
    private fun onBridgeMoveWidget() { _moveWidgetRequests.tryEmit(Unit) }

    /** 上游同步补漏：设置页"添加自定义小组件"命令 → 桌面开 WidgetPicker（跨进程 UI 动作）。 */
    private val _addWidgetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addWidgetRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _addWidgetRequests
    private fun onBridgeAddWidget() { _addWidgetRequests.tryEmit(Unit) }

    /** folders 阶段（hotfix folders2）：设置页"添加新的小组件"命令 → 桌面开 WidgetPicker 弹层模式。 */
    private val _addPopupWidgetRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val addPopupWidgetRequests: kotlinx.coroutines.flow.SharedFlow<String> = _addPopupWidgetRequests
    private fun onBridgeAddPopupWidget(ownerKey: String) { _addPopupWidgetRequests.tryEmit(ownerKey) }

    private val _uiState = MutableStateFlow(GraceHomeUiState(
        // Room 首读前的瞬时默认（loadInitial 在 init 即触发，正常先于首帧组合到达）
        settings = GraceHomeSettings(),
    ))
    val uiState = _uiState.asStateFlow()

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

    // ---- P4：widget picker 状态 ----
    /** null = 加载中；空列表 = 无可用 provider。宿主进程单 profile（原版多 profile 过滤裁剪）。 */
    private val _widgetProviders = MutableStateFlow<List<AppWidgetProviderInfo>?>(null)
    val widgetProviders = _widgetProviders.asStateFlow()
    private val _widgetPickerBusy = MutableStateFlow(true)
    val widgetPickerBusy = _widgetPickerBusy.asStateFlow()
    /** P4：widget 添加链路结果（false = bind/写库失败，UI 侧 Toast）。 */
    private val _widgetSetupResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val widgetSetupResults: kotlinx.coroutines.flow.SharedFlow<Boolean> = _widgetSetupResults
    /** refactor2（v2.9.44）：widget 管理抽 WidgetController（host/落库/配置页全链路）。 */
    private val widgetController = WidgetController(
        application, viewModelScope, settingsRepository,
        onSetupResult = { _widgetSetupResults.tryEmit(it) },
        onHomeLayoutSaved = { saved -> _uiState.update { it.copy(settings = saved) } },
    )

    private var appLoadJob: Job? = null
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
    /** hotfix folders2：已保存快捷方式流（saved_shortcuts 表） */
    private var savedShortcutsJob: Job? = null
    /** P6：图标包开关变化 → 重刷应用列表（渲染装饰跟随包顺序）。 */
    private var lastIconPacks: List<String>? = null
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
        sendBridgeCommand(GraceBridge.Cmd.SNAPSHOT_REFRESH)
        // 壁纸跟随失效源注册（见 wallpaperRevision 注释）；shutdown 侧解绑
        runCatching {
            android.app.WallpaperManager.getInstance(application)
                .addOnColorsChangedListener(wallpaperColorsListener,
                    android.os.Handler(android.os.Looper.getMainLooper()))
        }
        ContextCompat.registerReceiver(application, wallpaperReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        // B6.2：天气首装载（本进程直读小米天气 provider；后续 ContentObserver 失效）
        weatherHostEngine.refresh("mount")
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
        // hotfix folders2：已保存快捷方式跟随（文件夹成员里的 shortcut key 解析用；
        // 图标跨进程为空 —— 成员行用默认图标，登记降级）
        savedShortcutsJob = viewModelScope.launch {
            try {
                LauncherDatabase.getInstance(application).itemsDao().shortcuts().collect { rows ->
                    val apps = rows.mapNotNull { row ->
                        val activity = row.activity.takeIf { it.isNotBlank() }
                            ?.let(android.content.ComponentName::unflattenFromString)
                            ?: return@mapNotNull null
                        val shortcut = LauncherShortcut(
                            id = row.shortcutId, packageName = row.packageName, label = row.label,
                            icon = null, activity = activity,
                        )
                        LauncherApp(componentName = activity, label = row.label.ifBlank { row.shortcutId },
                            icon = null, shortcut = shortcut)
                    }
                    _uiState.update { it.copy(savedShortcutApps = apps) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel observeSavedShortcuts failed", error)
            }
        }
        // B6.2：天气首装载已由 init 的 weatherHostEngine.refresh("mount") 承接
        // （本进程直读 provider，不再向模块进程请求）
    }

    fun refreshApps() {
        appLoadJob?.cancel()
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
                // 两段式装载（宿主性能适配，装机实锤：重启桌面后时钟/文件夹先出、应用要等
                // 1-2 秒 —— 200+ 应用串行 getBadgedIcon 解码，收藏行又依赖全量列表）：
                // ① 枚举全量骨架（不解码系统图标）→ 并行解码收藏子集 → 先换入
                //    （收藏区随时钟/文件夹立即绘制，无自定义的应用走 decorateApps 骨架跳过）；
                // ② 全量解码在后台继续，完成后整体换入（抽屉/搜索等全量表面就绪）。
                // 首装无已存收藏序（currentOrder()=null）时跳过①：默认收藏要由
                // favoritesFor 基于全量列表生成，两段无收益。
                val inventory = appRepository.enumerateApps(packs)
                val savedOrder = favoritesStore.currentOrder()
                if (savedOrder != null) {
                    val partial = inventory.withIcons(savedOrder.toSet())
                    val apps = decorateApps(partial)
                    val folderApps = decorateFolders(_uiState.value.folders, snapshotAtDecorate)
                    _uiState.update {
                        it.copy(apps = apps, folderApps = folderApps, favoriteOrder = savedOrder,
                            isLoadingApps = false, homeReady = true)
                    }
                    itemsDecoratedWith = snapshotAtDecorate
                }
                val full = inventory.withIcons(inventory.apps.map(LauncherApp::key).toSet())
                val apps = decorateApps(full)
                val folderApps = decorateFolders(_uiState.value.folders, snapshotAtDecorate)
                // 原版语义：favoritesFor 负责首装默认收藏 / 旧格式迁移 / 去重；
                // 之后包变化刷新只补 key，不打乱用户已保存的顺序。
                val order = favoritesStore.favoritesFor(apps)
                // 上游同步 v1.1.8 S8a：工作资料枚举（无 managed profile 时零开销静默跳过）。
                // 两段式下归入②：收藏区不依赖工作资料，phase 1 不为它买单。
                val workProfiles = appRepository.workProfiles()
                val workProfileApps = if (workProfiles.isNotEmpty()) {
                    appRepository.loadWorkApps(workProfiles, packs)
                } else emptyList()
                _uiState.update {
                    it.copy(apps = apps, folderApps = folderApps, favoriteOrder = order,
                        isLoadingApps = false, homeReady = true,
                        workProfiles = workProfiles, workProfileApps = workProfileApps)
                }
                itemsDecoratedWith = snapshotAtDecorate
                // 装载期间 icons 流前进过（iconsJob 因 isLoadingApps 让路）：
                // 以最新快照补一轮内存重装饰，否则新快照要等下次 emission 才生效。
                if (iconsSnapshot != snapshotAtDecorate) applyItemIconSnapshot()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel loadApps failed", error)
                // 装载失败也揭示首页（收藏空态兜底），不能让桌面永久空白
                _uiState.update { it.copy(isLoadingApps = false, appLoadFailed = true, homeReady = true) }
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
                val state = _uiState.value
                val apps = decorateApps(state.apps)
                val folderApps = decorateFolders(state.folders, iconsSnapshot)
                _uiState.update { it.copy(apps = apps, folderApps = folderApps) }
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
                    // 骨架应用（两段式装载 phase 1，系统图标尚未解码）：无 per-item 选择 +
                    // 无全局面设计时跳过渲染 —— 否则 systemDrawable 兜底会对 200+ 骨架触发
                    // 全量重渲染（风暴复现）；phase 2 解码全量后统一装饰兜底。
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

    // ---- P4：快捷方式（ShortcutRepository 直通，签名对齐原版 LauncherActions 字段）----

    /** suspend：ShortcutPopup 的 produceState 协程内调用（GracePopupActions.shortcuts 契约）。 */
    suspend fun loadShortcuts(app: LauncherApp): ShortcutResult =
        runCatching { shortcutRepository.shortcutsFor(app) }
            .getOrElse { ModLog.e("GraceHomeViewModel loadShortcuts failed: ${app.key}", it); ShortcutResult(com.galaxyrio.gracelauncher.data.ShortcutStatus.Error) }

    fun cachedShortcuts(app: LauncherApp): ShortcutResult? =
        runCatching { shortcutRepository.peek(app) }.getOrNull()

    /** 行触摸准备（AppRowGestures.onPrepare）：协程预取，弹层打开零等待。 */
    fun prepareShortcuts(app: LauncherApp) {
        viewModelScope.launch {
            runCatching { shortcutRepository.prefetch(listOf(app)) }
                .onFailure { ModLog.e("GraceHomeViewModel prepareShortcuts failed: ${app.key}", it) }
        }
    }

    /** P5：签名对齐原版 launchShortcut —— sourceBounds/options 供弹层启动动画（B5.1）。 */
    fun launchShortcut(shortcut: LauncherShortcut, sourceBounds: Rect? = null, options: Bundle? = null): Boolean = runCatching {
        shortcutRepository.launch(shortcut, sourceBounds, options)
    }.getOrElse {
        ModLog.e("GraceHomeViewModel launchShortcut failed: ${shortcut.key}", it); false
    }

    private fun refreshShortcutAccess() {
        val access = runCatching { shortcutRepository.hasAccess() }
            .onFailure { ModLog.e("GraceHomeViewModel hasShortcutAccess failed", it) }
            .getOrDefault(false)
        _uiState.update { it.copy(hasShortcutAccess = access) }
    }

    // ---- P4：widget 管理（原版 WidgetSetupActivity 链路的宿主进程内等价实现）----

    /**
     * widget picker 数据加载（进入 picker 时调用）。
     * 原版多 profile/private profile 过滤裁剪：宿主进程单 myUserHandle。
     */
    fun loadWidgetProviders() {
        if (_widgetProviders.value != null) { _widgetPickerBusy.value = false; return }
        _widgetPickerBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val context = getApplication<Application>()
                val manager = AppWidgetManager.getInstance(context)
                manager.getInstalledProvidersForProfile(Process.myUserHandle())
                    .sortedBy { provider ->
                        runCatching { provider.loadLabel(context.packageManager) }
                            .getOrDefault(provider.provider.className).lowercase()
                    }
            }.onFailure { ModLog.e("GraceHomeViewModel loadWidgetProviders failed", it) }.getOrNull()
            _widgetProviders.value = result
            _widgetPickerBusy.value = false
        }
    }

    /**
     * 添加 widget：allocate → bindAppWidgetIdIfAllowed（宿主持 BIND_APPWIDGET，
     * 免系统确认对话框）→ 写 Room homeLayout → 有配置页则 NEW_TASK 启动
     * （fire-and-forget：配置返回前 widget 已落库生效）。全链路 runCatching，
     * 失败回收 allocated id 并发 false（UI Toast），对齐原版 cancelSetup。
     */
    /** widget 增删委托 WidgetController（v2.9.44-refactor2 抽离，语义见该类头注）。 */
    fun addHomeWidget(info: AppWidgetProviderInfo) = widgetController.addHome(info)

    fun removeHomeWidget() = widgetController.removeHome(_uiState.value.settings.homeLayout)

    fun addPopupWidgetTo(ownerKey: String, info: AppWidgetProviderInfo) =
        widgetController.addPopupTo(ownerKey, info)

    /**
     * folders 阶段（hotfix folders2）：模块设置页快捷方式代查（SHORTCUTS_QUERY）。
     * HOME 角色在本进程；从已加载应用列表解析 appKey → ShortcutRepository 查询 →
     * ACTION_SHORTCUTS_RESULT 点对点回包模块进程（无图标，跨 JSON 降级）。
     */
    private fun onBridgeShortcutQuery(json: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val keys = org.json.JSONArray(json).let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) } }
                val byKey = _uiState.value.apps.associateBy(LauncherApp::key)
                val wires = keys.flatMap { key ->
                    val app = byKey[key] ?: return@flatMap emptyList()
                    val result = runCatching { shortcutRepository.shortcutsFor(app) }
                        .getOrElse { ModLog.e("GraceHomeViewModel shortcut query failed: $key", it); return@flatMap emptyList() }
                    result.shortcuts.map { s ->
                        GraceSettingsBridge.ShortcutWire(
                            appKey = key, key = s.key, packageName = s.packageName, shortcutId = s.id,
                            activity = s.activity?.flattenToString(), label = s.label,
                        )
                    }
                }
                getApplication<Application>().sendBroadcast(
                    android.content.Intent(GraceSettingsBridge.ACTION_SHORTCUTS_RESULT)
                        .setPackage(com.homemode4miui.bridge.GraceBridge.PACKAGE_MODULE)
                        .putExtra(GraceBridge.EXTRA_JSON, GraceSettingsBridge.encodeShortcutsResult(wires))
                )
            }.onFailure { ModLog.e("GraceHomeViewModel shortcut query relay failed", it) }
        }
    }

    /** 原版 WidgetSetupActivity.configureWidget 语义：对现有 widget 启动其配置页。 */
    fun configureHomeWidget() {
        val current = _uiState.value.settings.homeLayout
        if (!current.hasWidget) return
        runCatching {
            val context = getApplication<Application>()
            val manager = AppWidgetManager.getInstance(context)
            val info = manager.getAppWidgetInfo(current.widgetId) ?: return
            if (info.configure == null) return
            context.startActivity(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setClassName(info.configure.packageName, info.configure.className)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, current.widgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { ModLog.e("GraceHomeViewModel configureHomeWidget failed", it) }
    }

    /** HomeScreen 编辑把手：首页内容区垂直偏移持久化（Room homeLayoutJson）。 */
    fun updateHomeLayoutTopOffset(offset: Float) =
        updateSettings { it.copy(homeLayout = it.homeLayout.copy(topOffsetDp = offset)) }

    /** HomeScreen 编辑把手：widget 高度持久化（0 = provider 默认）。 */
    fun updateHomeLayoutWidgetHeight(height: Int) =
        updateSettings { it.copy(homeLayout = it.homeLayout.copy(widgetHeightDp = height)) }

    /** 原版 clockAppKey == null 的默认语义：Android 标准 SHOW_ALARMS。 */
    fun openClock(): Boolean = runCatching {
        val context = getApplication<Application>()
        context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { ModLog.e("GraceHomeViewModel openClock failed", it) }.isSuccess

    /** 原版 LauncherViewModel.toggleFavorite 的 P1 同构：SP 落盘 + StateFlow 即时刷新。 */
    fun toggleFavorite(app: LauncherApp) {
        val current = _uiState.value.let { it.favoriteOrder + it.favorites.map(LauncherApp::key) }.distinct()
        val updated = favoritesStore.toggle(app.key, current)
        _uiState.update { it.copy(favoriteOrder = updated) }
    }

    /** hotfix8：编辑收藏页重排（原版 LauncherViewModel.reorderFavorites：Store 只重排现有成员）。 */
    fun reorderFavorites(keys: List<String>) {
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

    /** B6.1：通知监听权限探测（模块 App 的 listener 组件；公开 API 任意进程可查）。 */
    fun refreshMediaAccess() {
        val context = getApplication<Application>()
        val granted = runCatching {
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(MediaAccess.component(context))
        }.getOrDefault(false)
        _uiState.update { it.copy(hasMediaAccess = granted) }
    }

    /** B6.2：日程刷新（原版 refreshAgenda 语义：无权限 → PermissionRequired，查询降级 Error）。 */
    fun refreshAgenda() {
        val context = getApplication<Application>()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!_uiState.value.settings.calendarAgenda) {
            _uiState.update { it.copy(events = emptyList(), scheduleStatus = GraceScheduleStatus.Ready) }
            return
        }
        if (!granted) {
            _uiState.update { it.copy(events = emptyList(), scheduleStatus = GraceScheduleStatus.PermissionRequired) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(scheduleStatus = GraceScheduleStatus.Loading) }
            runCatching { calendarRepository.upcomingEvents() }
                .onSuccess { events -> _uiState.update { it.copy(events = events, scheduleStatus = GraceScheduleStatus.Ready) } }
                .onFailure { error ->
                    ModLog.e("GraceHomeViewModel loadAgenda failed", error)
                    _uiState.update { it.copy(events = emptyList(), scheduleStatus = GraceScheduleStatus.Error) }
                }
        }
    }


    /**
     * B6.2 权限降级：宿主 manifest 不可改、无法在宿主进程内弹 READ_CALENDAR 授权
     * （授权对话框必须由声明权限的应用发起）——"Connect calendar" 降级为打开系统
     * 日历应用（用户可见日程；provider 数据待宿主获得权限后自动生效）。
     */
    fun openCalendarApp(): Boolean = runCatching {
        val context = getApplication<Application>()
        context.startActivity(Intent(Intent.ACTION_VIEW)
            .setData(android.provider.CalendarContract.CONTENT_URI)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { ModLog.e("GraceHomeViewModel openCalendarApp failed", it) }.isSuccess

    // ---- 桥快照摄入（GraceBridgeReceiver 转发）----

    fun onBridgeMedia(media: NowPlaying?, artChanged: Boolean) {
        _uiState.update { current ->
            // 封面未随包变化时沿用旧封面（artKey = sessionId+revision 的引擎侧去重约定）
            val merged = if (media != null && !artChanged && media.artwork == null) {
                val previous = current.homeMedia
                if (previous?.sessionId == media.sessionId && previous.revision == media.revision) {
                    media.copy(artwork = previous.artwork)
                } else media
            } else media
            current.copy(homeMedia = merged)
        }
    }

    fun onBridgeNotifications(notifications: Map<String, List<AppNotification>>) {
        _uiState.update { it.copy(notifications = notifications) }
    }

    /**
     * B6.2 天气状态入口：宿主引擎直推（原桥广播路径随天气读取上移废止）。
     * 更新 uiState 后回推设置快照 —— 设置页天气经快照回带，保证地点选择器/
     * 状态行与桌面同源。
     */
    fun onWeatherState(weather: WeatherState) {
        if (_uiState.value.weather == weather) return
        _uiState.update { it.copy(weather = weather) }
        settingsHostEngine.pushSnapshot()
    }

    // ---- 命令上行（宿主 → 模块 App 进程；命令在持有 token 的进程内执行）----

    private fun sendBridgeCommand(cmd: String, arg1: String? = null, arg2: String? = null) {
        runCatching {
            getApplication<Application>().sendBroadcast(GraceBridge.command(cmd, arg1, arg2))
        }.onFailure { ModLog.e("GraceHomeViewModel sendBridgeCommand($cmd) failed", it) }
    }

    /** B6.1：媒体控制（上一首/播放暂停/下一首/打开播放器）。 */
    fun controlMedia(sessionId: String, command: MediaCommand) {
        sendBridgeCommand(GraceBridge.Cmd.MEDIA_COMMAND, sessionId, GraceBridge.mediaCommandName(command))
    }

    /** B6.1：本地划走（原版 dismiss 语义：只影响本桌面显示，不取消系统通知）。 */
    fun dismissMedia(sessionId: String, revision: Long): Boolean {
        sendBridgeCommand(GraceBridge.Cmd.MEDIA_DISMISS, sessionId, revision.toString())
        // 原版立即本地消失由 store 状态驱动；跨进程简化为命令发出后 UI 等快照更新
        return true
    }

    /** B6.3：打开通知（contentIntent 由模块 App 进程以 BAL 选项发送）。 */
    fun openNotification(key: String, revision: Long): Boolean {
        sendBridgeCommand(GraceBridge.Cmd.NOTIFICATION_OPEN, key, revision.toString())
        return true
    }

    /** B6.3：清除通知（系统侧 cancel，原版"等 onNotificationRemoved"语义经快照回流）。 */
    fun dismissNotification(key: String, revision: Long): Boolean {
        sendBridgeCommand(GraceBridge.Cmd.NOTIFICATION_DISMISS, key, revision.toString())
        return true
    }

    /** B6.2：天气刷新请求（宿主进程内直读小米天气 provider；ContentObserver 常驻失效）。 */
    fun refreshWeather() {
        weatherHostEngine.refresh("manual")
    }

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
        savedShortcutsJob?.cancel()
        // B6.2：天气引擎停摆（反注册 ContentObserver；VM 每次 attach 新建，dispose 即终态）
        runCatching { weatherHostEngine.dispose() }
            .onFailure { ModLog.e("GraceHomeViewModel weatherHostEngine.dispose failed", it) }
        runCatching { shortcutRepository.close() }
            .onFailure { ModLog.e("GraceHomeViewModel shortcutRepository.close failed", it) }
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
