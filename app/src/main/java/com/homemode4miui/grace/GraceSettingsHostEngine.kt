/*
 * C 组（C3）宿主侧设置引擎 —— 新建文件（非搬运）。运行在【宿主进程 com.miui.home】。
 *
 * 职责：接收模块设置页的 ACTION_SETTINGS_COMMAND 广播（经 GraceBridgeReceiver 转发），
 * 把命令落到本进程的 grace_launcher.db（LauncherSettingsRepository / LauncherItemsRepository）
 * 与 grace_launcher_preferences SP（FavoritesStore / LauncherPreferences），落地后把
 * 全量快照回推模块设置页（闭环）。桌面 VM（GraceHomeViewModel）通过 onMutated 回调
 * 刷新内存态（收藏序 / 设置流），保持"记账式 GONE"渲染即时生效。
 *
 * 数据面（与原版 LauncherViewModel 写路径同构）：
 *   - 设置行：LauncherSettings 大模型 read-modify-write（34 列全量落表，零 migration）；
 *   - item_icons / app_popups / saved_shortcuts：LauncherItemsRepository；
 *   - favorites / renames / categories / textMode / themedIcons：SP 原版语义；
 *   - private space：PrivateSpaceController（P6 探测升级为完整控制器，本文件 configure 接线）。
 *
 * 宿主消费登记（本阶段落地）：iconPackPackagesJson（AppRepository 装饰链已有）、
 * clockStyleJson（P1 时钟渲染已有）、weatherLocationId（模块桥引擎读）、
 * hideFavoriteNames / searchJson / homeLayoutJson（P1~P4 已有）。
 * 仅持久化、宿主渲染待接：hideStatusBar / hideAlphabet / dimWallpaper / blurWallpaper /
 * themeColor / darkMode / amoledMode / appFontId / allowHapticFeedback / clockAppKey /
 * mediaAppKey（登记于 PORTING_GraceLauncher.md C 组验收节）。
 */
package com.homemode4miui.grace

import android.content.Context
import android.content.Intent
import com.galaxyrio.gracelauncher.data.FavoritesStore
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherItemsRepository
import com.galaxyrio.gracelauncher.data.LauncherPreferences
import com.galaxyrio.gracelauncher.data.LauncherSettingsRepository
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.platform.PrivateSpaceController
import com.homemode4miui.bridge.GraceBridge
import com.homemode4miui.bridge.GraceSettingsBridge
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GraceSettingsHostEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onMutated: () -> Unit,
    /** UI 动作命令回调（hotfix2）：编辑模式等桌面 UI 状态不属于数据面，不经 apply 落库。 */
    private val onMoveWidget: () -> Unit = {},
    /** 上游同步补漏：设置页「添加自定义小组件」→ 桌面开 WidgetPicker（同 MOVE_WIDGET 模式）。 */
    private val onAddWidget: () -> Unit = {},
    /** folders 阶段（hotfix folders2）：弹层 widget 添加（宿主开 WidgetPicker 弹层模式）与快捷方式代查。 */
    private val onAddPopupWidget: (String) -> Unit = {},
    private val onShortcutQuery: (String) -> Unit = {},
    /** B6.2 天气上移宿主后：当前天气 wire（随快照回带设置页；模块进程被 provider 白名单拒绝）。 */
    private val weatherJson: () -> String? = { null },
    /** B6.2：设置页「刷新天气」→ 宿主引擎重读（新数据经 onState → VM 推快照回带）。 */
    private val onWeatherRefresh: () -> Unit = {},
) {
    private val database by lazy { runCatching { LauncherDatabase.getInstance(context) }.getOrNull() }
    private val settingsRepository by lazy { database?.let { LauncherSettingsRepository(it) } }
    private val itemsRepository by lazy { database?.let { LauncherItemsRepository(it) } }
    private val favoritesStore by lazy { FavoritesStore(context) }
    private val preferences by lazy { LauncherPreferences(context) }
    private val privateSpace by lazy { runCatching { PrivateSpaceController.get(context) }.getOrNull() }

    /** B6.2：当前首选天气城市（宿主 Room LauncherSettings.weatherLocationId；null = 列表首位）。 */
    suspend fun currentWeatherLocationId(): String? =
        settingsRepository?.snapshots?.first()?.settings?.weatherLocationId

    /** GraceBridgeReceiver 转发入口（onReceive 主线程；落库走 scope 协程）。 */
    fun handle(cmd: String?, arg1: String?, arg2: String?, json: String?) {
        scope.launch {
            val applied = runCatching { apply(cmd, arg1, arg2, json) }
                .onFailure { ModLog.e("GraceSettingsHostEngine: $cmd failed", it) }
                .getOrDefault(false)
            if (applied) {
                pushSnapshot()
                onMutated()
            }
        }
    }

    private suspend fun apply(cmd: String?, arg1: String?, arg2: String?, json: String?): Boolean {
        val repo = settingsRepository ?: return false
        val items = itemsRepository
        return when (cmd) {
            GraceSettingsBridge.Cmd.REQUEST_SNAPSHOT -> true // 快照在 handle 尾部统一回推
            GraceSettingsBridge.Cmd.MUTATE_SETTINGS -> {
                val delta = JSONObject(json ?: "{}")
                // S8b 诊断：宿主收到的设置增量（graceButton 链验证）
                com.homemode4miui.util.ModLog.i(
                    "GraceSettingsHostEngine MUTATE keys=" + delta.keys().asSequence().toList().toString())
                repo.mutateSettings { current ->
                    val next = GraceSettingsBridge.applyDelta(current, delta)
                    if (next.graceButton != current.graceButton) {
                        com.homemode4miui.util.ModLog.i(
                            "GraceSettingsHostEngine graceButton delta applied enabled=" +
                                next.graceButton.enabled)
                    }
                    if (next.privateSpace != current.privateSpace) privateSpace?.configure(next.privateSpace)
                    next
                }
                true
            }
            GraceSettingsBridge.Cmd.SET_HIDDEN_APPS -> {
                val keys = decodeKeys(json).filter(String::isNotBlank).toSet()
                repo.setHiddenApps(keys); true
            }
            GraceSettingsBridge.Cmd.SAVE_FOLDER -> {
                val f = decodeFolder(json) ?: return false
                repo.saveFolder(f); true
            }
            GraceSettingsBridge.Cmd.UPDATE_FOLDER -> {
                val v = JSONObject(json ?: "{}")
                repo.updateFolder(v.getString("id"),
                    name = v.opt("name") as? String,
                    placement = (v.opt("placement") as? String)?.let(FolderPlacement::decode),
                    appListAtBottom = v.opt("appListAtBottom") as? Boolean)
                true
            }
            GraceSettingsBridge.Cmd.DELETE_FOLDER -> { repo.deleteFolder(arg1 ?: return false); true }
            GraceSettingsBridge.Cmd.SET_FOLDER_FAVORITES -> {
                val v = JSONObject(json ?: "{}")
                repo.setFolderFavorites(v.keys().asSequence().associateWith { key -> v.optBoolean(key) }); true
            }
            GraceSettingsBridge.Cmd.SET_ITEM_ICON -> {
                val key = arg1 ?: return false
                val icon = (json ?: "").takeIf { it != "null" && it.isNotBlank() }?.let(ItemIcon::decode)
                items?.saveIcon(key, icon) ?: return false; true
            }
            GraceSettingsBridge.Cmd.DELETE_ICON_DESIGNS -> {
                val keys = json?.let { j -> (0 until JSONArray(j).length()).map { k -> JSONArray(j).optString(k) } }?.filter(String::isNotBlank)?.toSet()
                items?.resetIcons(keys ?: emptySet()) ?: return false; true
            }
            GraceSettingsBridge.Cmd.REMEMBER_SHORTCUT -> {
                // 快捷方式记忆 = saved_shortcuts 落表（原版 LauncherItemsRepository.rememberShortcut 同构）
                val dao = database?.itemsDao() ?: return false
                val v = JSONObject(json ?: "{}")
                val itemKey = v.optString("itemKey").ifBlank { arg1 ?: return false }
                dao.saveShortcut(com.galaxyrio.gracelauncher.data.SavedShortcutEntity(
                    itemKey = itemKey,
                    packageName = v.getString("packageName"),
                    shortcutId = v.getString("shortcutId"),
                    activity = v.getString("activity"),
                    label = v.optString("label"),
                    showInAppList = v.optBoolean("showInAppList", false),
                ))
                true
            }
            // 审计修缮（2026-10-16）：删除 REORDER_FAVORITES/CATEGORIZE/THEMED_ICONS
            // 接收分支 —— 发送端（设置页同名 action 字段）零消费已删，协议随葬。
            // 收藏重排在宿主 shim 面直走 GraceHomeViewModel.reorderFavorites。
            GraceSettingsBridge.Cmd.SAVE_POPUP -> {
                val ownerKey = arg1 ?: return false
                val keys = decodeKeys(json)
                items?.updatePopup(ownerKey, emptyList()) { previous ->
                    val byKey = previous.associateBy { it.key }
                    // 模块编辑只管 app 成员顺序；widget 项保持原相对顺序追加在尾部。
                    // hotfix folders2：新勾选的成员不在 previous 里（defaults 不可用），
                    // 必须按 key 新建 PopupItem —— 否则新增成员静默丢失（装机实锤）。
                    keys.mapNotNull { key -> byKey[key]?.takeIf { it.widget == null } ?: PopupItem(key) } +
                        previous.filter { it.widget != null }
                } ?: return false; true
            }
            GraceSettingsBridge.Cmd.TEXT_MODE -> {
                (arg1 ?: return false).let { mode ->
                    WallpaperTextMode.entries.firstOrNull { it.name == mode }?.let { preferences.textMode(it) }
                }; true
            }
            GraceSettingsBridge.Cmd.THEMED_ICONS -> { preferences.themedIcons(arg1 == "1"); true } // 保留：桌面装饰链读 SP（协议仍在）
            GraceSettingsBridge.Cmd.RENAME -> { preferences.rename(arg1 ?: return false, arg2 ?: ""); true }
            // 审计修缮（2026-10-16）：CATEGORIZE 分支已删（发送端零消费）
            // 审计修缮（2026-10-16）：删除 PS_REQUEST/PS_SETTINGS/PS_REORDER/PS_RESET_APPEARANCE
            // 接收分支 —— 协议常量已随发送端缺位一并移除（见 GraceSettingsBridge.Cmd）。
            // 私密空间「锁」侧（configure/close）与外观重置走既有 MUTATE_SETTINGS/PS_CLOSE。
            GraceSettingsBridge.Cmd.PS_CLOSE -> { privateSpace?.close(); true }
            // hotfix2：设置页"移动小部件" → 桌面 editingHome（原版同进程直改 UI 状态，
            // 本仓跨进程只能发命令；返回 false = 纯 UI 动作，不触发快照回推/onMutated）
            GraceSettingsBridge.Cmd.MOVE_WIDGET -> { onMoveWidget(); false }
            // 上游同步补漏：设置页「添加自定义小组件」（原版同进程直开 WidgetSetupActivity，
            // 本仓跨进程发命令；返回 false = 纯 UI 动作，不触发快照回推/onMutated）
            GraceSettingsBridge.Cmd.ADD_WIDGET -> { onAddWidget(); false }
            // folders 阶段（hotfix folders2）：均为 UI 动作/代查，不触发快照回推
            GraceSettingsBridge.Cmd.ADD_POPUP_WIDGET -> { onAddPopupWidget(arg1 ?: return false); false }
            GraceSettingsBridge.Cmd.SHORTCUTS_QUERY -> { onShortcutQuery(json ?: return false); false }
            // B6.2：设置页「刷新天气」→ 宿主天气引擎重读；返回 false = 不立即回推，
            // 新天气在装载完成后由 VM 侧主动推快照回带
            GraceSettingsBridge.Cmd.WEATHER_REFRESH -> { onWeatherRefresh(); false }
            // wipe-v4：清除第四桌面数据 —— 主路走桥。宿主进程内自清数据层：
            // Room clearAllTables（活连接，无删文件竞态）+ 三个 grace SP clear
            // （SharedPreferencesImpl 内存缓存与磁盘同进程一致清，v3 root 删文件的
            // "SP 缓存复活"根因在本路径不存在）+ files/item_icons 位图目录。
            // 清完回执模块 → 延迟自杀重启（HOME 被系统拉起，ModePrefs 保留）。
            // 返回 false：本命令自建回执链，不走 pushSnapshot/onMutated。
            GraceSettingsBridge.Cmd.WIPE_DATA -> { wipeHostData(); false }
            // appfont-bridge（v2.9.67）：字体删除同步（拉取方向走 FontBridgeProvider，
            // 无需推送协议）。返回 true 触发快照刷新。
            GraceSettingsBridge.Cmd.FONT_DELETE -> { fontDelete(arg1); true }
            else -> { ModLog.i("GraceSettingsHostEngine: unknown cmd $cmd"); false }
        }
    }

    /** 快照回推（模块设置页打开期间动态 receiver 接收；桌面未挂载时本引擎不存活）。 */
    fun pushSnapshot() {
        scope.launch {
            val repo = settingsRepository ?: run {
                sendSnapshot(GraceSettingsBridge.Snapshot(ok = false)); return@launch
            }
            runCatching {
                val snapshot = repo.snapshots.first()
                val dao = database?.itemsDao()
                val icons = dao?.readIcons()?.mapNotNull { row ->
                    ItemIcon.decode(row.iconJson)?.let { row.itemKey to it }
                }?.toMap() ?: emptyMap()
                val popupsJson = dao?.popups()?.first()
                    ?.associate { it.ownerKey to it.itemsJson } ?: emptyMap()
                val favoriteOrder = favoritesStore.currentOrder().orEmpty()
                GraceSettingsBridge.Snapshot(
                    ok = true,
                    settings = snapshot.settings,
                    weatherJson = weatherJson(),
                    hiddenAppKeys = snapshot.hiddenAppKeys,
                    folders = snapshot.folders,
                    popupsJson = popupsJson,
                    itemIcons = icons,
                    favoriteOrder = favoriteOrder,
                    favoriteKeys = favoriteOrder.toSet(),
                    textMode = preferences.textMode,
                    themedIcons = preferences.themedIcons,
                    renames = preferences.renames(),
                    categories = preferences.categories(),
                    privateSpace = privateSpace?.state?.value?.let {
                        GraceSettingsBridge.PrivateSpaceWire(it.supported, it.serial, it.locked, it.accessible, it.revision, it.lockVersion)
                    },
                )
            }.onSuccess { sendSnapshot(it) }
                .onFailure { ModLog.e("GraceSettingsHostEngine: snapshot build failed", it); sendSnapshot(GraceSettingsBridge.Snapshot(ok = false)) }
        }
    }

    /**
     * wipe-v4：数据层自清（IO 协程）→ 回执模块 → 800ms 后自杀重启。
     * 刻意保留：files/clock_fonts（导入字体文件本体）、launcher_sharedpreference.xml
     * （ModePrefs 模式开关/熔断状态，模式不关）。
     */
    private fun wipeHostData() {
        scope.launch {
            val detail = runCatching {
                withContext(Dispatchers.IO) {
                    // Room 全表清（库实例单例连接；null = DB 初始化失败降级 SP 场景，跳过）
                    database?.clearAllTables()
                    // 三个 grace SP 进程内一致清（内存缓存 + 磁盘同步清，commit 确认落盘）
                    listOf(
                        "grace_launcher_settings",   // GraceHomeSettingsStore
                        "grace_launcher_preferences", // FavoritesStore 收藏/排序
                        "launcher_appearance",        // LauncherPreferences renames/categories/textMode
                    ).forEach { name ->
                        context.getSharedPreferences(name, Context.MODE_PRIVATE)
                            .edit().clear().commit()
                    }
                    // 自定义图标位图（DB/SP 只承载元数据）
                    java.io.File(context.filesDir, "item_icons").deleteRecursively()
                }
                "db=${database != null}"
            }.getOrElse { "failed: ${it::class.java.simpleName}: ${it.message}" }
            val ok = !detail.startsWith("failed")
            ModLog.i("GraceSettingsHostEngine: WIPE_DATA done ok=$ok ($detail)")
            runCatching {
                context.sendBroadcast(
                    Intent(GraceSettingsBridge.ACTION_WIPE_DONE)
                        .setPackage(GraceBridge.PACKAGE_MODULE)
                        .putExtra(GraceSettingsBridge.EXTRA_OK, ok),
                )
            }
            // 自杀重启：HOME 被系统自动拉起，以全新默认数据挂载第四桌面
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }, 800)
        }
    }

    // ==================== appfont-bridge：字体删除同步（v2.9.67） ====================
    // 推送方向已由 FontBridgeProvider 按需拉取替代（v2.9.66 分片广播被 MIUI 吞掉，弃用）。

    private suspend fun fontDelete(id: String?) {
        if (id.isNullOrBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                listOf("clock_fonts", "clock_fonts_h").forEach { name ->
                    java.io.File(context.filesDir, "$name/$id").delete()
                }
            }
        }
    }

    private fun sendSnapshot(snapshot: GraceSettingsBridge.Snapshot) {
        runCatching {
            context.sendBroadcast(
                Intent(GraceSettingsBridge.ACTION_SETTINGS_SNAPSHOT)
                    .setPackage(GraceBridge.PACKAGE_MODULE)
                    .putExtra(GraceBridge.EXTRA_JSON, GraceSettingsBridge.encodeSnapshot(snapshot)),
            )
            ModLog.i("GraceSettingsHostEngine: snapshot pushed (ok=${snapshot.ok}, ${snapshot.settings.clockStyle.layout})")
        }.onFailure { ModLog.e("GraceSettingsHostEngine: snapshot send failed", it) }
    }

    private fun decodeFolder(json: String?): LauncherFolder? = runCatching {
        val v = JSONObject(json ?: return null)
        LauncherFolder(
            id = v.getString("id"), name = v.getString("name"),
            appKeys = v.optJSONArray("keys")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) } } ?: emptyList(),
            placement = FolderPlacement.decode(v.optString("placement")),
            appListAtBottom = v.optBoolean("appListAtBottom", true),
        )
    }.getOrNull()

    private fun decodeKeys(json: String?): List<String> = runCatching {
        val array = JSONArray(json ?: "[]")
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
}
