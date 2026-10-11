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
import com.galaxyrio.gracelauncher.data.ClockLayout
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
import com.galaxyrio.gracelauncher.platform.HomeWidgetHost
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
import kotlinx.coroutines.withContext

/** B6.2：日程加载状态（语义对齐原版 ScheduleStatus）。 */
enum class GraceScheduleStatus { PermissionRequired, Loading, Ready, Error }

data class GraceHomeUiState(
    val apps: List<LauncherApp> = emptyList(),
    /** 收藏顺序（LauncherApp.key）；favorites 列表由此派生，保证增删即时刷新。 */
    val favoriteOrder: List<String> = emptyList(),
    val settings: GraceHomeSettings = GraceHomeSettings(),
    val isLoadingApps: Boolean = true,
    val appLoadFailed: Boolean = false,
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
    /** B6.2：天气（模块 App 进程读 Breezy provider 快照桥接）。 */
    val weather: WeatherState = WeatherState(),
    // ---- hotfix7：隐藏应用（Room hidden_apps 表直读）----
    val hiddenAppKeys: Set<String> = emptySet(),
    // ---- folders 阶段：文件夹（Room folders 表 + popup items 成员 JSON 直读）----
    val folders: List<LauncherFolder> = emptyList(),
    /** hotfix folders2：弹层 widget 项（folderId → HomeLayout；原版 popup items widget 分支）。 */
    val folderWidgets: Map<String, List<com.galaxyrio.gracelauncher.data.HomeLayout>> = emptyMap(),
    /** hotfix folders2：已保存快捷方式（saved_shortcuts 表 → LauncherApp，文件夹成员解析用）。 */
    val savedShortcutApps: List<LauncherApp> = emptyList(),
    // 上游同步 v1.1.8 S8a：工作资料数据面（仅 managed profile；MIUI 双开不受影响）
    val workProfiles: List<com.galaxyrio.gracelauncher.data.WorkProfile> = emptyList(),
    val workProfileApps: List<LauncherApp> = emptyList(),
) {
    /** 原版 LauncherUiState.favoriteItems 的 P1 同构：按 favoriteOrder 排出已装应用。 */
    val favorites: List<LauncherApp>
        get() {
            if (isLoadingApps || appLoadFailed) return emptyList()
            val byKey = apps.associateBy(LauncherApp::key)
            return favoriteOrder.mapNotNull(byKey::get)
        }

    /** 文件夹图标项（原版 LauncherViewModel.folderItem 语义；label = 文件夹名）。 */
    fun folderItem(folder: LauncherFolder): LauncherApp = folder.asApp()

    /**
     * 原版 LauncherViewModel.favoriteItems 逐字语义（LauncherViewModel.kt:183-195）：
     * "Folder placement owns membership; the shared order only determines position."
     * favoriteApps + inFavorites 文件夹项合并；文件夹初次加入时排在末尾，
     * 重排后跟随 favoriteOrder。
     */
    val favoriteItems: List<LauncherApp>
        get() {
            if (isLoadingApps || appLoadFailed) return emptyList()
            val items = (favorites + folders.filter { it.placement.inFavorites }.map(::folderItem))
                .associateBy(LauncherApp::key)
            // Existing folders keep their old position at the end until reordered.
            return (favoriteOrder + items.keys).distinct().mapNotNull(items::get)
        }

    /**
     * 原版 LauncherViewModel.appListApps 逐字语义（hotfix7）：
     * "Hide Apps affects only the alphabetical app list, not other launcher surfaces."
     * 加载中/失败 = 空列表；favorites 等其余表面仍用全量 apps。
     */
    val appListApps: List<LauncherApp>
        get() = if (isLoadingApps || appLoadFailed) emptyList()
        else apps.filterNot { it.key in hiddenAppKeys }
            // 上游同步 v1.1.8 S8a：工作资料应用从主列表收编进独立文件夹（仅 managed profile，
            // MIUI 双开/clone 永不进入——见 AppRepository.workProfiles 严格过滤）
            .let { list -> if (workAppsOrganized) list.filterNot { it.isWorkProfile } else list }

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
    private val widgetHost = runCatching { HomeWidgetHost(application) }.getOrNull()
    /** P6（B6.2）：日历仓储（宿主进程直读 CalendarProvider；无权限降级 PermissionRequired）。 */
    private val calendarRepository = CalendarRepository(application)
    /** P6（B6.1/B6.2/B6.3）：桥快照接收（模块 App 进程 → 宿主进程显式广播）。 */
    private val bridgeReceiver = GraceBridgeReceiver(this)
    /** C 组（C3）：设置命令引擎（模块设置页 → 本进程落 Room/SP → 快照回推）。 */
    private val settingsHostEngine = GraceSettingsHostEngine(application, viewModelScope, ::onSettingsMutated, ::onBridgeMoveWidget,
        ::onBridgeAddPopupWidget, ::onBridgeShortcutQuery)

    /** hotfix2：设置页"移动小部件"命令 → 桌面进入 widget 编辑模式（原版同进程直改 editingHome）。 */
    private val _moveWidgetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val moveWidgetRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _moveWidgetRequests
    private fun onBridgeMoveWidget() { _moveWidgetRequests.tryEmit(Unit) }

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

    // ---- P4：widget picker 状态 ----
    /** null = 加载中；空列表 = 无可用 provider。宿主进程单 profile（原版多 profile 过滤裁剪）。 */
    private val _widgetProviders = MutableStateFlow<List<AppWidgetProviderInfo>?>(null)
    val widgetProviders = _widgetProviders.asStateFlow()
    private val _widgetPickerBusy = MutableStateFlow(true)
    val widgetPickerBusy = _widgetPickerBusy.asStateFlow()
    /** P4：widget 添加链路结果（false = bind/写库失败，UI 侧 Toast）。 */
    private val _widgetSetupResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val widgetSetupResults: kotlinx.coroutines.flow.SharedFlow<Boolean> = _widgetSetupResults

    private var appLoadJob: Job? = null
    /** P3：设置流常驻收集（Room 行变化驱动 settings）；shutdown 显式取消 */
    private var settingsJob: Job? = null
    /** hotfix7：隐藏应用键流（Room hidden_apps 表；设置页 SET_HIDDEN_APPS 落库后实时到达） */
    private var hiddenAppsJob: Job? = null
    /** folders 阶段：文件夹流（Room folders 表 + popup items 成员 JSON，原版 snapshots 同构） */
    private var foldersJob: Job? = null
    /** hotfix folders2：已保存快捷方式流（saved_shortcuts 表） */
    private var savedShortcutsJob: Job? = null
    /** P6：图标包开关变化 → 重刷应用列表（渲染装饰跟随包顺序）。 */
    private var lastIconPacks: List<String>? = null
    /** P6：图标包渲染状态（刷新完成后 UI 可显示装饰中）。 */
    private val _iconPacksDecorating = MutableStateFlow(false)
    val iconPacksDecorating = _iconPacksDecorating.asStateFlow()

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
        // P3：Room 设置接管 —— SP seed 迁移（一次性）+ 行变化流常驻跟随
        settingsJob = viewModelScope.launch {
            val initial = settingsRepository.loadInitial()
            _uiState.update { it.copy(settings = initial) }
            settingsRepository.observeSettings().collect { observed ->
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
                        )
                    }
                    folders to widgets
                }.collect { (folders, widgets) -> _uiState.update {
                    it.copy(folders = folders, folderWidgets = widgets)
                } }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel observeFolders failed", error)
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
        // B6.2：向模块请求一次天气快照（引擎已在监听服务连接时启动；未连接则无回包 = 隐藏）
        sendBridgeCommand(GraceBridge.Cmd.WEATHER_REFRESH)
    }

    fun refreshApps() {
        appLoadJob?.cancel()
        appLoadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingApps = true, appLoadFailed = false) }
            try {
                // B6.4：图标包跟随设置（装饰在 loadApps 内完成；包加载失败自动回退系统图标）
                val packs = _uiState.value.settings.enabledIconPackPackages
                lastIconPacks = packs
                val apps = loadApps(packs)
                // 原版语义：favoritesFor 负责首装默认收藏 / 旧格式迁移 / 去重；
                // 之后包变化刷新只补 key，不打乱用户已保存的顺序。
                val order = favoritesStore.favoritesFor(apps)
                // 上游同步 v1.1.8 S8a：工作资料枚举（无 managed profile 时零开销静默跳过）
                val workProfiles = appRepository.workProfiles()
                val workProfileApps = if (workProfiles.isNotEmpty()) {
                    appRepository.loadWorkApps(workProfiles, packs)
                } else emptyList()
                _uiState.update {
                    it.copy(apps = apps, favoriteOrder = order, isLoadingApps = false,
                        workProfiles = workProfiles, workProfileApps = workProfileApps)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("GraceHomeViewModel loadApps failed", error)
                _uiState.update { it.copy(isLoadingApps = false, appLoadFailed = true) }
            }
        }
    }

    /** B3.1：查询逻辑已抽入 AppRepository（AOSP 稳定 API，宿主进程直接可用）。 */
    private suspend fun loadApps(iconPackPackages: List<String>): List<LauncherApp> =
        appRepository.loadApps(iconPackPackages)

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
    fun addHomeWidget(info: AppWidgetProviderInfo) {
        val host = widgetHost ?: run { _widgetSetupResults.tryEmit(false); return }
        viewModelScope.launch(Dispatchers.Main) {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val context = getApplication<Application>()
                    val manager = AppWidgetManager.getInstance(context)
                    val id = host.allocateAppWidgetId()
                    val bound = runCatching {
                        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                        })
                    }.getOrDefault(false)
                    if (!bound) {
                        runCatching { host.deleteAppWidgetId(id) }
                            .onFailure { ModLog.e("GraceHomeViewModel cleanup unbound widget id failed", it) }
                        return@runCatching false
                    }
                    val label = runCatching { info.loadLabel(context.packageManager) }
                        .getOrDefault(info.provider.className)
                    settingsRepository.mutateSettings { current -> current.copy(
                        homeLayout = current.homeLayout.copy(
                            widgetId = id, widgetProvider = info.provider.flattenToString(),
                            widgetLabel = label, widgetHeightDp = 0,
                        )) }.let { saved -> _uiState.update { state -> state.copy(settings = saved) } }
                    // 配置页（fresh 信息校验防 provider 在 bind 后被移除）
                    val fresh = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
                    if (fresh?.configure != null) {
                        runCatching {
                            context.startActivity(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                                .setClassName(fresh.configure.packageName, fresh.configure.className)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { ModLog.e("GraceHomeViewModel configure activity launch failed", it) }
                    }
                    true
                }.getOrElse { error ->
                    ModLog.e("GraceHomeViewModel addHomeWidget failed", error); false
                }
            }
            _widgetSetupResults.tryEmit(success)
        }
    }

    /** 移除 widget：先删 host id（provider 收到删除通知）再清 homeLayout。 */
    fun removeHomeWidget() {
        val host = widgetHost ?: return
        val current = _uiState.value.settings.homeLayout
        if (!current.hasWidget) return
        val id = current.widgetId
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { host.deleteAppWidgetId(id) }
                .onFailure { ModLog.e("GraceHomeViewModel deleteAppWidgetId($id) failed", it) }
            val saved = settingsRepository.mutateSettings { it.copy(homeLayout = it.homeLayout.withoutWidget()) }
            _uiState.update { it.copy(settings = saved) }
        }
    }

    // ---- folders 阶段（hotfix folders2）：弹层 widget（原版 WidgetSetupActivity popupOwner 分支）----

    /**
     * 原版 WidgetSetupActivity.finishAdding popupOwner 分支同构：allocate → bind →
     * PopupItem("widget:<id>", layout) 落 app_popups（ownerKey）。与 addHomeWidget 的差异
     * 仅在写库目标（弹层 vs homeLayout）。失败回收 id 并发 false（Route Toast）。
     */
    fun addPopupWidgetTo(ownerKey: String, info: AppWidgetProviderInfo) {
        val host = widgetHost ?: run { _widgetSetupResults.tryEmit(false); return }
        viewModelScope.launch(Dispatchers.Main) {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val context = getApplication<Application>()
                    val manager = AppWidgetManager.getInstance(context)
                    val id = host.allocateAppWidgetId()
                    val bound = runCatching {
                        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, Bundle().apply {
                            putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                        })
                    }.getOrDefault(false)
                    if (!bound) {
                        runCatching { host.deleteAppWidgetId(id) }
                            .onFailure { ModLog.e("GraceHomeViewModel cleanup unbound popup widget id failed", it) }
                        return@runCatching false
                    }
                    val label = runCatching { info.loadLabel(context.packageManager) }
                        .getOrDefault(info.provider.className)
                    val layout = com.galaxyrio.gracelauncher.data.HomeLayout(
                        widgetId = id, widgetProvider = info.provider.flattenToString(),
                        widgetLabel = label, widgetHeightDp = 0,
                    )
                    val items = com.galaxyrio.gracelauncher.data.LauncherItemsRepository(
                        LauncherDatabase.getInstance(context))
                    items.updatePopup(ownerKey, emptyList()) { entries ->
                        if (entries.any { it.widget?.widgetId == id }) entries else entries + PopupItem("widget:$id", layout)
                    }
                    // 配置页（fire-and-forget，与 addHomeWidget 同语义）
                    val fresh = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
                    if (fresh?.configure != null) {
                        runCatching {
                            context.startActivity(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                                .setClassName(fresh.configure.packageName, fresh.configure.className)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { ModLog.e("GraceHomeViewModel popup widget configure launch failed", it) }
                    }
                    true
                }.getOrElse { error ->
                    ModLog.e("GraceHomeViewModel addPopupWidgetTo failed", error); false
                }
            }
            _widgetSetupResults.tryEmit(success)
        }
    }

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
        _uiState.update { it.copy(favoriteOrder = refreshed) }
    }

    /** P1 空态恢复入口：清空已存收藏并重新按默认规则初始化（dialer/相机等前 6 个）。 */
    fun resetFavorites() {
        favoritesStore.reset()
        val refreshed = favoritesStore.favoritesFor(_uiState.value.apps)
        _uiState.update { it.copy(favoriteOrder = refreshed) }
    }

    /**
     * P1 演示入口（时钟长按）：循环切换 ClockLayout。P3 起落 Room
     * launcher_settings.clockStyleJson（mutateSettings 事务），与原版
     * LauncherViewModel.applyClockStyle 保存路径对齐。每个 layout 的
     * face 数据随 ClockStyle.copy 保留（原版 per-layout draft 语义）。
     */
    fun cycleClockLayout() {
        val nextLayout = ClockLayout.entries[(_uiState.value.settings.clockStyle.layout.ordinal + 1) % ClockLayout.entries.size]
        updateSettings { it.copy(clockStyle = it.clockStyle.copy(layout = nextLayout)) }
    }

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

    /**
     * B6.1 权限引导意图（原版 MediaAccess.settingsIntent 逐字）。hotfix2：启动改由
     * Route 层用【宿主 Activity】执行 —— hotfix1 前这里用 Application context +
     * NEW_TASK 把系统设置开到独立 task，MIUI 上该路径设置页秒退回桌面（装机实锤）。
     */
    fun mediaAccessSettingsIntent(): Intent = MediaAccess.settingsIntent(getApplication<Application>())

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

    /** B6.2 权限引导：宿主 manifest 是否声明 READ_CALENDAR（声明了才有授权入口）。 */
    fun hasCalendarPermissionEntry(): Boolean = runCatching {
        val context = getApplication<Application>()
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(Manifest.permission.READ_CALENDAR) == true
    }.getOrDefault(false)

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

    fun onBridgeWeather(weather: WeatherState) {
        _uiState.update { it.copy(weather = weather) }
    }

    // ---- 命令上行（宿主 → 模块 App 进程；命令在持有 token 的进程内执行）----

    /** 上游同步 v1.1.8 S3：无障碍系统动作（锁屏/拉通知栏/拉快捷设置）跨进程委派
     *  —— 服务在模块 App 进程，宿主进程手势经 GRACE 桥触发（幂等，未连接安全丢弃）。 */
    fun sendSystemAction(action: com.galaxyrio.gracelauncher.data.GraceButtonAction) {
        sendBridgeCommand(com.homemode4miui.bridge.GraceBridge.Cmd.SYSTEM_ACTION, arg1 = action.name)
    }

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

    /** B6.2：天气刷新请求（模块进程重读 Breezy provider）。 */
    fun refreshWeather() {
        sendBridgeCommand(GraceBridge.Cmd.WEATHER_REFRESH)
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
        hiddenAppsJob?.cancel()
        foldersJob?.cancel()
        savedShortcutsJob?.cancel()
        runCatching { shortcutRepository.close() }
            .onFailure { ModLog.e("GraceHomeViewModel shortcutRepository.close failed", it) }
        runCatching { getApplication<Application>().unregisterReceiver(packageReceiver) }
            .onFailure { ModLog.e("GraceHomeViewModel unregister receiver failed", it) }
        runCatching { getApplication<Application>().unregisterReceiver(bridgeReceiver) }
            .onFailure { ModLog.e("GraceHomeViewModel unregister bridge receiver failed", it) }
    }
}
