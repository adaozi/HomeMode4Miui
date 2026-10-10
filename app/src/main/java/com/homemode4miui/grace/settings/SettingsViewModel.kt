/*
 * C 组（C1/C3）模块进程设置 ViewModel —— 新建文件（非搬运）。
 *
 * 语义对齐原版 LauncherViewModel 的设置面（GPL-3.0），但数据通路换桥：
 *   - 读：GraceSettingsSnapshotStore（宿主快照）+ 模块本地查询（应用列表/图标包/天气/
 *     快捷方式 —— PackageManager/LauncherApps/Breezy provider 在模块进程可直接访问）；
 *   - 写：LauncherActions.updateSettings 等 → 乐观更新本地状态 + GraceSettingsBridge
 *     命令广播 → 宿主 GraceSettingsHostEngine 落 Room/SP → 快照回推（权威态收敛）。
 *
 * 降级登记（本阶段明确不做，详见 PORTING_GraceLauncher.md C 组验收节）：
 *   - 快照超时（桌面未挂载/广播被拦）→ settingsLoadFailed → 设置页整体只读
 *     （SettingsStorageState.canEdit 语义，与原版"Room 未 seed 不初始化草稿"同构）；
 *   - 图标设计器图片导入（importItemIcon）：图片文件必须落【宿主】filesDir 才能被
 *     宿主渲染，模块进程导入无意义 → 返回 false（UI 走 icon_edit_failed 提示）；
 *   - 私人空间建档/解锁（requestPrivateSpace）：需宿主 Activity 鉴权 → 乐观回调，
 *     编辑器 app 列表空（privateApps 不跨桥）；ps 状态行数据来自快照；
 *   - 弹层 widget 项（addPopupWidget/addWidget）：宿主 widget host 特有 → no-op；
 *   - 快捷方式查询（shortcuts/cachedShortcuts）：模块进程无 shortcut host 权限 →
 *     DefaultLauncherRequired（UI 显示"设为默认桌面"提示，与原版语义一致）。
 */
package com.homemode4miui.grace.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyrio.gracelauncher.data.AppRepository
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.PrivateSpaceDefaultName
import com.galaxyrio.gracelauncher.data.ShortcutRepository
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.weather.BreezyWeatherRepository
import com.galaxyrio.gracelauncher.platform.PrivateSpaceController
import com.galaxyrio.gracelauncher.platform.PrivateSpaceState
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.ScheduleStatus
import com.homemode4miui.bridge.GraceSettingsBridge
import com.homemode4miui.bridge.GraceSettingsSnapshotStore
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appRepository = AppRepository(application)
    private val iconPacksRepository = IconPackRepository(application)
    private val weatherRepository = BreezyWeatherRepository(application)
    private val shortcutRepository = ShortcutRepository(application)
    private val settingsWriteMutex = Mutex()

    /** 快照到达前 true；超时仍无快照 → settingsLoadFailed（只读面）。 */
    private val _uiState = MutableStateFlow(LauncherUiState(isLoadingSettings = true))
    val uiState = _uiState.asStateFlow()

    /** hotfix2：跨进程 UI 动作后关闭设置页回桌面（moveWidget 请求桌面 editingHome）。 */
    private val _closeRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val closeRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _closeRequests

    init {
        GraceSettingsSnapshotStore.ensureRegistered(application)
        GraceSettingsSnapshotStore.requestSnapshot(application)
        // folders 阶段（hotfix folders2）：快捷方式代查回包接收（同快照生命周期）
        com.homemode4miui.bridge.GraceShortcutsRelay.ensureRegistered(application)

        viewModelScope.launch {
            // 快照超时（≈4s）：桌面未挂载/宿主被杀 → 只读。迟到快照到达后自然清除。
            delay(4_000)
            if (GraceSettingsSnapshotStore.snapshot.value == null) {
                ModLog.i("SettingsViewModel: snapshot timeout, settings read-only")
                _uiState.update { it.copy(isLoadingSettings = false, settingsLoadFailed = true) }
            }
        }
        viewModelScope.launch {
            GraceSettingsSnapshotStore.snapshot.collect { snapshot ->
                if (snapshot == null) return@collect
                applySnapshot(snapshot)
            }
        }
        refreshApps()
        refreshIconPacks()
        refreshWeather()
    }

    override fun onCleared() {
        GraceSettingsSnapshotStore.unregister(getApplication())
        com.homemode4miui.bridge.GraceShortcutsRelay.unregister(getApplication())
        super.onCleared()
    }

    fun refreshApps() {
        viewModelScope.launch {
            runCatching {
                val apps = appRepository.loadApps(_uiState.value.settings.enabledIconPackPackages)
                _uiState.update { it.copy(apps = apps, isLoadingApps = false, appLoadFailed = false) }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                ModLog.e("SettingsViewModel: app load failed", error)
                _uiState.update { it.copy(isLoadingApps = false, appLoadFailed = true) }
            }
        }
    }

    fun refreshIconPacks() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingIconPacks = true) }
            val packs = runCatching { iconPacksRepository.installedPacks() }.getOrDefault(emptyList())
            _uiState.update { it.copy(iconPacks = packs, isLoadingIconPacks = false, iconPacksLoadFailed = false) }
        }
    }

    fun refreshWeather() {
        viewModelScope.launch {
            val state = runCatching {
                weatherRepository.load(_uiState.value.settings.weatherLocationId)
            }.getOrElse { _uiState.value.weather }
            _uiState.update { it.copy(weather = state) }
        }
    }

    /** 快照 → uiState（模块本地查询面保留，桥面覆盖）。 */
    private fun applySnapshot(snapshot: GraceSettingsBridge.Snapshot) {
        val previousWeatherLocationId = _uiState.value.settings.weatherLocationId
        _uiState.update { current ->
            current.copy(
                isLoadingSettings = false,
                settingsLoadFailed = !snapshot.ok,
                settingsSaveFailed = false,
                settings = snapshot.settings,
                hiddenAppKeys = snapshot.hiddenAppKeys,
                folders = snapshot.folders,
                // folders 阶段：宿主快照 ok = 桌面已挂载 = 宿主持 HOME 角色 → 快捷方式
                // 代查（SHORTCUTS_QUERY）预期可达；真正的查询失败在 shortcutsFor 降级
                hasShortcutAccess = snapshot.ok,
                popups = snapshot.popupsJson.mapValues { (_, json) ->
                    runCatching { PopupItem.decode(json) }.getOrDefault(emptyList())
                },
                itemIcons = snapshot.itemIcons,
                itemRevision = current.itemRevision + 1,
                favoriteKeys = snapshot.favoriteKeys,
                favoriteOrder = snapshot.favoriteOrder,
                textMode = snapshot.textMode,
                themedIcons = snapshot.themedIcons,
                categories = snapshot.categories,
                privateSpace = snapshot.privateSpace?.let {
                    PrivateSpaceState(supported = it.supported, user = null, serial = it.serial,
                        locked = it.locked, accessible = it.accessible, revision = it.revision, lockVersion = it.lockVersion)
                } ?: current.privateSpace,
            )
        }
        // 图标包顺序可能变了 → 应用列表装饰链刷新
        refreshApps()
        // C 组（C4）：weatherLocationId 变化 → 触发本进程桥引擎按首选位置重拉天气并下发宿主
        if (previousWeatherLocationId != snapshot.settings.weatherLocationId) {
            com.homemode4miui.bridge.GraceBridgeEngine.start(getApplication())
            com.homemode4miui.bridge.GraceBridgeEngine.refreshWeather()
        }
    }

    // ================= 写路径（乐观更新 + 桥命令）=================

    val actions = LauncherActions(
        requestDefaultHome = {},
        requestPrivateSpace = { _, onResult -> onResult() }, // 降级：v1 无建档面（见头注）
        closePrivateSpace = { send(GraceSettingsBridge.Cmd.PS_CLOSE) },
        openPrivateSpaceSettings = {},
        reorderPrivateApps = {},        // 降级：私人空间 app 序不跨桥（见头注）
        resetPrivateSpaceAppearance = sendAndTrue {
            mutateSettingsDirect { it.copy(privateSpace = it.privateSpace.copy(name = PrivateSpaceDefaultName)) }
        },
        appInfo = ::openAppDetails,
        screenTime = ::openAppDetails,
        uninstall = { app -> runCatching {
            getApplication<Application>().startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } },
        // hotfix folders2：原版 renameApp 逐字分支（LauncherViewModel.kt:589-596）——
        // 文件夹名存 folders 表（Room），SP label: 重命名对文件夹无意义；
        //   - 普通文件夹 → UPDATE_FOLDER{name}；
        //   - 私人空间文件夹 → privateSpace.name 走设置行增列；
        //   - 其余（app/shortcut）保持 SP label 语义。
        rename = { app, label ->
            val folderId = app.folderId
            when {
                folderId == null -> send(GraceSettingsBridge.Cmd.RENAME, app.key, label)
                folderId == com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId ->
                    mutateSettingsDirect { it.copy(privateSpace = it.privateSpace.copy(
                        name = label.trim().ifBlank { com.galaxyrio.gracelauncher.data.PrivateSpaceDefaultName })) }
                else -> send(GraceSettingsBridge.Cmd.UPDATE_FOLDER,
                    json = JSONObject().put("id", folderId).put("name", label).toString())
            }
        },
        setItemIcon = { app, icon ->
            _uiState.update { state ->
                state.copy(
                    itemIcons = if (icon == null) state.itemIcons - app.key else state.itemIcons + (app.key to icon),
                    itemRevision = state.itemRevision + 1,
                )
            }
            send(GraceSettingsBridge.Cmd.SET_ITEM_ICON, app.key, json = icon?.encode())
            true
        },
        importItemIcon = { _, _ -> false }, // 降级：图片必须落宿主 filesDir（见头注）
        showShortcutInAppList = { app, show -> viewModelScope.launch { rememberShortcut(app, show) } },
        rememberShortcut = { app -> rememberShortcut(app, null) },
        updatePopup = { owner, defaults, transform ->
            val next = transform(_uiState.value.popupItems(owner, defaults))
            _uiState.update { it.copy(popups = it.popups + (owner.key to next)) }
            val keys = next.filter { it.widget == null }.map { it.key }
            send(GraceSettingsBridge.Cmd.SAVE_POPUP, owner.key, json = JSONArray(keys).toString())
        },
        // hotfix folders2：弹层 widget 宿主特有 —— 走 moveWidget 同款桥命令 + 关设置页，
        // 宿主 Route 收 ADD_POPUP_WIDGET 开 WidgetPicker 弹层模式（ownerKey 随行）
        addPopupWidget = { owner, _ ->
            send(GraceSettingsBridge.Cmd.ADD_POPUP_WIDGET, arg1 = owner.key)
            viewModelScope.launch { _closeRequests.emit(Unit) }
        },
        categorize = { app, category -> send(GraceSettingsBridge.Cmd.CATEGORIZE, app.key, category) },
        copyPackageName = { app -> copyToClipboard(app.packageName) },
        storePage = { app -> runCatching {
            getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } },
        newEvent = {},                  // v1：日程打开走宿主（B6.2 语义），设置页无入口
        openEvent = {},
        refreshAgenda = {},
        textMode = { mode -> send(GraceSettingsBridge.Cmd.TEXT_MODE, mode.name); _uiState.update { it.copy(textMode = mode) } },
        themedIcons = { enabled -> send(GraceSettingsBridge.Cmd.THEMED_ICONS, if (enabled) "1" else "0"); _uiState.update { it.copy(themedIcons = enabled) } },
        refreshIconPacks = ::refreshIconPacks,
        refreshApps = ::refreshApps,
        updateSettings = { transform ->
            viewModelScope.launch {
                val (old, new) = settingsWriteMutex.withLock {
                    val current = _uiState.value.settings
                    val next = transform(current)
                    _uiState.update { it.copy(settings = next, settingsSaveFailed = false) }
                    current to next
                }
                GraceSettingsBridge.diffRow(old, new)?.let { delta ->
                    send(GraceSettingsBridge.Cmd.MUTATE_SETTINGS, json = delta.toString())
                }
            }
        },
        applyClockStyle = { style -> applySettingsSuspend { it.copy(clockStyle = style) } },
        applyIconDesign = { choice -> applySettingsSuspend { it.copy(iconDesign = choice) } },
        deleteIconDesigns = { keys ->
            _uiState.update { it.copy(itemIcons = it.itemIcons - keys) }
            send(GraceSettingsBridge.Cmd.DELETE_ICON_DESIGNS, json = JSONArray(keys.toList()).toString())
            true
        },
        // 上游同步 v1.1.8 S5：字体管理器删除导入字体（ClockFontStore.delete，typeface 缓存同步清空）
        deleteFonts = { ids ->
            runCatching {
                com.galaxyrio.gracelauncher.data.ClockFontStore(getApplication()).delete(ids)
                true
            }.getOrDefault(false)
        },
        setHiddenApps = { keys ->
            _uiState.update { it.copy(hiddenAppKeys = keys) }
            send(GraceSettingsBridge.Cmd.SET_HIDDEN_APPS, json = JSONArray(keys.toList()).toString())
        },
        saveFolder = { folder -> send(GraceSettingsBridge.Cmd.SAVE_FOLDER, json = encodeFolder(folder)) },
        updateFolder = { id, name, placement, appListAtBottom ->
            val delta = JSONObject().put("id", id)
            name?.let { delta.put("name", it) }
            placement?.let { delta.put("placement", it.name) }
            appListAtBottom?.let { delta.put("appListAtBottom", it) }
            send(GraceSettingsBridge.Cmd.UPDATE_FOLDER, json = delta.toString())
        },
        setFolderFavorites = { selection ->
            send(GraceSettingsBridge.Cmd.SET_FOLDER_FAVORITES, json = JSONObject().apply {
                selection.forEach { (id, favorite) -> put(id, favorite) }
            }.toString())
            true
        },
        deleteFolder = { id -> send(GraceSettingsBridge.Cmd.DELETE_FOLDER, arg1 = id) },
        // hotfix folders2：快捷方式代查 —— ShortcutPickerScreen 的 load(app) 并发调用
        // 合并为一次宿主批量查询（结果缓存到本次设置页会话；图标跨桥为空，登记降级）
        shortcuts = { app -> shortcutsViaBridge(app) },
        cachedShortcuts = { _ -> null },
        prepareShortcuts = {},
        reorderFavorites = { keys ->
            _uiState.update { it.copy(favoriteOrder = keys) }
            send(GraceSettingsBridge.Cmd.REORDER_FAVORITES, json = JSONArray(keys).toString())
        },
        launchAppAt = null,
        launchSearchApp = null,
        launchShortcutAt = null,
        launchShortcut = {},
        requestMediaAccess = { openNotificationListenerSettings() },
        controlMedia = { _, _ -> },     // 媒体控制命令面属宿主（P6），设置页无播放器
        dismissMedia = { _, _ -> false },
        dismissNotification = { _, _ -> false },
        openNotification = { _, _ -> false },
        requestWeatherAccess = { refreshWeather() }, // provider permission 模块 manifest 自持
        requestCalendarAccess = {},     // 降级：日历权限在宿主授予（B6.2），设置页只切开关
        addWidget = {},                 // 降级：桌面 widget 编辑走宿主（P4）
        configureWidget = {},
        removeWidget = {},
        // hotfix2：编辑模式在桌面进程（原版同进程直改 editingHome）→ 发桥命令 +
        // 关闭设置页，用户回到桌面即见 widget 编辑态（宿主 Route 消费 MOVE_WIDGET）
        moveWidget = {
            send(GraceSettingsBridge.Cmd.MOVE_WIDGET)
            viewModelScope.launch { _closeRequests.emit(Unit) }
        },
        refreshWeather = ::refreshWeather,
        openBreezyWeather = ::openBreezy,
        installBreezyWeather = ::openBreezy,
    )

    private suspend fun applySettingsSuspend(transform: (LauncherSettings) -> LauncherSettings): Boolean =
        try {
            settingsWriteMutex.withLock {
                val current = _uiState.value.settings
                val next = transform(current)
                _uiState.update { it.copy(settings = next, settingsSaveFailed = false) }
                GraceSettingsBridge.diffRow(current, next)?.let { delta ->
                    send(GraceSettingsBridge.Cmd.MUTATE_SETTINGS, json = delta.toString())
                }
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ModLog.e("SettingsViewModel: apply failed", error)
            _uiState.update { it.copy(settingsSaveFailed = true) }
            false
        }

    private fun mutateSettingsDirect(transform: (LauncherSettings) -> LauncherSettings) {
        viewModelScope.launch { applySettingsSuspend(transform) }
    }

    /** SET_ITEM_ICON 已内联乐观路径。 */

    // ---- hotfix folders2：快捷方式代查（单飞行批 + 会话内缓存）----

    private val shortcutsMutex = Mutex()
    private var shortcutResults: Map<String, List<GraceSettingsBridge.ShortcutWire>>? = null

    private suspend fun shortcutsViaBridge(app: LauncherApp): ShortcutResult {
        if (shortcutResults == null) {
            shortcutsMutex.withLock {
                if (shortcutResults == null) {
                    val context = getApplication<Application>()
                    val relay = com.homemode4miui.bridge.GraceShortcutsRelay
                    relay.ensureRegistered(context)
                    relay.reset()
                    relay.request(context, _uiState.value.allApps.map(LauncherApp::key))
                    val batch = kotlinx.coroutines.withTimeoutOrNull(4_000) {
                        relay.results.filterNotNull().first()
                    }
                    if (batch == null) {
                        ModLog.i("SettingsViewModel: shortcuts relay timeout")
                        return ShortcutResult(ShortcutStatus.DefaultLauncherRequired)
                    }
                    shortcutResults = batch
                    // 快捷方式项在编辑器/选择器里的行标签与身份（icon 为空，登记降级）
                    _uiState.update { state -> state.copy(shortcutApps = batch.values.flatten().map { wire ->
                        val shortcut = wire.toLauncherShortcut()
                        LauncherApp(
                            componentName = shortcut.activity
                                ?: android.content.ComponentName(wire.packageName, "fallback"),
                            label = shortcut.label.ifBlank { wire.shortcutId },
                            icon = null, shortcut = shortcut,
                        )
                    }) }
                }
            }
        }
        val list = shortcutResults?.get(app.key).orEmpty()
        return ShortcutResult(ShortcutStatus.Ready, list.map { it.toLauncherShortcut() })
    }

    private fun GraceSettingsBridge.ShortcutWire.toLauncherShortcut() =
        com.galaxyrio.gracelauncher.data.LauncherShortcut(
            id = shortcutId, packageName = packageName, label = label, icon = null,
            activity = activity?.let(android.content.ComponentName::unflattenFromString),
        )

    private suspend fun rememberShortcut(app: LauncherApp, showInAppList: Boolean?): Boolean {
        val shortcut = app.shortcut ?: return false
        return try {
            settingsWriteMutex.withLock {
                _uiState.update { state ->
                    state.copy(shortcutApps = state.shortcutApps.filterNot { it.key == app.key } + app)
                }
                val entityJson = JSONObject()
                    .put("itemKey", app.key)
                    .put("packageName", shortcut.packageName)
                    .put("shortcutId", shortcut.id)
                    .put("activity", (shortcut.activity ?: app.componentName).flattenToString())
                    .put("label", app.originalLabel)
                    .put("showInAppList", showInAppList ?: false)
                send(GraceSettingsBridge.Cmd.REMEMBER_SHORTCUT, arg1 = app.key, json = entityJson.toString())
            }
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ModLog.e("SettingsViewModel: rememberShortcut failed", error)
            _uiState.update { it.copy(settingsSaveFailed = true) }
            false
        }
    }

    // ================= 杂项 =================

    private fun send(cmd: String, arg1: String? = null, arg2: String? = null, json: String? = null) {
        runCatching {
            getApplication<Application>().sendBroadcast(GraceSettingsBridge.command(cmd, arg1, arg2, json))
        }.onFailure { ModLog.e("SettingsViewModel: send $cmd failed", it) }
    }

    private fun sendAndTrue(block: () -> Unit): suspend () -> Boolean = {
        block(); true
    }

    private fun openAppDetails(app: LauncherApp) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { ModLog.e("SettingsViewModel: appInfo failed", it) }
    }

    private fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("package", text))
        }
    }

    private fun openNotificationListenerSettings() {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openBreezy() {
        val context = getApplication<Application>()
        val pkg = weatherRepository.installedPackage() ?: run {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=org.breezyweather")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        runCatching { context.startActivity(context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun encodeFolder(folder: LauncherFolder): String = JSONObject()
        .put("id", folder.id).put("name", folder.name)
        .put("placement", folder.placement.name)
        .put("appListAtBottom", folder.appListAtBottom)
        .put("keys", JSONArray(folder.appKeys))
        .toString()

    private fun List<LauncherApp>.orderedBy(keys: List<String>): List<LauncherApp> {
        val byKey = associateBy(LauncherApp::key)
        return (keys.mapNotNull(byKey::get) + filterNot { it.key in keys.toSet() })
    }
}
