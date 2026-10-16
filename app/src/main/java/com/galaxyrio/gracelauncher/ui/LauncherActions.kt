package com.galaxyrio.gracelauncher.ui

import androidx.compose.ui.geometry.Rect
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import com.galaxyrio.gracelauncher.data.WallpaperTextMode

/**
 * Platform actions are injected so all launcher surfaces can be previewed and tested.
 *
 * 审计修缮（2026-10-16）：裁掉 19 个零消费字段（screenTime/importItemIcon/
 * showShortcutInAppList/categorize/copyPackageName/storePage/newEvent/openEvent/
 * refreshAgenda/themedIcons/reorderFavorites/launchAppAt/launchSearchApp/
 * prepareShortcuts + controlMedia 系 5 个）——上游 70+ 字段聚合接口搬运残留，
 * 经 `actions.\w+` 全量扫描核实零调用后删除；恢复入口见 docs/CODE_AUDIT_2026-10-16.md。
 */
data class LauncherActions(
    val requestPrivateSpace: (Boolean, () -> Unit) -> Unit = { _, _ -> },
    val closePrivateSpace: () -> Unit = {},
    val openPrivateSpaceSettings: () -> Unit = {},
    val reorderPrivateApps: (List<String>) -> Unit = {},
    val resetPrivateSpaceAppearance: suspend () -> Boolean = { false },
    val reorderWorkApps: (List<String>) -> Unit = {},
    val resetWorkProfileAppearance: suspend () -> Boolean = { false },
    val openWorkProfileSettings: () -> Unit = {},
    val appInfo: (LauncherApp) -> Unit = {},
    val uninstall: (LauncherApp) -> Unit = {},
    val rename: (LauncherApp, String) -> Unit = { _, _ -> },
    val setItemIcon: suspend (LauncherApp, ItemIcon?) -> Boolean = { _, _ -> false },
    val rememberShortcut: suspend (LauncherApp) -> Boolean = { false },
    val updatePopup: (LauncherApp, List<LauncherApp>, (List<PopupItem>) -> List<PopupItem>) -> Unit = { _, _, _ -> },
    val addPopupWidget: (LauncherApp, List<LauncherApp>) -> Unit = { _, _ -> },
    val textMode: (WallpaperTextMode) -> Unit = {},
    val refreshIconPacks: () -> Unit = {},
    val refreshApps: () -> Unit = {},
    val updateSettings: ((LauncherSettings) -> LauncherSettings) -> Unit = {},
    val applyClockStyle: suspend (ClockStyle) -> Boolean = { false },
    val deleteFonts: suspend (Set<String>) -> Boolean = { false },
    val applyIconDesign: suspend (ItemIcon) -> Boolean = { false },
    val deleteIconDesigns: suspend (Set<String>) -> Boolean = { false },
    val setHiddenApps: (Set<String>) -> Unit = {},
    val saveFolder: (LauncherFolder) -> Unit = {},
    val updateFolder: (String, String?, FolderPlacement?, Boolean?) -> Unit = { _, _, _, _ -> },
    val setFolderFavorites: suspend (Map<String, Boolean>) -> Boolean = { false },
    val deleteFolder: (String) -> Unit = {},
    val shortcuts: suspend (LauncherApp) -> ShortcutResult = { ShortcutResult(ShortcutStatus.DefaultLauncherRequired) },
    val cachedShortcuts: (LauncherApp) -> ShortcutResult? = { null },
    val launchShortcutAt: ((LauncherShortcut, Rect) -> Unit)? = null,
    val launchShortcut: (LauncherShortcut) -> Unit = {},
    val requestMediaAccess: () -> Unit = {},
    val requestWeatherAccess: () -> Unit = {},
    val addWidget: () -> Unit = {},
    val configureWidget: () -> Unit = {},
    val removeWidget: () -> Unit = {},
    val moveWidget: () -> Unit = {},
    val refreshWeather: () -> Unit = {},
    val openWeatherApp: () -> Unit = {},
)
