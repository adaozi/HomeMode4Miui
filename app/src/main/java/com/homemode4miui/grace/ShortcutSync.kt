/*
 * 快捷方式域（GraceHomeViewModel 拆分第 6 步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：shortcutRepository / savedShortcutsJob / launcherAppsRef / launcherAppsCallback /
 *        lastShortcutSyncAt / lastPinnedSyncAt
 *   状态：savedShortcutApps / shownShortcutKeys / shortcutIcons / hasShortcutAccess
 *   方法：syncPinnedShortcuts / shortcutItemKey / buildShortcutApps / removeShortcut /
 *        loadShortcuts / cachedShortcuts / prepareShortcuts / launchShortcut /
 *        refreshShortcutAccess / onBridgeShortcutQuery
 *
 * ⚠️ 依赖方向（踩过一次的坑，见 docs/VIEWMODEL_SPLIT_PLAN.md §4-8）：
 *   `buildShortcutApps` 要把快捷方式送进和应用同一条图标装饰链路（decorateApps），
 *   而 AppsLoader 的装饰路径又会回头调 buildShortcutApps 重建列表 —— 两者**双向**。
 *   所以装饰能力以 lambda 注入（decorate），而不是持有 AppsLoader：
 *   本类只声明"我需要一个能装饰列表的函数"，由 VM（将来是 AppsLoader）提供实现。
 *
 * 跨域：removeShortcut 要摘掉收藏顺序里的脏 key，走注入的 onFavoriteKeyOrphaned
 * （收藏属于 Settings 域）。
 */
package com.homemode4miui.grace

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import androidx.compose.ui.graphics.asImageBitmap
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.SavedShortcutEntity
import com.galaxyrio.gracelauncher.data.ShortcutRepository
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import com.homemode4miui.bridge.GraceBridge
import com.homemode4miui.bridge.GraceSettingsBridge
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ShortcutSync(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
    private val shortcutRepository: ShortcutRepository,
    /** 图标装饰（与应用同一条链路）。由 VM 注入，第 8 步后指向 AppsLoader。 */
    private val decorate: suspend (List<LauncherApp>) -> List<LauncherApp>,
    /** 快捷方式被移除后清理收藏顺序里的脏 key（收藏属 Settings 域）。 */
    private val onFavoriteKeyOrphaned: (String) -> Unit,
) {
    /** hotfix folders2：已保存快捷方式流（saved_shortcuts 表）。 */
    private var savedShortcutsJob: Job? = null
    private var launcherAppsRef: LauncherApps? = null
    /** onShortcutsChanged 常成批到达（一次 pin 多条回调）→ 600ms 合并。 */
    private var lastShortcutSyncAt = 0L
    /** syncPinned 的「回桌面路径」节流时刻（uptimeMillis，见 syncPinned 注释）。 */
    private var lastPinnedSyncAt = 0L

    private companion object {
        /** 回桌面触发的 pinned 对账最小间隔（系统推送走 force=true，不受限）。 */
        const val PINNED_SYNC_MIN_INTERVAL_MS = 5_000L
        /** onShortcutsChanged 成批到达的合并窗口。 */
        const val SHORTCUT_CALLBACK_THROTTLE_MS = 600L
    }

    /**
     * 系统侧 shortcut 变化回调（require launcher/HOME 角色，宿主满足）。
     * 用户明确要求"其他地方删了快捷方式，模块桌面不能还留着" —— 这是主动推送面，
     * resume / 抽屉打开的对账是被动兜底。
     */
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle,
        ) {
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastShortcutSyncAt < SHORTCUT_CALLBACK_THROTTLE_MS) return
                lastShortcutSyncAt = now
                ModLog.i("shortcutsChanged: pkg=$packageName count=${shortcuts.size}")
                syncPinned(force = true)
            } catch (t: Throwable) {
                ModLog.e("ShortcutSync onShortcutsChanged failed", t)
            }
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) = Unit
        override fun onPackageAdded(packageName: String, user: UserHandle) = Unit
        override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
        override fun onPackagesAvailable(
            packageNames: Array<out String>, user: UserHandle, replacing: Boolean,
        ) = Unit

        override fun onPackagesUnavailable(
            packageNames: Array<out String>, user: UserHandle, replacing: Boolean,
        ) = Unit
    }

    /** VM init 调用：启动「saved_shortcuts 表跟随」+ 注册系统 shortcut 回调。 */
    fun start() {
        // hotfix folders2：已保存快捷方式跟随（文件夹成员里的 shortcut key 解析用）
        savedShortcutsJob = scope.launch {
            try {
                LauncherDatabase.getInstance(application).itemsDao().shortcuts().collect { rows ->
                    val (apps, shownKeys) = buildForRows(rows)
                    ModLog.i(
                        "savedShortcuts: rows=" + rows.size + " dismissed=" + rows.count { it.dismissed } +
                        " built=" + apps.size + " shown=" + shownKeys.size + " keys=" + shownKeys)
                    uiState.update {
                        it.copy(savedShortcutApps = apps, shownShortcutKeys = shownKeys)
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                ModLog.e("ShortcutSync observeSavedShortcuts failed", error)
            }
        }
        // v2.9.153：系统 shortcut 变化实时回调（pin/unpin/动态快捷方式更新）。
        // 注册失败（非默认桌面等）静默降级，不影响其余刷新路径。
        runCatching {
            val launcher = application.getSystemService(LauncherApps::class.java)
            launcher?.registerCallback(launcherCallbackForBinding())
            launcherAppsRef = launcher
        }.onFailure { ModLog.e("ShortcutSync register LauncherApps.callback failed", it) }
    }

    /** 供 register/unregister 使用同一个实例（避免匿名对象每次新建导致解绑失败）。 */
    private fun launcherCallbackForBinding(): LauncherApps.Callback = launcherAppsCallback

    /**
     * 补齐"桌面上的快捷方式"：把系统层面已 pin 的快捷方式同步进 saved_shortcuts
     * （showInAppList = true → 抽屉/搜索可见）。
     *
     * 为什么不能只靠 pin hook：MIUI 的 AddItemActivity 在部分版本/场景下不经过
     * PinItemRequestCompat.accept（或只写自己的 workspace），旁听会漏（装机实锤：
     * saved_shortcuts 恒 0 行，抽屉里永远没有 pin 快捷方式）。
     * FLAG_MATCH_PINNED 是 LauncherApps 的公开查询面，宿主持 HOME 角色可直接读，
     * 与 MIUI 内部实现无关；upsert 幂等，重启/装包即自愈。
     */
    fun syncPinned(force: Boolean = false) {
        // 2026-11-15 perf：调用方是 GraceHomeView 的每次回桌面（Launcher.onResume 复用分支）
        // 与 onShortcutsChanged。前者是纯对账，5s 内重复跑没有意义 —— 全量 getShortcuts +
        // 位图解码 + DB upsert 每次都做一遍，是 resume 路径上的固定开销。
        // onShortcutsChanged（系统主动推送）传 force=true 绕过节流，实时性不受影响。
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPinnedSyncAt < PINNED_SYNC_MIN_INTERVAL_MS) return
        lastPinnedSyncAt = now
        scope.launch {
            try {
                val launcher = application.getSystemService(LauncherApps::class.java) ?: return@launch
                val density = application.resources.displayMetrics.densityDpi
                val dao = LauncherDatabase.getInstance(application).itemsDao()
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val raw = launcher.getShortcuts(
                            LauncherApps.ShortcutQuery()
                                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
                            Process.myUserHandle(),
                        )
                        // 查询返回 null（无权限/服务未就绪）≠ 系统里没有 pin：不能拿空集合去对账
                        // 清库，否则一次查询失败会把用户所有快捷方式抹掉。
                        if (raw == null) return@runCatching null
                        val shortcuts = raw
                        val pinnedKeys = shortcuts.map { info ->
                            shortcutItemKey(info.`package`.orEmpty(), info.id)
                        }.toSet()
                        val existing = dao.readShortcuts()
                        // 文件夹/弹层成员引用保护：被引用的行不能删（成员解析会找不到应用）
                        val referenced = runCatching {
                            dao.readPopups().flatMap { PopupItem.decode(it.itemsJson) }
                                .map { it.key }.toSet()
                        }.getOrDefault(emptySet())

                        // ① 对账清理（"第四桌面显示两个"实锤修复）：系统的 pinned 集合是权威 ——
                        //    不在集合里的行说明已在原生桌面被删/被系统回收；用户主动移除
                        //    (dismissed) 的行在系统 unpin 后也在这里彻底忘掉（重新 pin 可再出现）。
                        //    只清理"因 pin 而存在"的行（showInAppList 或 dismissed），
                        //    弹层里手动保存、从未 pin 过的快捷方式（showInAppList=false）不动。
                        var purged = 0
                        var demoted = 0
                        existing.filter { row ->
                            // 只对账当前用户的行：work profile 的 key 带 "profile:<serial>:" 前缀，
                            // 而本查询（Process.myUserHandle）只覆盖当前用户 —— 混进来会被误删。
                            !row.itemKey.startsWith("profile:") &&
                                row.itemKey !in pinnedKeys && (row.showInAppList || row.dismissed)
                        }.forEach { row ->
                            if (row.itemKey in referenced) {
                                if (row.showInAppList) { dao.hideShortcutFromAppList(row.itemKey); demoted++ }
                            } else {
                                // 行彻底删除 → 图标设计器对该快捷方式的产出也一起清掉
                                // （v2.9.156：否则 item_icons 留下孤儿行，设置页残留且
                                //  同一 shortcutId 重新 pin 时会套用旧设计）
                                dao.deleteShortcut(row.itemKey)
                                runCatching { dao.resetIcon(row.itemKey) }
                                purged++
                            }
                        }

                        // ② 图标解码：与原生桌面一致 —— 之前抽屉一律拿"创建快捷方式的应用"
                        //    图标顶替（用户实锤不对）。现在取系统快捷方式图标（含角标），
                        //    再套 MIUI 图标样式遮罩（ShortcutIconCache.miuiStyle，原生桌面
                        //    同一入口）；结果同时落进程内缓存，供设置侧图标设计器缩略图复用。
                        val bitmaps = ShortcutIconCache.decodePinned(launcher, density)
                        ShortcutIconCache.putAll(bitmaps)
                        val icons = bitmaps.mapValues { entry -> entry.value.asImageBitmap() }

                        // ③ upsert（保留 dismissed 记忆：用户移除过的行不再写回显示）
                        val blocked = existing.filter { it.dismissed }.map { it.itemKey }.toSet()
                        shortcuts.forEach { info ->
                            val pkg = info.`package` ?: return@forEach
                            val key = shortcutItemKey(pkg, info.id)
                            if (key in blocked) return@forEach
                            val label = (info.shortLabel ?: info.longLabel)?.toString().orEmpty()
                                .ifBlank { info.id }
                            dao.saveShortcut(SavedShortcutEntity(
                                itemKey = key,
                                packageName = pkg,
                                shortcutId = info.id,
                                activity = info.activity?.flattenToString().orEmpty(),
                                label = label,
                                showInAppList = true,
                            ))
                        }
                        Triple(shortcuts.size, icons, purged to demoted)
                    }.onFailure { ModLog.e("ShortcutSync query failed", it) }.getOrNull()
                } ?: return@launch
                val (pinned, icons, purge) = result
                // 图标更新后必须重建整批展示项（含装饰）：savedShortcutApps 里的
                // LauncherApp 是上一步 decorateApps 的产物，不能只替换 icon 字段
                // 否则图标设计器/主题化产出会被冲掉。
                uiState.update { it.copy(shortcutIcons = icons) }
                val rows = withContext(Dispatchers.IO) {
                    runCatching { dao.readShortcuts() }.getOrDefault(emptyList())
                }
                rebuildFromRows(rows)
                ModLog.i("ShortcutSync: pinned=$pinned icons=${icons.size} purged=${purge.first} demoted=${purge.second}")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ModLog.e("ShortcutSync syncPinned failed", error)
            }
        }
    }

    /**
     * saved_shortcuts 行 → 展示项并写 state。
     * 供本域的流跟随，以及 AppsLoader 装饰完成后重建列表（§4-8 的双向依赖出口）。
     */
    suspend fun rebuildFromRows(rows: List<SavedShortcutEntity>) {
        val (apps, shownKeys) = buildForRows(rows)
        uiState.update { it.copy(savedShortcutApps = apps, shownShortcutKeys = shownKeys) }
    }

    /** 与 GracePinItemHook.persist 的 key 逐字一致：shortcut:<pkg>/<Uri.encode(id)>。 */
    private fun shortcutItemKey(pkg: String, id: String): String =
        "shortcut:$pkg/" + Uri.encode(id)

    /**
     * saved_shortcuts 行 → 展示项（**含图标装饰**）。
     *
     * v2.9.154（User 实锤）：此前快捷方式只拿到"系统原始快捷方式图标"，没走
     * `decorateApps`，与抽屉里普通应用（主题化图标 / 图标包 / 图标设计器产出）
     * 观感不一致 —— 用户描述"相当于应用的图标 vs 桌面的图标的区别"。
     * 现在快捷方式与应用共用同一条装饰链路，item_icons 的 key 就是
     * `shortcut:<pkg>/<id>`，因此图标设计器给快捷方式做的设计也会在这里生效。
     */
    suspend fun buildForRows(
        rows: List<SavedShortcutEntity>,
    ): Pair<List<LauncherApp>, Set<String>> {
        // 落库行可能没有 activity（pin 请求里 ShortcutInfo.getActivity() 常为 null，
        // 例如应用自发布的动态快捷方式）：不能整行丢弃。activity 缺失时用 packageName
        // 占位 —— 启动走 LauncherApps.startShortcut(pkg, id)，本就不需要 activity。
        // dismissed 行（用户主动移除的 pin）不参与任何 UI 面。
        val icons = uiState.value.shortcutIcons
        val built = rows.filterNot { it.dismissed }.mapNotNull { row ->
            val activity = row.activity.takeIf { it.isNotBlank() }
                ?.let(ComponentName::unflattenFromString)
            val component = activity
                ?: row.packageName.takeIf { it.isNotBlank() }
                    ?.let { ComponentName(it, "") }
                ?: return@mapNotNull null
            val icon = icons[row.itemKey]
            val shortcut = LauncherShortcut(
                id = row.shortcutId, packageName = row.packageName, label = row.label,
                icon = icon, activity = activity,
            )
            LauncherApp(componentName = component, label = row.label.ifBlank { row.shortcutId },
                icon = icon, shortcut = shortcut) to row.showInAppList
        }
        val decorated = decorate(built.map { it.first })
        // showInAppList=true（pin 到桌面的快捷方式）→ 并入抽屉/搜索主列表
        val shown = built.filter { it.second }.map { it.first.key }.toSet()
        return decorated to shown
    }

    /**
     * 用户主动移除快捷方式（v2.9.141）：先标 dismissed（UI 立即消失，不依赖系统结果），
     * 再 unpin —— 用户明确要求"模块桌面删除 = 原生桌面也删除"。
     *
     * 为什么不直接删行：syncPinned 每次 refreshApps/resume 都按系统 pinned 集合 upsert，
     * 删行会被自愈写回（"快捷方式删不掉"实锤根因）。dismissed 行不参与 UI；系统 unpin
     * 生效后下一轮对账会把它彻底删除（重新 pin 时以 pin hook 写入的 dismissed=false
     * 新行为准，可正常恢复）。
     */
    fun removeShortcut(app: LauncherApp) {
        val shortcut = app.shortcut ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val dao = LauncherDatabase.getInstance(application).itemsDao()
                        dao.dismissShortcut(app.key)
                        // v2.9.156（User 要求）：图标设计器给这条快捷方式做的设计（item_icons
                        // 以 "shortcut:<pkg>/<id>" 为 key）一并删除 —— 否则是孤儿数据，
                        // 将来同一 shortcutId 重新 pin 时会莫名其妙套用旧设计，设置页也残留。
                        dao.resetIcon(app.key)
                    }.onFailure { ModLog.e("ShortcutSync dismiss failed: ${app.key}", it) }
                    // 系统层 unpin：原生桌面上的那个快捷方式一起消失（用户要求）。
                    // LauncherApps 的公开面只有 pinShortcuts(pkg, 全量 id 列表, user) ——
                    // 传该应用剩余的 pinned id 集合即等于"取消 pin 掉目标这条"（空列表 = 全清）。
                    // 查询失败时绝不下发（否则会把该应用所有 pin 一起清掉）。
                    val launcher = application.getSystemService(LauncherApps::class.java)
                    val user = Process.myUserHandle()
                    val remaining = runCatching {
                        launcher?.getShortcuts(
                            LauncherApps.ShortcutQuery()
                                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                                .setPackage(shortcut.packageName),
                            user,
                        )?.map { it.id }?.filter { it != shortcut.id }
                    }.getOrNull()
                    val unpinOk = if (launcher != null && remaining != null) {
                        runCatching {
                            launcher.pinShortcuts(shortcut.packageName, remaining, user)
                        }.onFailure { ModLog.e("ShortcutSync unpin failed: ${app.key}", it) }.isSuccess
                    } else false
                    ModLog.i("removeShortcut: ${app.key} unpinOk=$unpinOk remaining=${remaining?.size}")
                }
                // v2.9.157：收藏顺序里摘掉该 key —— 否则 favoriteOrder 留下脏 key
                // （虽然 favorites 解析会跳过，但编辑收藏页会拿到不存在的成员槽位）
                runCatching { onFavoriteKeyOrphaned(app.key) }
                    .onFailure { ModLog.e("ShortcutSync favorite cleanup failed", it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ModLog.e("ShortcutSync removeShortcut failed: ${app.key}", error)
            }
        }
    }

    // ---- P4：快捷方式查询/启动（ShortcutRepository 直通，签名对齐原版 LauncherActions）----

    /** suspend：ShortcutPopup 的 produceState 协程内调用（GracePopupActions.shortcuts 契约）。 */
    suspend fun loadShortcuts(app: LauncherApp): ShortcutResult =
        runCatching { shortcutRepository.shortcutsFor(app) }
            .getOrElse { ModLog.e("ShortcutSync loadShortcuts failed: ${app.key}", it); ShortcutResult(ShortcutStatus.Error) }

    fun cachedShortcuts(app: LauncherApp): ShortcutResult? =
        runCatching { shortcutRepository.peek(app) }.getOrNull()

    /** 行触摸准备（AppRowGestures.onPrepare）：协程预取，弹层打开零等待。 */
    fun prepareShortcuts(app: LauncherApp) {
        scope.launch {
            runCatching { shortcutRepository.prefetch(listOf(app)) }
                .onFailure { ModLog.e("ShortcutSync prepareShortcuts failed: ${app.key}", it) }
        }
    }

    /** P5：签名对齐原版 launchShortcut —— sourceBounds/options 供弹层启动动画（B5.1）。 */
    fun launchShortcut(shortcut: LauncherShortcut, sourceBounds: android.graphics.Rect? = null, options: android.os.Bundle? = null): Boolean = runCatching {
        shortcutRepository.launch(shortcut, sourceBounds, options)
    }.getOrElse {
        ModLog.e("ShortcutSync launchShortcut failed: ${shortcut.key}", it); false
    }

    /** 通知监听/快捷方式授权探测（挂载时一次，回桌面时刷新）。 */
    fun refreshAccess() {
        val access = runCatching { shortcutRepository.hasAccess() }
            .onFailure { ModLog.e("ShortcutSync hasShortcutAccess failed", it) }
            .getOrDefault(false)
        uiState.update { it.copy(hasShortcutAccess = access) }
    }

    /**
     * folders 阶段（hotfix folders2）：模块设置页快捷方式代查（SHORTCUTS_QUERY）。
     * HOME 角色在本进程；从已加载应用列表解析 appKey → ShortcutRepository 查询 →
     * ACTION_SHORTCUTS_RESULT 点对点回包模块进程（无图标，跨 JSON 降级）。
     */
    fun onBridgeQuery(json: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val keys = org.json.JSONArray(json).let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) }
                }
                val byKey = uiState.value.apps.associateBy(LauncherApp::key)
                val wires = keys.flatMap { key ->
                    val app = byKey[key] ?: return@flatMap emptyList()
                    val result = runCatching { shortcutRepository.shortcutsFor(app) }
                        .getOrElse { ModLog.e("ShortcutSync shortcut query failed: $key", it); return@flatMap emptyList() }
                    result.shortcuts.map { s ->
                        GraceSettingsBridge.ShortcutWire(
                            appKey = key, key = s.key, packageName = s.packageName, shortcutId = s.id,
                            activity = s.activity?.flattenToString(), label = s.label,
                        )
                    }
                }
                application.sendBroadcast(
                    Intent(GraceSettingsBridge.ACTION_SHORTCUTS_RESULT)
                        .setPackage(GraceBridge.PACKAGE_MODULE)
                        .putExtra(GraceBridge.EXTRA_JSON, GraceSettingsBridge.encodeShortcutsResult(wires))
                )
            }.onFailure { ModLog.e("ShortcutSync shortcut query relay failed", it) }
        }
    }

    /** VM shutdown 调用：取消流跟随 + 解绑系统回调 + 关闭仓储（VM 每次 attach 新建）。 */
    fun dispose() {
        savedShortcutsJob?.cancel()
        savedShortcutsJob = null
        runCatching { launcherAppsRef?.unregisterCallback(launcherAppsCallback) }
            .onFailure { ModLog.e("ShortcutSync unregister LauncherApps.callback failed", it) }
        launcherAppsRef = null
        runCatching { shortcutRepository.close() }
            .onFailure { ModLog.e("ShortcutSync shortcutRepository.close failed", it) }
    }
}
