/*
 * C 组（C3）跨进程设置同步协议 —— 新建文件（非搬运）。
 *
 * 背景：设置页运行在【模块 App 进程】（C1：SettingsActivity 正常 Compose Activity），
 * 而 launcher_settings / hidden_apps / folders / app_popups / item_icons 表与收藏 SP
 * 都在【宿主进程 com.miui.home】的数据目录。两进程数据目录不互通（P6 桥同前提），
 * 复用 P6 广播桥模式：
 *   - 快照下发（宿主 → 模块）：ACTION_SETTINGS_SNAPSHOT。宿主在收到
 *     REQUEST_SNAPSHOT 命令、或任一设置命令落地后推送；设置页打开期间模块侧
 *     动态注册 receiver 接收（导出 receiver，快照方向仅驱动设置 UI 显示）。
 *   - 命令上行（模块 → 宿主）：ACTION_SETTINGS_COMMAND。宿主 GraceBridgeReceiver
 *     （随 GraceHomeView 挂载期动态注册）接收，GraceSettingsHostEngine 落 Room/SP，
 *     落地后回推快照闭环。桌面未挂载（第四模式未启用）时命令不可达 —— 设置页
 *     请求快照超时 → settingsLoadFailed → UI 只读（SettingsStorageState 语义）。
 *
 * 安全面评估（对齐 GraceBridgeReceiver hotfix1 头注）：
 *   - 快照方向：仅驱动设置页显示（宿主→模块），无命令面、无崩溃面（全链 runCatching）；
 *   - 命令方向：宿主 receiver 导出（RECEIVER_EXPORTED 动态注册无法做 uid 校验），
 *     伪造广播最多污染设置数据（等同用户自己在设置页操作），不触碰宿主进程安全边界；
 *     收紧路径 = AIDL 升级（同 P6 登记项）。
 *
 * 设置行编解码：launcher_settings 表 34 列 ↔ JSONObject（列名 = DB 列名）。
 * 增量写：模块发 delta（只含被改列），宿主 read-modify-write 大模型后全量落表 ——
 * 未出现的列保持宿主现值（双端并发写不互相覆盖）。
 */
package com.homemode4miui.bridge

import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.FontLibrary
import com.galaxyrio.gracelauncher.data.GraceButtonSettings
import com.galaxyrio.gracelauncher.data.ListAppearance
import com.galaxyrio.gracelauncher.data.ShortcutsFoldersSettings
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.ProfileSettings
import com.galaxyrio.gracelauncher.data.SearchSettings
import com.galaxyrio.gracelauncher.data.ThemeMode
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.HomeLayout
import org.json.JSONArray
import org.json.JSONObject

object GraceSettingsBridge {

    const val ACTION_SETTINGS_SNAPSHOT = "com.homemode4miui.ACTION_GRACE_SETTINGS_SNAPSHOT"
    const val ACTION_SETTINGS_COMMAND = "com.homemode4miui.ACTION_GRACE_SETTINGS_COMMAND"

    /** 命令字面量（模块 → 宿主；EXTRA_CMD 承载）。 */
    object Cmd {
        const val REQUEST_SNAPSHOT = "settings_request_snapshot"
        const val MUTATE_SETTINGS = "settings_mutate"            // EXTRA_JSON = 列名增量
        const val SET_HIDDEN_APPS = "settings_hidden_apps"       // EXTRA_JSON = [key..]
        const val SAVE_FOLDER = "settings_save_folder"           // EXTRA_JSON = 文件夹全量
        const val UPDATE_FOLDER = "settings_update_folder"       // EXTRA_JSON = {id, name?, placement?, appListAtBottom?}
        const val DELETE_FOLDER = "settings_delete_folder"       // EXTRA_ARG1 = id
        const val SET_FOLDER_FAVORITES = "settings_folder_favs"  // EXTRA_JSON = {id: bool}
        const val SET_ITEM_ICON = "settings_set_icon"            // EXTRA_ARG1 = key, EXTRA_JSON = ItemIcon json / null
        const val DELETE_ICON_DESIGNS = "settings_del_icons"     // EXTRA_JSON = [key..]
        const val REMEMBER_SHORTCUT = "settings_remember_shortcut" // EXTRA_JSON = shortcut 字段
        const val REORDER_FAVORITES = "settings_reorder_favs"    // EXTRA_JSON = [key..]
        const val SAVE_POPUP = "settings_save_popup"             // EXTRA_ARG1 = ownerKey, EXTRA_JSON = [key..]（widget 项宿主保序）
        const val TEXT_MODE = "settings_text_mode"               // EXTRA_ARG1 = mode name
        const val THEMED_ICONS = "settings_themed_icons"         // EXTRA_ARG1 = "1"/"0"
        const val RENAME = "settings_rename"                     // EXTRA_ARG1 = key, EXTRA_ARG2 = label（空串清除）
        const val CATEGORIZE = "settings_categorize"             // EXTRA_ARG1 = key, EXTRA_ARG2 = category
        const val PS_REQUEST = "settings_ps_request"             // EXTRA_ARG1 = "1"/"0"（enable）
        const val PS_CLOSE = "settings_ps_close"
        const val PS_SETTINGS = "settings_ps_settings"
        const val PS_REORDER = "settings_ps_reorder"             // EXTRA_JSON = [key..]
        const val PS_RESET_APPEARANCE = "settings_ps_reset"      // 回传快照 extra 带 ok 标志
        const val MOVE_WIDGET = "settings_move_widget"           // UI 动作命令：桌面进入 widget 编辑模式（无参数，不回推快照）
        // folders 阶段（hotfix folders2）：
        const val ADD_POPUP_WIDGET = "settings_add_popup_widget" // UI 动作命令：EXTRA_ARG1 = ownerKey（桌面开 WidgetPicker 弹层模式）
        const val SHORTCUTS_QUERY = "settings_shortcuts_query"   // EXTRA_JSON = [appKey..]（宿主代查，回 ACTION_SHORTCUTS_RESULT）
    }

    /** 快捷方式代查回包（宿主 → 模块）。activity 为 flattened ComponentName，可为 null。 */
    data class ShortcutWire(
        val appKey: String, val key: String, val packageName: String,
        val shortcutId: String, val activity: String?, val label: String,
    )

    const val ACTION_SHORTCUTS_RESULT = "com.homemode4miui.ACTION_GRACE_SHORTCUTS_RESULT"

    fun encodeShortcutsResult(entries: List<ShortcutWire>): String = JSONObject()
        .put("ok", true)
        .put("apps", JSONObject().apply {
            entries.groupBy(ShortcutWire::appKey).forEach { (appKey, wires) ->
                put(appKey, JSONArray().apply {
                    wires.forEach { w -> put(JSONObject()
                        .put("key", w.key).put("packageName", w.packageName)
                        .put("shortcutId", w.shortcutId)
                        .put("activity", w.activity ?: JSONObject.NULL)
                        .put("label", w.label)) }
                })
            }
        }).toString()

    fun decodeShortcutsResult(json: String?): Map<String, List<ShortcutWire>>? = runCatching {
        val v = JSONObject(json ?: return null)
        if (!v.optBoolean("ok", false)) return emptyMap()
        val apps = v.optJSONObject("apps") ?: return emptyMap()
        apps.keys().asSequence().associateWith { appKey ->
            val arr = apps.optJSONArray(appKey) ?: return@associateWith emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                ShortcutWire(appKey = appKey, key = e.optString("key"),
                    packageName = e.optString("packageName"), shortcutId = e.optString("shortcutId"),
                    activity = e.opt("activity") as? String, label = e.optString("label"))
            }
        }
    }.getOrNull()

    // ================= 设置行（launcher_settings 34 列）=================

    /** 全量行编码（列名 = DB 列名）。 */
    fun encodeRow(s: LauncherSettings): JSONObject = JSONObject()
        .put("clockEnabled", s.clockEnabled)
        .put("calendarAgenda", s.calendarAgenda)
        .put("calendarAboveClock", s.calendarAboveClock)
        .put("showBatteryPercentage", s.showBatteryPercentage)
        .put("allowHapticFeedback", s.allowHapticFeedback)
        .put("useDynamicColors", s.useDynamicColors)
        .put("themeColor", s.themeColor)
        .put("darkMode", s.darkMode.name)
        .put("amoledMode", s.amoledMode)
        .put("iconPackPackage", s.iconPackPackage ?: JSONObject.NULL)
        .put("iconPackPackagesJson", s.iconPackPackages.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() } ?: JSONObject.NULL)
        .put("iconDesignJson", s.iconDesign?.encode() ?: JSONObject.NULL)
        .put("mediaPlayer", s.mediaPlayer)
        .put("mediaAlwaysVisible", s.mediaAlwaysVisible)
        .put("mediaAppKey", s.mediaAppKey ?: JSONObject.NULL)
        .put("graceButtonJson", s.graceButton.encode())
        .put("weatherEnabled", s.weatherEnabled)
        .put("weatherForecastDays", s.weatherForecastDays)
        .put("weatherLocationId", s.weatherLocationId ?: JSONObject.NULL)
        .put("clockAppKey", s.clockAppKey ?: JSONObject.NULL)
        .put("clockStyleJson", s.clockStyle.encode())
        .put("homeLayoutJson", s.homeLayout.encode())
        .put("hideStatusBar", s.hideStatusBar)
        .put("hideAlphabet", s.hideAlphabet)
        .put("hideFavoriteNames", s.hideFavoriteNames)
        .put("dimWallpaper", s.dimWallpaper)
        .put("wallpaperDimAmount", s.wallpaperDimAmount)
        .put("blurWallpaper", s.blurWallpaper)
        .put("wallpaperBlurRadius", s.wallpaperBlurRadius)
        .put("appFontId", s.appFontId ?: JSONObject.NULL)
        .put("applyFontToSettings", s.applyFontToSettings)
        .put("privateSpaceJson", s.privateSpace.encode())
        .put("workProfileJson", s.workProfile.encode())
        .put("homeGesturesJson", s.homeGestures.encode())
        .put("fontLibraryJson", s.fontLibrary.encode())
        .put("listAppearanceJson", s.listAppearance.encode())
        .put("shortcutsFoldersJson", s.shortcutsFolders.encode())
        .put("searchJson", s.search.encode())

    /** 行解码（模块侧快照种子）。 */
    fun decodeRow(v: JSONObject): LauncherSettings = LauncherSettings(
        clockEnabled = v.optBoolean("clockEnabled", true),
        calendarAgenda = v.optBoolean("calendarAgenda", true),
        calendarAboveClock = v.optBoolean("calendarAboveClock", false),
        showBatteryPercentage = v.optBoolean("showBatteryPercentage", true),
        allowHapticFeedback = v.optBoolean("allowHapticFeedback", true),
        useDynamicColors = v.optBoolean("useDynamicColors", true),
        themeColor = v.optInt("themeColor", 0xFF6750A4.toInt()),
        darkMode = ThemeMode.entries.firstOrNull { it.name == v.optString("darkMode") } ?: ThemeMode.System,
        amoledMode = v.optBoolean("amoledMode", false),
        iconPackPackage = v.opt("iconPackPackage") as? String,
        iconPackPackages = decodeStringList(v.opt("iconPackPackagesJson") as? String),
        iconDesign = (v.opt("iconDesignJson") as? String)?.let(ItemIcon::decode),
        mediaPlayer = v.optBoolean("mediaPlayer", true),
        mediaAlwaysVisible = v.optBoolean("mediaAlwaysVisible", false),
        mediaAppKey = v.opt("mediaAppKey") as? String,
        graceButton = GraceButtonSettings.decode(v.opt("graceButtonJson") as? String),
        weatherEnabled = v.optBoolean("weatherEnabled", false),
        weatherForecastDays = v.optInt("weatherForecastDays", 7),
        weatherLocationId = v.opt("weatherLocationId") as? String,
        clockAppKey = v.opt("clockAppKey") as? String,
        clockStyle = ClockStyle.decode(v.opt("clockStyleJson") as? String),
        homeLayout = HomeLayout.decode(v.opt("homeLayoutJson") as? String ?: "{}"),
        hideStatusBar = v.optBoolean("hideStatusBar", true),
        hideAlphabet = v.optBoolean("hideAlphabet", false),
        hideFavoriteNames = v.optBoolean("hideFavoriteNames", false),
        dimWallpaper = v.optBoolean("dimWallpaper", false),
        wallpaperDimAmount = v.optInt("wallpaperDimAmount", 20),
        blurWallpaper = v.optBoolean("blurWallpaper", true),
        wallpaperBlurRadius = v.optInt("wallpaperBlurRadius", 16),
        appFontId = v.opt("appFontId") as? String,
        applyFontToSettings = v.optBoolean("applyFontToSettings", true),
        privateSpace = ProfileSettings.decode(v.opt("privateSpaceJson") as? String),
        workProfile = ProfileSettings.decode(v.opt("workProfileJson") as? String, ProfileSettings.workDefaults()),
        homeGestures = GraceButtonSettings.decode(v.opt("homeGesturesJson") as? String, GraceButtonSettings.homeDefaults()),
        fontLibrary = FontLibrary.decode(v.opt("fontLibraryJson") as? String, v.opt("appFontId") as? String),
        listAppearance = ListAppearance.decode(v.opt("listAppearanceJson") as? String, v.optBoolean("hideFavoriteNames", false)),
        shortcutsFolders = ShortcutsFoldersSettings.decode(v.opt("shortcutsFoldersJson") as? String),
        search = SearchSettings.decode(v.opt("searchJson") as? String),
    )

    /**
     * 增量应用（宿主侧）：delta 只含被改列，未出现列保持 current。
     * 与 encodeRow 同列名；类型按列语义解析。
     */
    fun applyDelta(current: LauncherSettings, delta: JSONObject): LauncherSettings {
        var s = current
        fun set(block: (LauncherSettings) -> LauncherSettings) { s = block(s) }
        if (delta.has("clockEnabled")) set { it.copy(clockEnabled = delta.getBoolean("clockEnabled")) }
        if (delta.has("calendarAgenda")) set { it.copy(calendarAgenda = delta.getBoolean("calendarAgenda")) }
        if (delta.has("calendarAboveClock")) set { it.copy(calendarAboveClock = delta.getBoolean("calendarAboveClock")) }
        if (delta.has("showBatteryPercentage")) set { it.copy(showBatteryPercentage = delta.getBoolean("showBatteryPercentage")) }
        if (delta.has("allowHapticFeedback")) set { it.copy(allowHapticFeedback = delta.getBoolean("allowHapticFeedback")) }
        if (delta.has("useDynamicColors")) set { it.copy(useDynamicColors = delta.getBoolean("useDynamicColors")) }
        if (delta.has("themeColor")) set { it.copy(themeColor = delta.getInt("themeColor")) }
        if (delta.has("darkMode")) set { it.copy(darkMode = ThemeMode.entries.firstOrNull { m -> m.name == delta.getString("darkMode") } ?: ThemeMode.System) }
        if (delta.has("amoledMode")) set { it.copy(amoledMode = delta.getBoolean("amoledMode")) }
        if (delta.has("iconPackPackage")) set { it.copy(iconPackPackage = delta.opt("iconPackPackage") as? String) }
        if (delta.has("iconPackPackagesJson")) set { it.copy(iconPackPackages = decodeStringList(delta.opt("iconPackPackagesJson") as? String)) }
        if (delta.has("iconDesignJson")) set { it.copy(iconDesign = (delta.opt("iconDesignJson") as? String)?.let(ItemIcon::decode)) }
        if (delta.has("mediaPlayer")) set { it.copy(mediaPlayer = delta.getBoolean("mediaPlayer")) }
        if (delta.has("mediaAlwaysVisible")) set { it.copy(mediaAlwaysVisible = delta.getBoolean("mediaAlwaysVisible")) }
        if (delta.has("mediaAppKey")) set { it.copy(mediaAppKey = delta.opt("mediaAppKey") as? String) }
        if (delta.has("graceButtonJson")) set { it.copy(graceButton = GraceButtonSettings.decode(delta.optString("graceButtonJson"))) }
        if (delta.has("weatherEnabled")) set { it.copy(weatherEnabled = delta.getBoolean("weatherEnabled")) }
        if (delta.has("weatherForecastDays")) set { it.copy(weatherForecastDays = delta.getInt("weatherForecastDays")) }
        if (delta.has("weatherLocationId")) set { it.copy(weatherLocationId = delta.opt("weatherLocationId") as? String) }
        if (delta.has("clockAppKey")) set { it.copy(clockAppKey = delta.opt("clockAppKey") as? String) }
        if (delta.has("clockStyleJson")) set { it.copy(clockStyle = ClockStyle.decode(delta.optString("clockStyleJson"))) }
        if (delta.has("homeLayoutJson")) set { it.copy(homeLayout = HomeLayout.decode(delta.optString("homeLayoutJson") ?: "{}")) }
        if (delta.has("hideStatusBar")) set { it.copy(hideStatusBar = delta.getBoolean("hideStatusBar")) }
        if (delta.has("hideAlphabet")) set { it.copy(hideAlphabet = delta.getBoolean("hideAlphabet")) }
        if (delta.has("hideFavoriteNames")) set { it.copy(hideFavoriteNames = delta.getBoolean("hideFavoriteNames")) }
        if (delta.has("dimWallpaper")) set { it.copy(dimWallpaper = delta.getBoolean("dimWallpaper")) }
        if (delta.has("wallpaperDimAmount")) set { it.copy(wallpaperDimAmount = delta.getInt("wallpaperDimAmount")) }
        if (delta.has("blurWallpaper")) set { it.copy(blurWallpaper = delta.getBoolean("blurWallpaper")) }
        if (delta.has("wallpaperBlurRadius")) set { it.copy(wallpaperBlurRadius = delta.getInt("wallpaperBlurRadius")) }
        if (delta.has("appFontId")) set { it.copy(appFontId = delta.opt("appFontId") as? String) }
        if (delta.has("applyFontToSettings")) set { it.copy(applyFontToSettings = delta.getBoolean("applyFontToSettings")) }
        if (delta.has("privateSpaceJson")) set { it.copy(privateSpace = ProfileSettings.decode(delta.optString("privateSpaceJson"))) }
        if (delta.has("workProfileJson")) set { it.copy(workProfile = ProfileSettings.decode(delta.optString("workProfileJson"), ProfileSettings.workDefaults())) }
        if (delta.has("homeGesturesJson")) set { it.copy(homeGestures = GraceButtonSettings.decode(delta.optString("homeGesturesJson"), GraceButtonSettings.homeDefaults())) }
        if (delta.has("fontLibraryJson")) set { it.copy(fontLibrary = FontLibrary.decode(delta.optString("fontLibraryJson"), it.appFontId)) }
        if (delta.has("listAppearanceJson")) set { it.copy(listAppearance = ListAppearance.decode(delta.optString("listAppearanceJson"), it.hideFavoriteNames)) }
        if (delta.has("shortcutsFoldersJson")) set { it.copy(shortcutsFolders = ShortcutsFoldersSettings.decode(delta.optString("shortcutsFoldersJson"))) }
        if (delta.has("searchJson")) set { it.copy(search = SearchSettings.decode(delta.optString("searchJson"))) }
        return s
    }

    private fun decodeStringList(json: String?): List<String> = runCatching {
        if (json == null) emptyList() else JSONArray(json).let { a -> (0 until a.length()).mapNotNull { i -> (a.opt(i) as? String)?.takeIf(String::isNotBlank) } }
    }.getOrDefault(emptyList())

    /** 两份设置行的列级 diff（模块侧 updateSettings 增量上行用）；无变化返回 null。 */
    fun diffRow(old: LauncherSettings, new: LauncherSettings): JSONObject? {
        val a = encodeRow(old)
        val b = encodeRow(new)
        val delta = JSONObject()
        b.keys().forEach { key ->
            val ov = a.opt(key)
            val nv = b.opt(key)
            if (ov != nv) delta.put(key, nv)
        }
        return delta.takeIf { it.length() > 0 }
    }

    // ================= 快照（宿主 → 模块）=================

    data class Snapshot(
        val settings: LauncherSettings = LauncherSettings(),
        val hiddenAppKeys: Set<String> = emptySet(),
        val folders: List<LauncherFolder> = emptyList(),
        /** ownerKey → app_popups.itemsJson 原文（模块侧 PopupItem.decode 解开）。 */
        val popupsJson: Map<String, String> = emptyMap(),
        val itemIcons: Map<String, ItemIcon> = emptyMap(),
        val favoriteOrder: List<String> = emptyList(),
        val favoriteKeys: Set<String> = emptySet(),
        val textMode: WallpaperTextMode = WallpaperTextMode.Auto,
        val themedIcons: Boolean = true,
        val renames: Map<String, String> = emptyMap(),
        val categories: Map<String, String> = emptyMap(),
        val privateSpace: PrivateSpaceWire? = null,
        /** 已落库标志：false = 宿主引擎不可达（第四模式未挂载），设置页只读。 */
        val ok: Boolean = true,
    )

    /** PrivateSpaceState 跨桥裁剪版（UserHandle 不可序列化，user=null 语义 = 未知）。 */
    data class PrivateSpaceWire(
        val supported: Boolean, val serial: Long, val locked: Boolean,
        val accessible: Boolean, val revision: Int, val lockVersion: Int,
    )

    fun encodeSnapshot(s: Snapshot): String = JSONObject()
        .put("ok", s.ok)
        .put("s", encodeRow(s.settings))
        .put("hid", JSONArray(s.hiddenAppKeys.toList()))
        .put("fols", JSONArray().apply {
            s.folders.forEach { f ->
                put(JSONObject()
                    .put("id", f.id).put("name", f.name)
                    .put("placement", f.placement.name)
                    .put("appListAtBottom", f.appListAtBottom)
                    .put("keys", JSONArray(f.appKeys)))
            }
        })
        .put("pps", JSONObject().apply { s.popupsJson.forEach { (k, v) -> put(k, v) } })
        .put("icons", JSONObject().apply { s.itemIcons.forEach { (k, v) -> put(k, v.encode()) } })
        .put("favOrder", JSONArray(s.favoriteOrder))
        .put("favKeys", JSONArray(s.favoriteKeys.toList()))
        .put("textMode", s.textMode.name)
        .put("themedIcons", s.themedIcons)
        .put("renames", JSONObject().apply { s.renames.forEach { (k, v) -> put(k, v) } })
        .put("cats", JSONObject().apply { s.categories.forEach { (k, v) -> put(k, v) } })
        .put("ps", s.privateSpace?.let { p -> JSONObject()
            .put("supported", p.supported).put("serial", p.serial).put("locked", p.locked)
            .put("accessible", p.accessible).put("revision", p.revision).put("lockVersion", p.lockVersion) }
            ?: JSONObject.NULL)
        .toString()

    fun decodeSnapshot(json: String?): Snapshot? = runCatching {
        val v = JSONObject(json ?: return null)
        val folders = v.optJSONArray("fols")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val f = arr.getJSONObject(i)
                    LauncherFolder(
                        id = f.getString("id"), name = f.getString("name"),
                        appKeys = f.optJSONArray("keys")?.let { ka ->
                            (0 until ka.length()).mapNotNull { j -> ka.optString(j).takeIf(String::isNotBlank) }
                        } ?: emptyList(),
                        placement = FolderPlacement.decode(f.optString("placement")),
                        appListAtBottom = f.optBoolean("appListAtBottom", true),
                    )
                }.getOrNull()
            }
        } ?: emptyList()
        Snapshot(
            ok = v.optBoolean("ok", true),
            settings = v.optJSONObject("s")?.let(::decodeRow) ?: LauncherSettings(),
            hiddenAppKeys = v.optJSONArray("hid")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) }.toSet() } ?: emptySet(),
            folders = folders,
            popupsJson = v.optJSONObject("pps")?.let { o -> o.keys().asSequence().associateWith { key -> o.optString(key) } } ?: emptyMap(),
            itemIcons = v.optJSONObject("icons")?.let { o ->
                o.keys().asSequence().mapNotNull { key -> ItemIcon.decode(o.optString(key))?.let { key to it } }.toMap()
            } ?: emptyMap(),
            favoriteOrder = v.optJSONArray("favOrder")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) } } ?: emptyList(),
            favoriteKeys = v.optJSONArray("favKeys")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf(String::isNotBlank) }.toSet() } ?: emptySet(),
            textMode = WallpaperTextMode.entries.firstOrNull { it.name == v.optString("textMode") } ?: WallpaperTextMode.Auto,
            themedIcons = v.optBoolean("themedIcons", true),
            renames = v.optJSONObject("renames")?.let { o -> o.keys().asSequence().associateWith { key -> o.optString(key) } } ?: emptyMap(),
            categories = v.optJSONObject("cats")?.let { o -> o.keys().asSequence().associateWith { key -> o.optString(key) } } ?: emptyMap(),
            privateSpace = v.optJSONObject("ps")?.let { p -> PrivateSpaceWire(
                supported = p.optBoolean("supported"), serial = p.optLong("serial", -1L),
                locked = p.optBoolean("locked", true), accessible = p.optBoolean("accessible", false),
                revision = p.optInt("revision"), lockVersion = p.optInt("lockVersion"),
            ) },
        )
    }.getOrNull()

    // ================= 命令构造（模块 → 宿主）=================

    fun command(cmd: String, arg1: String? = null, arg2: String? = null, json: String? = null) =
        android.content.Intent(ACTION_SETTINGS_COMMAND)
            .setPackage(GraceBridge.PACKAGE_HOST)
            .putExtra(GraceBridge.EXTRA_CMD, cmd)
            .putExtra(GraceBridge.EXTRA_ARG1, arg1)
            .putExtra(GraceBridge.EXTRA_ARG2, arg2)
            .putExtra(GraceBridge.EXTRA_JSON, json)

    fun requestSnapshot() = command(Cmd.REQUEST_SNAPSHOT)
}
