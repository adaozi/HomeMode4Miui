/*
 * 设置域（GraceHomeViewModel 拆分第 7 步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：settingsRepository / favoritesStore / settingsJob / hiddenAppsJob / foldersJob /
 *        lastIconPacks（改为本类内部 lastSeenPacks）
 *   状态：settings / settingsRevision / hiddenAppKeys / folders / folderWidgets / popups
 *   方法：updateSettings / onSettingsMutated / toggleFavorite / dropFavoriteKey /
 *        reorderFavorites / setFolderFavorites / recordSearchApp / loadIconPacks / setIconPack
 *
 * 这一域是全仓的**公共写通道**：widget 的 homeLayout、搜索最近使用、图标包选择
 * 都通过 updateSettings 落 Room。所以它对外只暴露一个 `update(transform)`，
 * 其他域以 lambda 注入（见 VM 的 WidgetSlot 构造）。
 *
 * 三条 Room 观察流（设置行 / 隐藏应用 / 文件夹+弹层）都在 start() 里启动，
 * 失败各自降级（抽屉退化为不过滤 / 文件夹空态），不阻塞主链路。
 */
package com.homemode4miui.grace

import android.app.Application
import com.galaxyrio.gracelauncher.data.FavoritesStore
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.GraceHomeSettings
import com.galaxyrio.gracelauncher.data.GraceSettingsRepository
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherSettingsRepository
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.icons.IconPackInfo
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class SettingsSync(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
    /** 共享数据层实例（AppsLoader 也要读设置流与收藏序，实例由 VM 统一持有）。 */
    private val settingsRepository: GraceSettingsRepository,
    private val favoritesStore: FavoritesStore,
    /** 图标包顺序变化 → 通知 AppsLoader 重刷（装饰跟随包顺序）。 */
    private val onIconPacksChanged: () -> Unit,
    /** 已安装图标包查询（AppRepository 能力，由 VM 注入以免两处各建一个 AppRepository）。 */
    private val installedIconPacks: suspend () -> List<IconPackInfo>,
) {
    private var settingsJob: Job? = null
    private var hiddenAppsJob: Job? = null
    private var foldersJob: Job? = null

    /**
     * 上次观察到的图标包顺序。
     * 原实现由 refreshApps 写 `lastIconPacks` 再被设置流读取 —— 跨域共享字段；
     * 这里改为本类自己记录观察值（首次不触发，之后变化才通知重刷，语义等价且更直接）。
     */
    private var lastSeenPacks: List<String>? = null

    /** null = 加载中。原版 installedPacks（adw/nova/go/lawnchair 等 discovery intents）。 */
    private val _iconPacks = MutableStateFlow<List<IconPackInfo>?>(null)
    val iconPacks = _iconPacks.asStateFlow()

    /** VM init 调用：启动三条 Room 观察流。 */
    fun start() {
        // P3：Room 设置接管 —— SP seed 迁移（一次性）+ 行变化流常驻跟随
        settingsJob = scope.launch {
            val initial = settingsRepository.loadInitial()
            uiState.update { it.copy(settings = initial) }
            settingsRepository.observeSettings().collect { observed ->
                // S8b 诊断：宿主 settings 流（graceButton.enabled 跟随验证）
                ModLog.i(
                    "GraceSettings observe graceButton.enabled=" + observed.graceButton.enabled +
                        " tap=" + observed.graceButton.tap.action.name +
                        " longPress=" + observed.graceButton.longPress.action.name +
                        " doubleTap.enabled=" + observed.graceButton.doubleTap.enabled)
                uiState.update { it.copy(settings = observed) }
                // B6.4：图标包顺序变化 → 重刷（设置流驱动，幂等）
                val packs = observed.enabledIconPackPackages
                if (lastSeenPacks != null && packs != lastSeenPacks) onIconPacksChanged()
                lastSeenPacks = packs
            }
        }
        // hotfix7：隐藏应用键跟随（与 LauncherSettingsRepository 同源 DAO；
        // 观察失败不阻塞主链路 —— 抽屉退化为不过滤）
        hiddenAppsJob = scope.launch {
            try {
                val flow = LauncherDatabase.getInstance(application).settingsDao().observeHiddenApps()
                flow.collect { keys ->
                    uiState.update { it.copy(hiddenAppKeys = keys.toSet()) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("SettingsSync observeHiddenApps failed", error)
            }
        }
        // folders 阶段：文件夹跟随（成员关系存于 popup items JSON，owner = "folder:<id>"，
        // 原版 LauncherSettingsRepository.snapshots :18-33 逐字映射；观察失败不阻塞主链路）
        foldersJob = scope.launch {
            try {
                val database = LauncherDatabase.getInstance(application)
                combine(
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
                }.collect { (folders, widgets, popups) ->
                    uiState.update {
                        it.copy(folders = folders, folderWidgets = widgets, popups = popups)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("SettingsSync observeFolders failed", error)
            }
        }
    }

    /** P3：写路径切 Room（GraceSettingsRepository.mutateSettings 事务；DB 故障内部降级 SP）。 */
    fun update(transform: (GraceHomeSettings) -> GraceHomeSettings) {
        scope.launch {
            val next = settingsRepository.mutateSettings(transform)
            uiState.update { it.copy(settings = next) }
        }
    }

    /** C 组（C3）：引擎落库回调 —— 设置行走 Room 观察流自动生效，这里补 SP 面（收藏序）。 */
    fun onMutated() {
        val refreshed = favoritesStore.currentOrder().orEmpty()
        uiState.update { it.copy(favoriteOrder = refreshed, settingsRevision = it.settingsRevision + 1) }
    }

    /** 原版 LauncherViewModel.toggleFavorite 的 P1 同构：SP 落盘 + StateFlow 即时刷新。 */
    fun toggleFavorite(app: LauncherApp) {
        val current = uiState.value.let { it.favoriteOrder + it.favorites.map(LauncherApp::key) }.distinct()
        val updated = favoritesStore.toggle(app.key, current)
        ModLog.i("toggleFavorite: ${app.key} shortcut=${app.shortcut != null} size=${updated.size}")
        uiState.update { it.copy(favoriteOrder = updated) }
    }

    /** 收藏顺序里摘掉某个 key（快捷方式被移除时清理脏 key；ShortcutSync 的回调）。 */
    fun dropFavoriteKey(key: String) {
        val current = uiState.value.favoriteOrder
        if (key !in current) return
        val updated = favoritesStore.toggle(key, current)
        uiState.update { it.copy(favoriteOrder = updated) }
    }

    /**
     * hotfix8：编辑收藏页重排（原版 LauncherViewModel.reorderFavorites：Store 只重排现有成员）。
     *
     * P11b：上游 1.2.2+ 收藏顺序存在 `homeLayout.homeOrder` 里（与区块同序）。此处在保持
     * 「双写单读」策略：homeOrder 为主（区块相对位置不动，收藏段换成新顺序），
     * FavoritesStore 继续镜像写入作为回退来源。
     */
    fun reorderFavorites(keys: List<String>) {
        val current = uiState.value
        val updated = favoritesStore.reorder(keys, current.favoriteOrder)
        val homeOrder = current.settings.homeLayout.homeOrder
        if (homeOrder.isNullOrEmpty()) {
            uiState.update { it.copy(favoriteOrder = updated) }
            return
        }
        val blocks = homeOrder.filter { it.startsWith("grace:") || it.startsWith("widget:") }
        val favorites = keys.filterNot { it.startsWith("grace:") || it.startsWith("widget:") }
        val merged = (blocks + favorites).distinct()
        update { it.copy(homeLayout = it.homeLayout.copy(homeOrder = merged)) }
        uiState.update { it.copy(favoriteOrder = favorites) }
    }

    /**
     * folders 阶段：编辑收藏页的文件夹收藏开关（原版 LauncherSettingsRepository.
     * setFolderFavorites 直通 —— 宿主与库同进程，无需走桥；文件夹流实时跟随）。
     */
    suspend fun setFolderFavorites(selection: Map<String, Boolean>): Boolean {
        if (selection.isEmpty()) return true
        return try {
            LauncherSettingsRepository(LauncherDatabase.getInstance(application))
                .setFolderFavorites(selection)
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ModLog.e("SettingsSync setFolderFavorites failed", error); false
        }
    }

    /** P2：搜索结果打开应用后记录最近使用（原版 LauncherViewModel.launchSearchApp 语义）。 */
    fun recordSearchApp(appKey: String) {
        update { it.copy(searchSettings = it.searchSettings.recordApp(appKey)) }
    }

    fun loadIconPacks() {
        if (_iconPacks.value != null) return
        scope.launch {
            val result = runCatching { installedIconPacks() }
                .onFailure { ModLog.e("SettingsSync loadIconPacks failed", it) }
                .getOrDefault(emptyList())
            _iconPacks.value = result
        }
    }

    /** 单选简化（原版多选 fallback 顺序随 C 组设置页恢复）：null = 系统图标。 */
    fun setIconPack(pkg: String?) {
        update { it.copy(iconPackPackages = listOfNotNull(pkg)) }
    }

    /** VM shutdown 调用：取消三条观察流（VM 每次 attach 新建，dispose 即终态）。 */
    fun dispose() {
        settingsJob?.cancel(); settingsJob = null
        hiddenAppsJob?.cancel(); hiddenAppsJob = null
        foldersJob?.cancel(); foldersJob = null
    }
}
