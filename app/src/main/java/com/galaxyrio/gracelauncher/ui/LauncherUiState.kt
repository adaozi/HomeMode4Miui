/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/LauncherViewModel.kt
 *   （LauncherUiState + ScheduleStatus 两个顶层声明，原文件 851 行）
 * C 组移植（C1）—— 设置页（模块 App 进程 SettingsActivity）与宿主收藏编辑器
 *   （B4.3 解锁）共用同一个 LauncherUiState/LauncherActions 表面，设置面板零改签名。
 *
 * 填充方差异：
 *   - 模块进程 = grace/settings/SettingsViewModel（跨进程桥快照 + 模块本地查询）；
 *   - 宿主进程 = grace/GraceFavoritesShim（GraceHomeViewModel 适配，仅收藏编辑面）。
 *
 * 与原版的差异（裁剪登记）：
 *   - appLaunchTransition / 无关 VM 方法不搬（这里只有状态模型）；
 *   - 派生属性全量保留（findItem/allApps/favoriteItems/popupItems 等设置页全消费）；
 *   - LauncherApp 不带桌面动画上下文，与原版语义一致（纯数据）。
 */
package com.galaxyrio.gracelauncher.ui

import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherAppOrder
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId
import com.galaxyrio.gracelauncher.data.ProfileSettings
import com.galaxyrio.gracelauncher.data.RecentlyInstalledFolderId
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.data.icons.IconPackInfo
import com.galaxyrio.gracelauncher.data.icons.IconPackStatus
import com.galaxyrio.gracelauncher.data.media.MediaSnapshot
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.weather.WeatherState
import com.galaxyrio.gracelauncher.platform.PrivateSpaceState

enum class ScheduleStatus {
    PermissionRequired,
    Loading,
    Ready,
    Error,
}

data class LauncherUiState(
    val apps: List<LauncherApp> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val isLoadingApps: Boolean = true,
    val appLoadFailed: Boolean = false,
    val events: List<ScheduleEvent> = emptyList(),
    val scheduleStatus: ScheduleStatus = ScheduleStatus.PermissionRequired,
    val hasShortcutAccess: Boolean = false,
    val categories: Map<String, String> = emptyMap(),
    val textMode: WallpaperTextMode = WallpaperTextMode.Auto,
    val themedIcons: Boolean = true,
    val settings: LauncherSettings = LauncherSettings(),
    val hiddenAppKeys: Set<String> = emptySet(),
    val folders: List<LauncherFolder> = emptyList(),
    val isLoadingSettings: Boolean = false,
    val settingsLoadFailed: Boolean = false,
    val settingsSaveFailed: Boolean = false,
    val iconPacks: List<IconPackInfo> = emptyList(),
    val isLoadingIconPacks: Boolean = false,
    val iconPacksLoadFailed: Boolean = false,
    val iconPackStatus: IconPackStatus = IconPackStatus.System,
    // Unknown until the first system query, avoiding a banner flash for an existing default.
    val isDefaultHome: Boolean? = null,
    val media: MediaSnapshot = MediaSnapshot(),
    val notifications: Map<String, List<AppNotification>> = emptyMap(),
    val favoriteOrder: List<String> = emptyList(),
    val weather: WeatherState = WeatherState(),
    val shortcutApps: List<LauncherApp> = emptyList(),
    val folderApps: List<LauncherApp> = emptyList(),
    val popups: Map<String, List<PopupItem>> = emptyMap(),
    val itemIcons: Map<String, ItemIcon> = emptyMap(),
    val itemRevision: Int = 0,
    val privateSpace: PrivateSpaceState = PrivateSpaceState(),
    val privateApps: List<LauncherApp> = emptyList(),
    val privateAppsLoading: Boolean = false,
    val privateAppsFailed: Boolean = false,
    val privateFolderApp: LauncherApp? = null,
    val shownShortcutKeys: Set<String> = emptySet(),
) {
    // 上游同步 v1.1.8（LauncherViewModel.notificationsFor 逐字语义，落到本仓 UiState）：
    // 通知预览按 ShortcutsFoldersSettings 的静默/普通显示策略过滤；快捷方式/文件夹/
    // 其他 profile 行不显示预览。
    fun notificationsFor(app: LauncherApp, expanded: Boolean): List<AppNotification> =
        if (app.shortcut != null || app.user != null || app.folderId != null) emptyList()
        else notifications[app.packageName].orEmpty().filter { settings.shortcutsFolders.showsNotification(it.silent, expanded) }

    fun findItem(key: String): LauncherApp? = apps.firstOrNull { it.key == key }
        ?: shortcutApps.firstOrNull { it.key == key && (!it.isPrivateSpace || privateContentVisible) }
            ?.let { if (it.isPrivateSpace) it.copy(showPrivateIndicator = settings.privateSpace.showIndicator) else it }
        ?: privateSpaceApps.firstOrNull { it.key == key }
        ?: privateFolder.takeIf { it.key == key }?.let(::folderItem)
        ?: folders.firstOrNull { it.key == key }?.let(::folderItem)

    val privateFolder: LauncherFolder get() = settings.privateSpace.folder(privateSpaceApps)
    val privateContentVisible: Boolean get() = !isLoadingSettings && !settingsLoadFailed && settings.privateSpace.enabled &&
        privateSpace.user != null && (settings.privateSpace.exposesApps || (privateSpace.accessible && !privateSpace.locked))
    val privateSpaceApps: List<LauncherApp> get() = if (privateContentVisible)
        settings.privateSpace.orderedApps(privateApps).map { it.copy(showPrivateIndicator = settings.privateSpace.showIndicator) } else emptyList()
    private val publicPrivateShortcuts: List<LauncherApp> get() = if (settings.privateSpace.exposesApps && privateContentVisible)
        shortcutApps.filter { it.isPrivateSpace && it.key in shownShortcutKeys }.map { it.copy(showPrivateIndicator = settings.privateSpace.showIndicator) } else emptyList()
    /** Public surfaces include private apps only after the user opts out of launcher protection. */
    val allApps: List<LauncherApp> get() = (apps + (if (settings.privateSpace.exposesApps) privateSpaceApps else emptyList()) + publicPrivateShortcuts)
        .distinctBy(LauncherApp::key).sortedWith(LauncherAppOrder)

    // Hidden apps remain available in folders. Profile-protected apps and saved
    // shortcuts are not installations in this personal-profile collection.
    val recentlyInstalledApps: List<LauncherApp> get() = apps.asSequence()
        .filter { it.shortcut == null && it.folderId == null && !it.isPrivateSpace && !it.isLauncherSettings }
        .sortedWith(compareByDescending<LauncherApp> { it.firstInstallTime }.then(LauncherAppOrder))
        .distinctBy(LauncherApp::packageName).take(8).toList()

    fun recentlyInstalledFolder(name: String) = LauncherFolder(
        RecentlyInstalledFolderId, name, recentlyInstalledApps.map(LauncherApp::key), FolderPlacement.AppList,
    )

    fun folderItem(folder: LauncherFolder): LauncherApp = ((if (folder.id == PrivateSpaceFolderId) privateFolderApp
        else folderApps.firstOrNull { it.folderId == folder.id })
        ?: folder.asApp()).copy(label = folder.name, originalLabel = folder.name)

    fun popupItems(owner: LauncherApp, defaults: List<LauncherApp>): List<PopupItem> {
        if (owner.folderId == RecentlyInstalledFolderId) return recentlyInstalledApps.map { PopupItem(it.key) }
        val items = popups[owner.key].takeUnless { owner.folderId == PrivateSpaceFolderId }
            ?: (if (owner.folderId == PrivateSpaceFolderId) privateSpaceApps.map { it.key }
                else if (owner.folderId == null) defaults.map { it.key }
                else folders.firstOrNull { it.id == owner.folderId }?.appKeys.orEmpty()).distinct().map { PopupItem(it) }
        return if (owner.folderId != PrivateSpaceFolderId && !settings.privateSpace.exposesApps)
            items.filterNot { it.key.startsWith("profile:") } else items
    }
    val homeMedia: NowPlaying?
        get() = media.nowPlaying.takeIf {
            settings.mediaPlayer && media.hasAccess && !isLoadingSettings && !settingsLoadFailed
        }

    /** Hide Apps affects only the alphabetical app list, not other launcher surfaces. */
    val appListApps: List<LauncherApp>
        get() = if (isLoadingSettings || settingsLoadFailed) emptyList()
        else (if (settings.privateSpace.display == PrivateSpaceDisplay.NormalApp) allApps else apps + publicPrivateShortcuts)
            .filterNot { it.key in hiddenAppKeys }

    val favoriteApps: List<LauncherApp>
        get() {
            if (isLoadingSettings || settingsLoadFailed) return emptyList()
            val favorites = (allApps + shortcutApps.filter { !it.isPrivateSpace || (settings.privateSpace.exposesApps && privateContentVisible) })
                .filter { it.key in favoriteKeys }.associateBy(LauncherApp::key)
            return (favoriteOrder + favorites.keys).distinct().mapNotNull(favorites::get)
        }

    /** Folder placement owns membership; the shared order only determines position. */
    val favoriteItems: List<LauncherApp>
        get() {
            if (isLoadingSettings || settingsLoadFailed) return emptyList()
            val items = (favoriteApps + folders.filter { it.placement.inFavorites }.map(::folderItem))
                .associateBy(LauncherApp::key)
            // Existing folders keep their old position at the end until reordered.
            return (favoriteOrder + items.keys).distinct().mapNotNull(items::get)
        }
}
