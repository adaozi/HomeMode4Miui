/*
 * C 组（C1/C3）模块进程设置 ViewModel —— 新建文件（非搬运）。
 *
 * 语义对齐原版 LauncherViewModel 的设置面（GPL-3.0），但数据通路换桥：
 *   - 读：GraceSettingsSnapshotStore（宿主快照）+ 模块本地查询（应用列表/图标包/天气/
 *     快捷方式 —— PackageManager/LauncherApps/小米天气 provider 在模块进程可直接访问）；
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
import com.galaxyrio.gracelauncher.data.weather.MiuiWeatherRepository
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
    private val shortcutRepository = ShortcutRepository(application)
    private val settingsWriteMutex = Mutex()

    /** 快照到达前 true；超时仍无快照 → settingsLoadFailed（只读面）。 */
    private val _uiState = MutableStateFlow(LauncherUiState(
        isLoadingSettings = true,
        // 上游同步 v1.1.8 S8b（LauncherViewModel.kt:254）：Grace 按钮虚拟项注入。
        // IconDesignerSettings 经 findItem 解析后进入按钮特化面；仅为身份载体，
        // 预览位图由 IconDesigner 经 ItemIconStore.layers 现算（不依赖此处 icon）。
        graceButtonApp = com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.item(application),
    ))
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
                // 上游同步 v1.1.8 S8b：工作资料枚举（仅 managed profile，MIUI 双开不受影响）
                val workProfiles = appRepository.workProfiles()
                val workSerials = workProfiles.map { it.serial }.toSet()
                val workProfileApps = if (workProfiles.isNotEmpty()) {
                    appRepository.loadWorkApps(workProfiles, _uiState.value.settings.enabledIconPackPackages)
                } else emptyList()
                // 主列表收编：managed profile 应用排除（已进工作文件夹），MIUI 双开（clone）保留
                val personal = apps.filterNot { it.userSerial != null && it.userSerial in workSerials }
                _uiState.update { it.copy(apps = personal, isLoadingApps = false, appLoadFailed = false,
                    workProfiles = workProfiles, workProfileApps = workProfileApps) }
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

    /**
     * B6.2：天气数据已上移宿主进程（小米天气 provider 白名单拒绝模块 App 进程，
     * 装机实锤 hasAccessProvider=false），本进程不再直读 —— 刷新 = 发宿主命令，
     * 新天气经 GraceSettingsBridge.Snapshot.weatherJson 回带（applySnapshot 解出）。
     */
    fun refreshWeather() {
        // 天气读取在宿主进程；新数据装载完成后宿主主动推快照回带（applySnapshot 解出）
        send(GraceSettingsBridge.Cmd.WEATHER_REFRESH)
    }

    /** 快照 → uiState（模块本地查询面保留，桥面覆盖）。 */
    private fun applySnapshot(snapshot: GraceSettingsBridge.Snapshot) {
        val previousWeatherLocationId = _uiState.value.settings.weatherLocationId
        // B6.2：天气随快照回带（宿主进程读取；null = 宿主尚无数据，保持现状）
        val bridgedWeather = snapshot.weatherJson?.let { json ->
            runCatching { com.homemode4miui.bridge.GraceBridge.decodeWeatherWire(json) }.getOrNull()
        }
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
                weather = bridgedWeather ?: current.weather,
            )
        }
        // 图标包顺序可能变了 → 应用列表装饰链刷新
        refreshApps()
        // C 组（C4）：weatherLocationId 变化 → 宿主天气引擎按首选城市重读（新数据随快照回带）
        if (previousWeatherLocationId != snapshot.settings.weatherLocationId) {
            send(GraceSettingsBridge.Cmd.WEATHER_REFRESH)
        }
    }

    // ================= 写路径（乐观更新 + 桥命令）=================

    val actions = LauncherActions(
        requestPrivateSpace = { _, onResult -> onResult() }, // 降级：v1 无建档面（见头注）
        closePrivateSpace = { send(GraceSettingsBridge.Cmd.PS_CLOSE) },
        // 审计修缮（2026-10-16）：接上现成执行端（此前 no-op，私密空间「系统设置」点击无反应）
        openPrivateSpaceSettings = {
            com.galaxyrio.gracelauncher.platform.PrivateSpaceProbe.openSettings(getApplication())
        },
        reorderPrivateApps = {},        // 降级：私人空间 app 序不跨桥（见头注）
        resetPrivateSpaceAppearance = sendAndTrue {
            mutateSettingsDirect { it.copy(privateSpace = it.privateSpace.copy(name = PrivateSpaceDefaultName)) }
        },
        appInfo = ::openAppDetails,
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
        // 审计修缮（2026-10-16）：移除 categorize/copyPackageName/storePage/newEvent/
        // openEvent/refreshAgenda/themedIcons 赋值 —— 对应 LauncherActions 字段零消费已删。
        textMode = { mode -> send(GraceSettingsBridge.Cmd.TEXT_MODE, mode.name); _uiState.update { it.copy(textMode = mode) } },
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
                    // S8b 诊断：设置侧发出的增量 keys
                    com.homemode4miui.util.ModLog.i(
                        "GraceSettings diffRow keys=" + delta.keys().asSequence().toList().toString())
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
        // 上游同步 v1.1.8 S8b：工作资料应用排序（workProfile.appOrder，经桥落 Room workProfileJson）
        reorderWorkApps = { keys ->
            // mutateSettingsDirect：设置进程直写（appOrder 经桥 workProfileJson delta 落 Room）
            mutateSettingsDirect { it.copy(workProfile = it.workProfile.copy(appOrder = keys)) }
            true
        },
        resetWorkProfileAppearance = sendAndTrue {
            mutateSettingsDirect { it.copy(workProfile = it.workProfile.copy(appOrder = emptyList())) }
        },
        openWorkProfileSettings = {
            // MIUI/Android 无统一工作资料设置入口，跳系统设置（登记：后续可细化 per-profile 页）
            runCatching {
                getApplication<Application>().startActivity(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
        // 审计修缮（2026-10-16）：移除 prepareShortcuts/reorderFavorites/launchAppAt/
        // launchSearchApp 赋值 —— 字段零消费已删（收藏重排在宿主 shim 面直走
        // GraceHomeViewModel.reorderFavorites，不经桥）。
        launchShortcutAt = null,
        launchShortcut = {},
        requestMediaAccess = { openNotificationListenerSettings() },
        requestWeatherAccess = { refreshWeather() }, // provider permission 模块 manifest 自持
        // 上游同步补漏：原为 no-op 占位（"桌面 widget 编辑走宿主"），效率页
        // 「添加自定义小组件」点击无反应。对齐 moveWidget 跨进程模式：发命令 +
        // 关设置页，桌面 Route 收 ADD_WIDGET 开 WidgetPicker。
        addWidget = {
            send(GraceSettingsBridge.Cmd.ADD_WIDGET)
            viewModelScope.launch { _closeRequests.emit(Unit) }
        },
        configureWidget = {},
        removeWidget = {},
        // hotfix2：编辑模式在桌面进程（原版同进程直改 editingHome）→ 发桥命令 +
        // 关闭设置页，用户回到桌面即见 widget 编辑态（宿主 Route 消费 MOVE_WIDGET）
        moveWidget = {
            send(GraceSettingsBridge.Cmd.MOVE_WIDGET)
            viewModelScope.launch { _closeRequests.emit(Unit) }
        },
        refreshWeather = ::refreshWeather,
        openWeatherApp = ::openWeatherApp,
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

    private fun openWeatherApp() {
        // 天气读取在宿主进程，但拉起入口仍在模块进程（MAIN/LAUNCHER queries 覆盖可见性）
        val context = getApplication<Application>()
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(MiuiWeatherRepository.PACKAGE_NAME)
        }.getOrNull()
        if (launch != null) {
            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } else {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=${MiuiWeatherRepository.PACKAGE_NAME}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun encodeFolder(folder: LauncherFolder): String = JSONObject()
        .put("id", folder.id).put("name", folder.name)
        .put("placement", folder.placement.name)
        .put("appListAtBottom", folder.appListAtBottom)
        .put("keys", JSONArray(folder.appKeys))
        .toString()

}
