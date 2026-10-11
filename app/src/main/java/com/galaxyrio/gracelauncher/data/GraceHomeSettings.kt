/*
 * P1 精简设置模型（新建文件，非搬运）—— PORTING_GraceLauncher.md B1.4 偏好落地。
 *
 * 原版 LauncherSettings（data/LauncherSettings.kt）依赖 ItemIcon / ProfileSettings /
 * SearchSettings / GraceButtonSettings 等 P4/P6 资产，且由 Room（LauncherSettingsRepository）
 * 持久化——整体留待 P3。本类只承载首页渲染面用到的字段，走 SP 持久化：
 *   - clockStyle 用原版 ClockStyle.encode()/decode() JSON 格式落盘，P3 切 Room 时
 *     字节级兼容，直接迁移进 LauncherSettings.clockStyle。
 *   - SP 文件名带 grace_ 前缀（grace_launcher_settings），落在 com.miui.home 进程
 *     shared_prefs，与宿主偏好隔离；切回原生模式不清理（用户数据保留）。
 *
 * P6（B6.x）扩展：附属特性开关面全部补齐 —— 字段名与原版 LauncherSettings 一一
 * 对应，全部命中 Room launcher_settings 表 v18 已有列（P3 一次到位策略），零 migration：
 *   calendarAgenda / calendarAboveClock / showBatteryPercentage（B6.2 日程 + 头部行）
 *   mediaPlayer / mediaAlwaysVisible（B6.1 媒体；mediaAppKey 属 C 组媒体兜底应用）
 *   weatherEnabled / weatherForecastDays（B6.2 天气；weatherLocationId 固定首地点，C 组配）
 *   iconPackPackages（B6.4 图标包；iconDesignJson/iconPackPackage 旧单包字段仅历史兼容）
 *   privateSpace（B6.5；完整 UI 载体等 folders 阶段）
 */
package com.galaxyrio.gracelauncher.data

import android.content.Context
import androidx.core.content.edit

data class GraceHomeSettings(
    val clockEnabled: Boolean = true,
    val clockStyle: ClockStyle = ClockStyle(),
    val hideFavoriteNames: Boolean = false,
    /** P2：搜索设置（最近应用建议落盘；原版由 Room 承载，P3 迁入 LauncherSettings.search）。 */
    val searchSettings: SearchSettings = SearchSettings(),
    /** P4：首页布局（widgetId/provider/label/height + topOffset），原版 JSON 格式，Room launcher_settings.homeLayoutJson。 */
    val homeLayout: HomeLayout = HomeLayout(),
    // ---- P6（B6.x）附属特性开关 ----
    /** B6.2：首页下一事件行 + Agenda 面板（原版默认 true）。 */
    val calendarAgenda: Boolean = true,
    /** B6.2：日程行置于时钟上方（原版默认 false）。 */
    val calendarAboveClock: Boolean = false,
    /** 日期行尾电量百分比（原版默认 true；原版只在 calendarAgenda 时渲染）。 */
    val showBatteryPercentage: Boolean = true,
    /** B6.1：媒体控制（原版默认 true；实际显示还需通知监听权限，无权限自然隐藏）。 */
    val mediaPlayer: Boolean = true,
    /** B6.1：无播放会话时常驻占位播放器（原版默认 false）。 */
    val mediaAlwaysVisible: Boolean = false,
    /** B6.2：天气（原版默认 false；实际显示还需模块 App 读到 Breezy provider 快照）。 */
    val weatherEnabled: Boolean = false,
    /** B6.2：Agenda 面板预报天数（原版默认 7，clamp 1..14）。 */
    val weatherForecastDays: Int = 7,
    /** B6.4：启用的图标包（fallback 顺序；原版 iconPackPackages JSON 列）。 */
    val iconPackPackages: List<String> = emptyList(),
    /** B6.5：Private Space 设置（JSON 列 privateSpaceJson；完整 UI 载体等 folders 阶段）。 */
    val privateSpace: ProfileSettings = ProfileSettings(),
    // ---- C 组（hotfix3）：外观字段接通宿主渲染（原版 LauncherSettings 同名列）----
    /** 原版 hideStatusBar：状态栏 autoHide（hotfix6 接通，LauncherSystemBars）。 */
    val hideStatusBar: Boolean = true,
    /** 原版 hideAlphabet：隐藏抽屉字母索引 rail。 */
    val hideAlphabet: Boolean = false,
    /** 原版 dimWallpaper：抽屉/搜索时壁纸加深色遮罩（强度 wallpaperDimAmount/100）。 */
    val dimWallpaper: Boolean = false,
    /** 原版 blurWallpaper：抽屉/搜索时壁纸模糊（半径 wallpaperBlurRadius）。 */
    val blurWallpaper: Boolean = true,
    /** 原版 wallpaperDimAmount：遮罩强度 0..100（默认 20）。 */
    val wallpaperDimAmount: Int = 20,
    /** 原版 wallpaperBlurRadius：模糊半径（默认 16）。 */
    val wallpaperBlurRadius: Int = 16,
    // ---- 上游同步 v1.1.8（S2）：列表外观 + 弹层/文件夹/通知显示策略 ----
    /** 原版 listAppearance：行距/边距/字号/名字可见性/A-Z rail 外观（JSON 列 listAppearanceJson）。 */
    val listAppearance: ListAppearance = ListAppearance(),
    /** 原版 shortcutsFolders：弹层/文件夹设置 + 通知显示策略（JSON 列 shortcutsFoldersJson）。 */
    val shortcutsFolders: ShortcutsFoldersSettings = ShortcutsFoldersSettings(),
    /** 上游同步 v1.1.8 S3：桌面手势模型（JSON 列 homeGesturesJson；默认 = homeDefaults）。 */
    val homeGestures: GraceButtonSettings = GraceButtonSettings.homeDefaults(),
    /** 上游同步 v1.1.8 S4：Grace Button 模型（JSON 列 graceButtonJson；schema v17 已有列）。 */
    val graceButton: GraceButtonSettings = GraceButtonSettings(),
    /** 上游同步 v1.1.8 S5：字体库（JSON 列 fontLibraryJson；decode 兼容 appFontId 旧选择）。 */
    val fontLibrary: FontLibrary = FontLibrary(),
    /** 上游同步 v1.1.8 S8a：工作资料模型（JSON 列 workProfileJson；schema v19 已有列）。 */
    val workProfile: ProfileSettings = ProfileSettings.workDefaults(),
) {
    /** 对齐原版 LauncherSettings.enabledIconPackPackages：空列表 = 系统图标。 */
    val enabledIconPackPackages: List<String>
        get() = normalizeIconPackOrder(iconPackPackages)

    companion object {
        fun decode(clockStyleJson: String?, clockEnabled: Boolean, hideFavoriteNames: Boolean,
                   searchSettingsJson: String? = null, homeLayoutJson: String? = null,
                   calendarAgenda: Boolean = true, calendarAboveClock: Boolean = false,
                   showBatteryPercentage: Boolean = true, mediaPlayer: Boolean = true,
                   mediaAlwaysVisible: Boolean = false, weatherEnabled: Boolean = false,
                   weatherForecastDays: Int = 7, iconPackPackagesJson: String? = null,
                   privateSpaceJson: String? = null,
                   hideStatusBar: Boolean = true,
                   hideAlphabet: Boolean = false, dimWallpaper: Boolean = false,
                   blurWallpaper: Boolean = true, wallpaperDimAmount: Int = 20,
                   wallpaperBlurRadius: Int = 16,
                   listAppearanceJson: String? = null,
                   shortcutsFoldersJson: String? = null,
                   homeGesturesJson: String? = null,
                   graceButtonJson: String? = null,
                   fontLibraryJson: String? = null,
                   workProfileJson: String? = null) = GraceHomeSettings(
            clockEnabled = clockEnabled,
            clockStyle = ClockStyle.decode(clockStyleJson),
            hideFavoriteNames = hideFavoriteNames,
            searchSettings = SearchSettings.decode(searchSettingsJson),
            homeLayout = HomeLayout.decode(homeLayoutJson),
            calendarAgenda = calendarAgenda,
            calendarAboveClock = calendarAboveClock,
            showBatteryPercentage = showBatteryPercentage,
            mediaPlayer = mediaPlayer,
            mediaAlwaysVisible = mediaAlwaysVisible,
            weatherEnabled = weatherEnabled,
            weatherForecastDays = weatherForecastDays.coerceIn(1, 14),
            iconPackPackages = decodeStringList(iconPackPackagesJson),
            privateSpace = ProfileSettings.decode(privateSpaceJson),
            hideStatusBar = hideStatusBar,
            hideAlphabet = hideAlphabet,
            dimWallpaper = dimWallpaper,
            blurWallpaper = blurWallpaper,
            wallpaperDimAmount = wallpaperDimAmount.coerceIn(0, 100),
            wallpaperBlurRadius = wallpaperBlurRadius.coerceIn(0, 64),
            listAppearance = ListAppearance.decode(listAppearanceJson, hideFavoriteNames),
            shortcutsFolders = ShortcutsFoldersSettings.decode(shortcutsFoldersJson),
            homeGestures = GraceButtonSettings.decode(homeGesturesJson, GraceButtonSettings.homeDefaults()),
            graceButton = GraceButtonSettings.decode(graceButtonJson),
            fontLibrary = FontLibrary.decode(fontLibraryJson),
            workProfile = ProfileSettings.decode(workProfileJson, ProfileSettings.workDefaults()),
        )

        /** 原版 LauncherSettingsRepository.settingsJson 同构：字符串列表 JSON。 */
        fun decodeStringList(json: String?): List<String> = runCatching {
            val array = org.json.JSONArray(json ?: return emptyList())
            (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())

        fun encodeStringList(values: List<String>): String =
            org.json.JSONArray().apply { values.filter(String::isNotBlank).forEach { put(it) } }.toString()
    }
}

/** SP 同步读写。数据量极小（十几个 key），主线程直写与原版 SharedPreferences 语义一致。 */
class GraceHomeSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): GraceHomeSettings = GraceHomeSettings.decode(
        clockStyleJson = preferences.getString(KEY_CLOCK_STYLE, null),
        clockEnabled = preferences.getBoolean(KEY_CLOCK_ENABLED, true),
        hideFavoriteNames = preferences.getBoolean(KEY_HIDE_FAVORITE_NAMES, false),
        searchSettingsJson = preferences.getString(KEY_SEARCH_SETTINGS, null),
        homeLayoutJson = preferences.getString(KEY_HOME_LAYOUT, null),
        calendarAgenda = preferences.getBoolean(KEY_CALENDAR_AGENDA, true),
        calendarAboveClock = preferences.getBoolean(KEY_CALENDAR_ABOVE_CLOCK, false),
        showBatteryPercentage = preferences.getBoolean(KEY_SHOW_BATTERY, true),
        mediaPlayer = preferences.getBoolean(KEY_MEDIA_PLAYER, true),
        mediaAlwaysVisible = preferences.getBoolean(KEY_MEDIA_ALWAYS, false),
        weatherEnabled = preferences.getBoolean(KEY_WEATHER_ENABLED, false),
        weatherForecastDays = preferences.getInt(KEY_WEATHER_DAYS, 7),
        iconPackPackagesJson = preferences.getString(KEY_ICON_PACKS, null),
        privateSpaceJson = preferences.getString(KEY_PRIVATE_SPACE, null),
        hideStatusBar = preferences.getBoolean(KEY_HIDE_STATUS_BAR, true),
        hideAlphabet = preferences.getBoolean(KEY_HIDE_ALPHABET, false),
        dimWallpaper = preferences.getBoolean(KEY_DIM_WALLPAPER, false),
        blurWallpaper = preferences.getBoolean(KEY_BLUR_WALLPAPER, true),
        wallpaperDimAmount = preferences.getInt(KEY_WALLPAPER_DIM, 20),
        wallpaperBlurRadius = preferences.getInt(KEY_WALLPAPER_BLUR_RADIUS, 16),
        listAppearanceJson = preferences.getString(KEY_LIST_APPEARANCE, null),
        shortcutsFoldersJson = preferences.getString(KEY_SHORTCUTS_FOLDERS, null),
        homeGesturesJson = preferences.getString(KEY_HOME_GESTURES, null),
        graceButtonJson = preferences.getString(KEY_GRACE_BUTTON, null),
        fontLibraryJson = preferences.getString(KEY_FONT_LIBRARY, null),
        workProfileJson = preferences.getString(KEY_WORK_PROFILE, null),
    )

    fun save(settings: GraceHomeSettings) = preferences.edit {
        putBoolean(KEY_CLOCK_ENABLED, settings.clockEnabled)
        putString(KEY_CLOCK_STYLE, settings.clockStyle.encode())
        putBoolean(KEY_HIDE_FAVORITE_NAMES, settings.hideFavoriteNames)
        putString(KEY_SEARCH_SETTINGS, settings.searchSettings.encode())
        putString(KEY_HOME_LAYOUT, settings.homeLayout.encode())
        putBoolean(KEY_CALENDAR_AGENDA, settings.calendarAgenda)
        putBoolean(KEY_CALENDAR_ABOVE_CLOCK, settings.calendarAboveClock)
        putBoolean(KEY_SHOW_BATTERY, settings.showBatteryPercentage)
        putBoolean(KEY_MEDIA_PLAYER, settings.mediaPlayer)
        putBoolean(KEY_MEDIA_ALWAYS, settings.mediaAlwaysVisible)
        putBoolean(KEY_WEATHER_ENABLED, settings.weatherEnabled)
        putInt(KEY_WEATHER_DAYS, settings.weatherForecastDays)
        putString(KEY_ICON_PACKS, GraceHomeSettings.encodeStringList(settings.iconPackPackages))
        putString(KEY_PRIVATE_SPACE, settings.privateSpace.encode())
        putBoolean(KEY_HIDE_STATUS_BAR, settings.hideStatusBar)
        putBoolean(KEY_HIDE_ALPHABET, settings.hideAlphabet)
        putBoolean(KEY_DIM_WALLPAPER, settings.dimWallpaper)
        putBoolean(KEY_BLUR_WALLPAPER, settings.blurWallpaper)
        putInt(KEY_WALLPAPER_DIM, settings.wallpaperDimAmount)
        putInt(KEY_WALLPAPER_BLUR_RADIUS, settings.wallpaperBlurRadius)
        putString(KEY_LIST_APPEARANCE, settings.listAppearance.encode())
        putString(KEY_SHORTCUTS_FOLDERS, settings.shortcutsFolders.encode())
        putString(KEY_HOME_GESTURES, settings.homeGestures.encode())
        putString(KEY_GRACE_BUTTON, settings.graceButton.encode())
        putString(KEY_FONT_LIBRARY, settings.fontLibrary.encode())
        putString(KEY_WORK_PROFILE, settings.workProfile.encode())
    }

    private companion object {
        const val FILE_NAME = "grace_launcher_settings"
        const val KEY_CLOCK_ENABLED = "clock_enabled"
        const val KEY_CLOCK_STYLE = "clock_style"
        const val KEY_HIDE_FAVORITE_NAMES = "hide_favorite_names"
        const val KEY_SEARCH_SETTINGS = "search_settings"
        const val KEY_HOME_LAYOUT = "home_layout"
        const val KEY_CALENDAR_AGENDA = "calendar_agenda"
        const val KEY_CALENDAR_ABOVE_CLOCK = "calendar_above_clock"
        const val KEY_SHOW_BATTERY = "show_battery_percentage"
        const val KEY_MEDIA_PLAYER = "media_player"
        const val KEY_MEDIA_ALWAYS = "media_always_visible"
        const val KEY_WEATHER_ENABLED = "weather_enabled"
        const val KEY_WEATHER_DAYS = "weather_forecast_days"
        const val KEY_ICON_PACKS = "icon_pack_packages"
        const val KEY_PRIVATE_SPACE = "private_space"
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        const val KEY_HIDE_ALPHABET = "hide_alphabet"
        const val KEY_DIM_WALLPAPER = "dim_wallpaper"
        const val KEY_BLUR_WALLPAPER = "blur_wallpaper"
        const val KEY_WALLPAPER_DIM = "wallpaper_dim_amount"
        const val KEY_WALLPAPER_BLUR_RADIUS = "wallpaper_blur_radius"
        const val KEY_LIST_APPEARANCE = "list_appearance"
        const val KEY_SHORTCUTS_FOLDERS = "shortcuts_folders"
        const val KEY_HOME_GESTURES = "home_gestures"
        const val KEY_GRACE_BUTTON = "grace_button"
        const val KEY_FONT_LIBRARY = "font_library"
        const val KEY_WORK_PROFILE = "work_profile"
    }
}
