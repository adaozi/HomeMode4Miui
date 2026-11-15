/*
 * 桌面 UI 状态（从 GraceHomeViewModel.kt 抽出，纯移动 —— 2026-11-15 拆分第 1 步）。
 *
 * 抽出的理由：data class + 8 个派生属性占了 ViewModel 前 170 行，与逻辑无关，
 * 混在一起让「改界面状态」必须打开 1775 行的文件。语义与字段顺序完全不变。
 *
 * 注意：派生属性（favorites / appListApps / workFolder …）是 get() 计算属性，
 * **每次访问都会全量重算**。在 Composition 里访问要谨慎（见 GraceLauncherRoute
 * 的 remember key 注释：那里已改为只在源字段变化时求值）。
 */
package com.homemode4miui.grace

import com.galaxyrio.gracelauncher.data.GraceHomeSettings
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.WorkProfileFolderId
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.data.weather.WeatherState

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
     *  单段装载下 = 全量（含系统图标解码）就绪 / 装载失败（空态兜底）两条路径置 true；
     *  后续包变化刷新不重置（桌面已显示，不闪断）。首页内容（时钟/widget/收藏区/
     *  Grace 按钮）在 Route 侧按此门控统一出现。 */
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
    /**
     * 上游 v1.1.9（LauncherViewModel.shownShortcutKeys 逐字）：saved_shortcuts 里
     * showInAppList = true 的项要并入【主应用列表】（抽屉/搜索），普通"只进弹层"的
     * 快捷方式(showInAppList=false)仍只在右滑面板出现。缺失此项 = 第三方应用
     * "固定到主屏幕"的 pin 快捷方式桌面上有、应用列表里查不到（装机实锤）。
     */
    val shownShortcutKeys: Set<String> = emptySet(),
    /**
     * v2.9.141：pin 快捷方式的真实图标（itemKey → 位图）。
     * saved_shortcuts 表不存位图，此前抽屉/搜索里快捷方式一律显示"所属应用"图标
     * （用户实锤：和原生桌面上那个快捷方式图标不一样）。宿主进程持 HOME 角色，
     * syncPinnedShortcuts 用 LauncherApps.getShortcutBadgedIconDrawable 解码后存这里
     * （仅内存，冷启动/refreshApps 重新解码），UI 优先用它，取不到才回落应用图标。
     */
    val shortcutIcons: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap(),
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
     *  消失几秒是移植引入的 isLoadingApps 空化，非上游设计）。
     *  v2.9.157（User 实锤"快捷方式不能加到收藏"）：解析池补 savedShortcutApps ——
     *  此前只查 apps，快捷方式 key 永远解析不到，toggle 写进了 favoriteOrder 也显示不出来。
     *  用全量 saved（不只 showInAppList）以便把弹层里的快捷方式也收藏到首页。 */
    val favorites: List<LauncherApp>
        get() {
            if (appLoadFailed) return emptyList()
            val byKey = (apps + savedShortcutApps).associateBy(LauncherApp::key)
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
    /**
     * showInAppList 的已保存快捷方式（pin 到桌面的第三方快捷方式并入主列表）。
     * 图标：优先 shortcutIcons（syncPinnedShortcuts 解码的系统快捷方式图标，与原生
     * 桌面一致）；未解码到时才回落所属应用图标（上游 decorate 同语义）。
     */
    val shownShortcutApps: List<LauncherApp>
        get() {
            if (shownShortcutKeys.isEmpty() || savedShortcutApps.isEmpty()) return emptyList()
            return savedShortcutApps.filter { it.key in shownShortcutKeys }.map { entry ->
                if (entry.icon != null) return@map entry
                val real = shortcutIcons[entry.key]
                if (real != null) return@map entry.copy(icon = real)
                val owner = apps.firstOrNull { it.componentName == entry.componentName }
                    ?: apps.firstOrNull { it.packageName == entry.packageName }
                if (owner?.icon != null) entry.copy(icon = owner.icon) else entry
            }
        }

    /** 抽屉/搜索同源列表：真实应用 + showInAppList 快捷方式（按同一 LauncherAppOrder 归位）。 */
    val appsWithShortcuts: List<LauncherApp>
        get() {
            if (shownShortcutKeys.isEmpty()) return apps
            val extra = shownShortcutApps.filterNot { entry -> apps.any { it.key == entry.key } }
            return if (extra.isEmpty()) apps
            else (apps + extra).sortedWith(com.galaxyrio.gracelauncher.data.LauncherAppOrder)
        }

    val appListApps: List<LauncherApp>
        get() = if (appLoadFailed) emptyList()
        // 上游同步 v1.1.9：手动隐藏表 + autoHideApps 文件夹成员派生集合求并集
        else appsWithShortcuts.filterNot { it.key in effectiveHiddenAppKeys }
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
