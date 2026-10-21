package com.galaxyrio.gracelauncher.data

import com.materialkolor.PaletteStyle

enum class ThemeMode { System, Light, Dark }

// C 组搬运注记：原版此处的 AppFont 枚举未随搬（本仓 data/AppFont.kt 已承载，P1 字体链路）。

data class LauncherSettings(
    val clockEnabled: Boolean = true,
    val calendarAgenda: Boolean = true,
    val calendarAboveClock: Boolean = false,
    val showBatteryPercentage: Boolean = true,
    val allowHapticFeedback: Boolean = true,
    val useDynamicColors: Boolean = true,
    val themeColor: Int = DEFAULT_THEME_COLOR,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val darkMode: ThemeMode = ThemeMode.System,
    val amoledMode: Boolean = false,
    /** Retained for installations that used the original single-pack setting. */
    val iconPackPackage: String? = null,
    val iconPackPackages: List<String> = emptyList(),
    val iconDesign: ItemIcon? = null,
    val mediaPlayer: Boolean = true,
    val mediaAlwaysVisible: Boolean = false,
    val mediaAppKey: String? = null,
    val weatherEnabled: Boolean = false,
    val weatherForecastDays: Int = 7,
    val weatherLocationId: String? = null,
    /** null delegates to Android's standard SHOW_ALARMS action. */
    val clockAppKey: String? = null,
    val clockStyle: ClockStyle = ClockStyle(),
    val homeLayout: HomeLayout = HomeLayout(),
    val hideStatusBar: Boolean = true,
    val hideAlphabet: Boolean = false,
    /** Legacy column; ListAppearance migrates this into names on first read. */
    val hideFavoriteNames: Boolean = false,
    val dimWallpaper: Boolean = false,
    val wallpaperDimAmount: Int = 20,
    val blurWallpaper: Boolean = true,
    val wallpaperBlurRadius: Int = 16,
    /** Legacy selection, used only when migrating to the ordered font library. */
    val appFontId: String? = null,
    val fontLibrary: FontLibrary = FontLibrary(),
    val listAppearance: ListAppearance = ListAppearance(),
    val applyFontToSettings: Boolean = true,
    val privateSpace: ProfileSettings = ProfileSettings(),
    val workProfile: ProfileSettings = ProfileSettings.workDefaults(),
    val search: SearchSettings = SearchSettings(),
    val shortcutsFolders: ShortcutsFoldersSettings = ShortcutsFoldersSettings(),
    val graceButton: GraceButtonSettings = GraceButtonSettings(),
    val homeGestures: GraceButtonSettings = GraceButtonSettings.homeDefaults(),
) {
    /** Icon designer overrides precede this order; system icons always follow it. */
    val enabledIconPackPackages: List<String>
        get() = normalizeIconPackOrder(iconPackPackages.ifEmpty { listOfNotNull(iconPackPackage) })

    /** Shared icon display lives inside the bulk design, same shape as per-app overrides. */
    val iconDisplay: IconDisplaySettings
        get() = iconDesign?.display ?: IconDisplaySettings()

    fun withIconPacks(packages: List<String>): LauncherSettings = copy(
        iconPackPackage = null,
        iconPackPackages = normalizeIconPackOrder(packages),
    )

    fun gestureSettings(home: Boolean): GraceButtonSettings = if (home) homeGestures else graceButton
    fun withGestureSettings(home: Boolean, transform: (GraceButtonSettings) -> GraceButtonSettings): LauncherSettings =
        if (home) copy(homeGestures = transform(homeGestures)) else copy(graceButton = transform(graceButton))

    companion object {
        /** 铁律整改：默认主题种子色单一事实源（上游默认值；seed/解码默认值统一引用本常量） */
        const val DEFAULT_THEME_COLOR = 0xFF6750A4.toInt()
    }
}

enum class FolderPlacement(val inFavorites: Boolean, val inAppList: Boolean) {
    Favorites(true, false), AppList(false, true), Both(true, true), None(false, false);

    fun withFavorites(enabled: Boolean): FolderPlacement = of(enabled, inAppList)
    fun withAppList(enabled: Boolean): FolderPlacement = of(inFavorites, enabled)

    companion object {
        fun of(inFavorites: Boolean, inAppList: Boolean): FolderPlacement =
            entries.first { it.inFavorites == inFavorites && it.inAppList == inAppList }

        // Preserve the existing stored Favorites/AppList values on upgrade.
        fun decode(value: String): FolderPlacement = entries.firstOrNull { it.name == value } ?: AppList
    }
}

const val RecentlyInstalledFolderId = "recently-installed"

data class LauncherFolder(
    val id: String,
    val name: String,
    val appKeys: List<String>,
    val placement: FolderPlacement,
    /** Only affects the app list; favorites always use their shared manual order. */
    val appListAtBottom: Boolean = true,
    /** Membership-derived hiding never changes the explicit hidden-app table. */
    val autoHideApps: Boolean = false,
    /** 上游同步 1.2.0：同一套成员/位置存储，但渲染成横向应用堆而不是有名字的文件夹。 */
    val isAppStack: Boolean = false,
) {
    val key: String get() = "folder:$id"

    /** Local identity only: folders must never be sent to Android as activities. */
    fun asApp() = LauncherApp(
        componentName = android.content.ComponentName("com.homemode4miui", key),
        label = name, icon = null, folderId = id, isAppStack = isAppStack,
    )
}

/** A complete first emission is available only after all Room tables have loaded. */
data class LauncherStorageSnapshot(
    val settings: LauncherSettings = LauncherSettings(),
    val hiddenAppKeys: Set<String> = emptySet(),
    val folders: List<LauncherFolder> = emptyList(),
) {
    /** Explicit, manually hidden keys only. */
    val folderHiddenAppKeys: Set<String> get() = hiddenAppsFromFolders(folders)
    val effectiveHiddenAppKeys: Set<String> get() = hiddenAppKeys + folderHiddenAppKeys
}

/** Derive this union from current membership, including folders not shown on any surface. */
internal fun hiddenAppsFromFolders(folders: List<LauncherFolder>): Set<String> =
    folders.asSequence().filter { it.autoHideApps }.flatMap { it.appKeys.asSequence() }
        .filter(String::isNotBlank).toSet()

internal fun decodePaletteStyle(value: String): PaletteStyle =
    PaletteStyle.entries.firstOrNull { it.name == value } ?: PaletteStyle.TonalSpot
