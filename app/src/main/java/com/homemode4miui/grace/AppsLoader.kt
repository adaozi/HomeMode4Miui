/*
 * 应用装载域（GraceHomeViewModel 拆分第 8 步，也是最后一步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：appLoadJob / appLoadRetryCount / iconsJob / iconsSnapshot / itemsDecoratedWith /
 *        decorationThemeStamp / decorateMutex / appLoadGeneration / wallpaperRevision /
 *        wallpaperColorsListener / wallpaperReceiver / packageReceiver / iconStore /
 *        moduleResourceContext
 *   状态：apps / folderApps / isLoadingApps / appLoadFailed / homeReady / itemIcons / graceButtonApp
 *   方法：refreshApps / applyItemIconSnapshot / currentIconSettings(OrNull) /
 *        decorationThemeStampOf / decorateApps / decorateFolders
 *
 * ⚠️ 这是全仓最危险的一块，动手前请读完这段：
 *
 * 1. **decorateMutex + appLoadGeneration 是治"图标整批丢失"的药，不能拆散**
 *    2026-10-28 装机实锤：重启桌面后收藏有图标、抽屉全是字母占位。根因是两段式装载
 *    的 phase1 把 isLoadingApps 置 false 后，iconsJob 的重装饰抢先读 partial 列表、
 *    又在全量换入之后写回 —— 把全量结果盖回骨架，且 itemsDecoratedWith 已被标成
 *    "已装饰"，此后永不自愈。
 *    现在的纪律：**读 → 校验代际 → 写**全在 decorateMutex 内；装载换入同样在锁内。
 *    任何"为了解耦把锁拆到两个类"的改动都会把这个窗口重新打开。
 *
 * 2. **favoriteOrder 是唯一允许本类跨域写的字段**
 *    因为 favoritesFor 的默认收藏序必须和 apps 在同一次 update 里换入（否则收藏区
 *    会闪一帧空）。这是对「delegate 只写自己域」的明确例外，其余字段一律不碰。
 *
 * 3. **对 ShortcutSync 零直接依赖**
 *    refreshApps 开头要触发 pinned 对账、装饰尾部要重建快捷方式列表 —— 都用注入的
 *    lambda（onRefreshPinned / buildShortcuts），因为 ShortcutSync 反过来又要调
 *    本类的 decorate（双向依赖，见 docs/VIEWMODEL_SPLIT_PLAN.md §4-8）。
 */
package com.homemode4miui.grace

import android.app.Application
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.galaxyrio.gracelauncher.data.AppRepository
import com.galaxyrio.gracelauncher.data.FavoritesStore
import com.galaxyrio.gracelauncher.data.GraceSettingsRepository
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherPreferences
import com.galaxyrio.gracelauncher.data.SavedShortcutEntity
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import com.galaxyrio.gracelauncher.data.icons.ItemIconStore
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AppsLoader(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
    private val appRepository: AppRepository,
    private val settingsRepository: GraceSettingsRepository,
    private val favoritesStore: FavoritesStore,
    /** 装载前按系统 pinned 对账（ShortcutSync 能力；lambda 断开双向依赖）。 */
    private val onRefreshPinned: () -> Unit,
    /** 装饰后重建快捷方式列表（ShortcutSync 能力，与原列表原子换入）。 */
    private val buildShortcuts: suspend (List<SavedShortcutEntity>) -> Pair<List<LauncherApp>, Set<String>>,
) {
    private var appLoadJob: Job? = null
    /** 装载失败自愈重试计数：成功装载后归零，连败不重试（防失败-重试循环）。 */
    private var appLoadRetryCount = 0
    private var iconsJob: Job? = null

    /** 壁纸跟随失效源：Grace 按钮/themed 图标的光栅化配色在 VM 层预渲染成位图，
     *  壁纸变化后必须重渲染才跟得上主题。双通道 bump（与组合层 rememberWallpaperVersion
     *  同语义）：OnColorsChangedListener（AOSP 对同色系壁纸去重不回调）+
     *  ACTION_WALLPAPER_CHANGED 广播（每次设壁纸必发）。 */
    private val wallpaperRevision = MutableStateFlow(0)
    private val wallpaperColorsListener = WallpaperManager.OnColorsChangedListener { _, _ ->
        wallpaperRevision.value = wallpaperRevision.value + 1
    }
    private val wallpaperReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_WALLPAPER_CHANGED) {
                wallpaperRevision.value = wallpaperRevision.value + 1
            }
        }
    }

    /** 上游同步补漏：item_icons 快照（iconsJob 主线程写，refreshApps/装饰读）。 */
    private var iconsSnapshot: Map<String, ItemIcon> = emptyMap()
    /** 内存列表（apps + folderApps）当前已按哪个 icons 快照装饰完成的标记；
     *  null = 未装饰/上次装饰失败（下次快照到达强制重试）。 */
    private var itemsDecoratedWith: Map<String, ItemIcon>? = null
    /** 上次重装饰时的主题戳（壁纸 revision + 主题设置指纹）；戳变化 = 强制重装饰。 */
    private var decorationThemeStamp: String? = null
    /** 「读内存列表 → 装饰 → 原子换入」串行化：iconsJob 重装饰与 refreshApps 收尾互斥。 */
    private val decorateMutex = Mutex()
    /** 应用装载代际（每次 refreshApps 自增）；语义见文件头第 1 条。 */
    @Volatile
    private var appLoadGeneration = 0

    /**
     * 图标渲染管线（Grace 按钮 artwork 面专用；settings 传入逐次构造）。
     * context 必须是【模块资源 Context】：ItemIconStore/GraceButtonIcon 解析模块
     * drawable/string —— 宿主 MiuiResources 无模块 R id（NotFound）或同 ID 错位
     * （DualResources 只兜 string 族，drawable 错位静默换图）。
     */
    private val moduleResourceContext: Context = runCatching {
        application.createPackageContext("com.homemode4miui", Context.CONTEXT_IGNORE_SECURITY)
    }.getOrElse { application }
    private val iconStore = ItemIconStore(moduleResourceContext, IconPackRepository(moduleResourceContext))

    /** 与原版 packageReceiver 同构：包变化（安装/卸载/更新/变更）触发应用列表刷新。 */
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_PACKAGE_REMOVED &&
                intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            ) return
            refreshApps()
        }
    }

    /** VM init 调用：注册包变化/壁纸广播 + 启动 item_icons 跟随。 */
    fun start() {
        ContextCompat.registerReceiver(application, packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // 壁纸跟随失效源注册（见 wallpaperRevision 注释）；dispose 侧解绑
        runCatching {
            WallpaperManager.getInstance(application)
                .addOnColorsChangedListener(wallpaperColorsListener,
                    android.os.Handler(android.os.Looper.getMainLooper()))
        }
        ContextCompat.registerReceiver(application, wallpaperReceiver,
            IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        // 上游同步 v1.1.8 S8b：图标设计器产出跟随（GraceButton design + artwork 面）
        // 模块侧根因修复：combine 壁纸 revision + 设置流 —— 光栅化配色（WallpaperColors
        // 种子）或主题设置变化时，Grace 按钮 artwork 重渲染 + themed 图标强制重装饰。
        iconsJob = scope.launch {
            try {
                val database = LauncherDatabase.getInstance(application)
                kotlinx.coroutines.flow.combine(
                    database.itemsDao().icons(),
                    settingsRepository.observeSettings(),
                    wallpaperRevision,
                ) { rows, _, _ -> rows }.collect { rows ->
                    val icons = rows.mapNotNull { row ->
                        ItemIcon.decode(row.iconJson)?.let { row.itemKey to it }
                    }.toMap()
                    // 上游 itemsSnapshot 同构：局部 icons 必须同步到 iconsSnapshot ——
                    // decorateApps/decorateFolders 的两条消费链（冷启动 + 本 collect 即时
                    // 重装饰）都从该字段读 per-item 选择。漏赋值 = 单个图标/文件夹图标
                    // 设计永不生效（装机实锤）。
                    iconsSnapshot = icons
                    // Grace 按钮位图随 icons 流产出（上游 LauncherViewModel.kt:450-451 同构）
                    val buttonApp = try {
                        val iconSettings = currentIconSettings()
                        // moduleResourceContext：GraceButtonIcon.item 内部 getString
                        // (settings_grace_button) 必须解析模块资源。
                        iconStore.apply(
                            com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.item(moduleResourceContext),
                            com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.choice(
                                icons[com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.key]),
                            iconSettings,
                        )
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        ModLog.e("AppsLoader renderGraceButtonIcon failed", error)
                        null
                    }
                    // 上游 reload 语义：快照变化 → 内存列表重装饰（设计器保存后桌面立即生效）。
                    // 去重 + 装载中让路（详见 class 头第 1 条）：
                    // ① 快照与 itemsDecoratedWith 相同 → 跳过；
                    // ② isLoadingApps = refreshApps 在途，其 decorateApps 会读到最新快照，
                    //    此处并发重装饰会用旧 apps 覆盖装载结果（空列表闪断竞态）；
                    // ③ 主题戳（壁纸 revision + 主题设置）变化也强制重装饰。
                    val iconSettingsForStamp = currentIconSettingsOrNull()
                    if (iconSettingsForStamp != null) {
                        val stamp = decorationThemeStampOf(iconSettingsForStamp)
                        if (icons != itemsDecoratedWith || stamp != decorationThemeStamp) {
                            decorationThemeStamp = stamp
                            applyItemIconSnapshot()
                        }
                    }
                    uiState.update { it.copy(itemIcons = icons, graceButtonApp = buttonApp) }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("AppsLoader observeItemIcons failed", error)
            }
        }
    }

    fun refreshApps() {
        // force：包变化/挂载/图标包切换后必须按系统 pinned 重新对账；
        // 回桌面（GraceHomeView）走默认节流，这里 force 正好覆盖首次装载那一次。
        onRefreshPinned()
        appLoadJob?.cancel()
        // 代际自增必须在启动新 job 之前（旧 job 可能正在锁内换入，读到新代际即让位）
        val generation = ++appLoadGeneration
        appLoadJob = scope.launch {
            uiState.update { it.copy(isLoadingApps = true, appLoadFailed = false) }
            try {
                // B6.4：图标包跟随设置（装饰在 loadApps 内完成；包加载失败自动回退系统图标）
                val packs = uiState.value.settings.enabledIconPackPackages
                // 快照在装饰前取定：装载期间 icons 流前进的场景由尾部补偿轮处理。
                val snapshotAtDecorate = iconsSnapshot
                // 单段装载（2026-11-14 User 实锤：重启手机后收藏图标正常、抽屉整屏字母占位）：
                // 两段式（phase1 收藏子集 + phase2 全量）在 MIUI IconCustomizer 主题资源锁上
                // 互相踩踏，全量那批撞解码超时被整批判死 → 抽屉永久骨架态。回归单段后
                // 首帧晚 1-2 秒，但抽屉图标不再整批丢失。
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
                        ModLog.i("refreshApps: icon missing=" + missing.size + "/" + full.size)
                    }
                    if (missing.isNotEmpty() && missing.size * 20 >= full.size) {
                        ModLog.i("refreshApps: retry icon decode missing=" + missing.size + "/" + full.size)
                        full = inventory.withIcons(missing.toSet(), parallelism = 2)
                        ModLog.i("refreshApps: after retry missing=" + full.count { it.icon == null } +
                            "/" + full.size)
                    }
                }
                // 开机后首次装载的兜底（2026-11-02 User 实测："再重启一次桌面图标就回来"）：
                // 按 6s/15s 退避自动整表重装，最多两轮。
                val missingAfterRetry = full.count { it.icon == null }
                if (missingAfterRetry * 5 >= full.size && appLoadRetryCount < 2) {
                    appLoadRetryCount += 1
                    val delayMs = if (appLoadRetryCount == 1) 6_000L else 15_000L
                    ModLog.i("refreshApps: schedule auto-retry #" + appLoadRetryCount
                            + " in " + delayMs + "ms (missing=" + missingAfterRetry + "/" + full.size + ")")
                    scope.launch {
                        kotlinx.coroutines.delay(delayMs)
                        if (generation == appLoadGeneration && !uiState.value.isLoadingApps) refreshApps()
                    }
                } else if (missingAfterRetry == 0) {
                    appLoadRetryCount = 0
                }
                val apps = decorate(apps = full)
                ModLog.i("refreshApps: decorated at=" + android.os.SystemClock.uptimeMillis()
                        + " iconMissing=" + apps.count { it.icon == null } + "/" + apps.size)
                val folderApps = decorateFolders(uiState.value.folders, snapshotAtDecorate)
                // 原版语义：favoritesFor 负责首装默认收藏 / 旧格式迁移 / 去重；
                // 之后包变化刷新只补 key，不打乱用户已保存的顺序。
                val order = favoritesStore.favoritesFor(apps)
                // 上游同步 v1.1.8 S8a：工作资料枚举（无 managed profile 时零开销静默跳过）。
                val workProfiles = appRepository.workProfiles()
                val workProfileApps = if (workProfiles.isNotEmpty()) {
                    appRepository.loadWorkApps(workProfiles, packs)
                } else emptyList()
                // 锁内换入（与 applyItemIconSnapshot 串行，见文件头第 1 条）。
                // favoriteOrder 与本类之外的唯一跨域写：必须与 apps 同一次换入，
                // 否则收藏区会闪一帧空（见文件头第 2 条）。
                decorateMutex.withLock {
                    if (generation != appLoadGeneration) return@launch
                    uiState.update {
                        it.copy(apps = apps, folderApps = folderApps, favoriteOrder = order,
                            isLoadingApps = false, homeReady = true,
                            workProfiles = workProfiles, workProfileApps = workProfileApps)
                    }
                }
                // appLoadRetryCount 的归零放在 missing 判定分支：只有"零缺失"的成功装载
                // 才清零，否则退避重试计数会被每次装载重置成 0。
                itemsDecoratedWith = snapshotAtDecorate
                // 装载期间 icons 流前进过（iconsJob 因 isLoadingApps 让路）：
                // 以最新快照补一轮内存重装饰，否则新快照要等下次 emission 才生效。
                if (iconsSnapshot != snapshotAtDecorate) applyItemIconSnapshot()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("AppsLoader loadApps failed", error)
                // 已有列表（上一轮装载结果）时不得置 appLoadFailed —— 那会让
                // favorites/appListApps 双双空化，比"图标未解出"严重得多。
                val hasList = uiState.value.apps.isNotEmpty()
                uiState.update { it.copy(isLoadingApps = false, appLoadFailed = !hasList, homeReady = true) }
                // 半装载自愈：枚举/解码中途抛错会留下旧列表，且没有兜底能唤醒 ——
                // 延迟重试一次（仅一次，避免失败-重试循环）。
                if (hasList && appLoadRetryCount < 1 && generation == appLoadGeneration) {
                    appLoadRetryCount += 1
                    scope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (generation == appLoadGeneration && !uiState.value.isLoadingApps) refreshApps()
                    }
                }
            }
        }
    }

    /**
     * 用当前 iconsSnapshot 重装饰内存中的应用/文件夹图标列表（不重查系统），原子换入。
     * icons 快照到达（iconsJob）与 refreshApps 装载尾部补偿共用；装载在途时直接让路。
     * 成功后落 itemsDecoratedWith 标记；失败不落（下次快照到达强制重试）。
     */
    private suspend fun applyItemIconSnapshot(): Boolean {
        if (uiState.value.isLoadingApps) return false
        return decorateMutex.withLock {
            try {
                val generation = appLoadGeneration
                val state = uiState.value
                val apps = decorate(apps = state.apps)
                val folderApps = decorateFolders(state.folders, iconsSnapshot)
                // v2.9.154：快捷方式同样重装饰（图标设计器改快捷方式图标后即时生效）
                val (shortcutApps, shownKeys) = runCatching {
                    val rows = LauncherDatabase.getInstance(application).itemsDao().readShortcuts()
                    buildShortcuts(rows)
                }.getOrElse { state.savedShortcutApps to state.shownShortcutKeys }
                // 装饰期间新一次装载接管（代际前进 / isLoadingApps 已置位）：本轮基于旧
                // apps 的结果直接丢弃 —— 写回去会把在途装载的换入结果盖成旧快照的骨架。
                if (generation != appLoadGeneration || uiState.value.isLoadingApps) return@withLock false
                uiState.update {
                    it.copy(apps = apps, folderApps = folderApps,
                        savedShortcutApps = shortcutApps, shownShortcutKeys = shownKeys)
                }
                itemsDecoratedWith = iconsSnapshot
                true
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("AppsLoader applyItemIconSnapshot failed", error)
                false
            }
        }
    }

    /** 主题戳：光栅化配色的全部来源指纹（壁纸取色 revision + 主题设置 + 夜间模式）。 */
    private fun decorationThemeStampOf(settings: com.galaxyrio.gracelauncher.data.LauncherSettings): String {
        val nightMode = application.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return wallpaperRevision.value.toString() + "|" + settings.useDynamicColors + "|" + settings.themeColor +
            "|" + settings.darkMode.name + "|" + settings.paletteStyle.name + "|" +
            settings.enabledIconPackPackages + "|" + nightMode
    }

    /** currentIconSettings 的容错版（主题戳计算用；失败返回 null 跳过本次重装饰判定）。 */
    private suspend fun currentIconSettingsOrNull(): com.galaxyrio.gracelauncher.data.LauncherSettings? =
        runCatching { currentIconSettings() }.getOrNull()

    /**
     * 图标渲染设置（上游 iconSettings 的宿主同构）：主题三参从 Room 行直取（与设计器
     * 预览同源），iconDesign = 全局面「全部」页设计，iconPackPackages = theme kind 跟随的主题包。
     */
    private suspend fun currentIconSettings(): com.galaxyrio.gracelauncher.data.LauncherSettings {
        val row = LauncherDatabase.getInstance(application).settingsDao().readSettings()
        return com.galaxyrio.gracelauncher.data.LauncherSettings(
            useDynamicColors = row?.useDynamicColors ?: true,
            themeColor = row?.themeColor ?: com.galaxyrio.gracelauncher.data.LauncherSettings.DEFAULT_THEME_COLOR,
            darkMode = row?.darkMode
                ?.let { runCatching { com.galaxyrio.gracelauncher.data.ThemeMode.valueOf(it) }.getOrNull() }
                ?: com.galaxyrio.gracelauncher.data.ThemeMode.System,
            iconDesign = row?.iconDesignJson?.let(ItemIcon::decode),
            iconPackPackages = com.galaxyrio.gracelauncher.data.GraceHomeSettings.decodeStringList(row?.iconPackPackagesJson),
        )
    }

    /**
     * 应用图标装饰（上游 LauncherViewModel.decorate 逐字语义）。快速路径：无自定义
     * 设计 + 无全局面 + 未开主题化时零渲染（宿主进程省 200+ 次 144px 位图渲染 ——
     * 上游独立进程扛得住，com.miui.home 与 MIUI 自身 UI 抢 CPU 扛不住，见下方注释）。
     *
     * internal 可见：ShortcutSync 通过 VM 注入的 decorate lambda 复用它（快捷方式与
     * 应用共用同一条装饰链路）。
     */
    suspend fun decorate(apps: List<LauncherApp>): List<LauncherApp> {
        val icons = iconsSnapshot
        val iconSettings = currentIconSettings()
        val themed = LauncherPreferences(application).themedIcons
        if (!themed && icons.isEmpty() && iconSettings.iconDesign == null) return apps
        return withContext(Dispatchers.IO) {
            apps.map { app ->
                runCatching {
                    val special = icons[app.key]
                    // 骨架应用（系统图标解码失败/未解出）：无 per-item 选择 + 无全局面设计
                    // 时直接跳过 —— 否则 systemDrawable 兜底会对 200+ 骨架触发全量重渲染。
                    // 宿主性能适配（装机实锤 v2.9.51 后每次重启桌面字母条滑动卡顿）：
                    // 快速路径与重渲染视觉等价：保留枚举原图标，仅按设计分支语义清空
                    // monochromeIcon（桌面 themed 圆显隐一致）。
                    when {
                        // v2.9.158：快捷方式图标还没解码出来（saved_shortcuts 行先到、
                        // LauncherApps 图标后到）→ 跳过装饰。等 shortcutIcons 到位后
                        // buildForRows 会重建并正常装饰。
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
     * key "folder:<id>" 的自定义选择经 ItemIconStore.apply 渲染成位图。
     * 无自定义选择的文件夹不进 map（folderItem 回落 asApp() 默认圆标）。
     */
    private suspend fun decorateFolders(
        folders: List<LauncherFolder>,
        icons: Map<String, ItemIcon>,
    ): Map<String, LauncherApp> {
        if (icons.isEmpty() || folders.isEmpty()) return emptyMap()
        val iconSettings = currentIconSettings()
        return withContext(Dispatchers.IO) {
            folders.mapNotNull { folder ->
                val choice = icons[folder.key] ?: return@mapNotNull null
                runCatching { iconStore.apply(folder.asApp(), choice, iconSettings) }
                    .getOrNull()?.takeIf { it.icon != null }?.let { folder.id to it }
            }.toMap()
        }
    }

    /** VM shutdown 调用：取消装载/图标流 + 解绑三处广播监听（VM 每次 attach 新建）。 */
    fun dispose() {
        appLoadJob?.cancel(); appLoadJob = null
        iconsJob?.cancel(); iconsJob = null
        runCatching { application.unregisterReceiver(packageReceiver) }
            .onFailure { ModLog.e("AppsLoader unregister package receiver failed", it) }
        runCatching { application.unregisterReceiver(wallpaperReceiver) }
            .onFailure { ModLog.e("AppsLoader unregister wallpaper receiver failed", it) }
        runCatching {
            WallpaperManager.getInstance(application).removeOnColorsChangedListener(wallpaperColorsListener)
        }
    }
}
