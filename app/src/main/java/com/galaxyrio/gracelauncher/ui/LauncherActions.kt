package com.galaxyrio.gracelauncher.ui

import androidx.compose.ui.geometry.Rect
import android.net.Uri
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.FolderPlacement
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import com.galaxyrio.gracelauncher.data.WallpaperTextMode
import com.galaxyrio.gracelauncher.data.media.MediaCommand

/** Platform actions are injected so all launcher surfaces can be previewed and tested. */
data class LauncherActions(
    val requestDefaultHome: () -> Unit = {},
    val requestPrivateSpace: (Boolean, () -> Unit) -> Unit = { _, _ -> },
    val closePrivateSpace: () -> Unit = {},
    val openPrivateSpaceSettings: () -> Unit = {},
    val reorderPrivateApps: (List<String>) -> Unit = {},
    val resetPrivateSpaceAppearance: suspend () -> Boolean = { false },
    val reorderWorkApps: (List<String>) -> Unit = {},
    val resetWorkProfileAppearance: suspend () -> Boolean = { false },
    val openWorkProfileSettings: () -> Unit = {},
    val appInfo: (LauncherApp) -> Unit = {},
    val screenTime: (LauncherApp) -> Unit = {},
    val uninstall: (LauncherApp) -> Unit = {},
    val rename: (LauncherApp, String) -> Unit = { _, _ -> },
    val setItemIcon: suspend (LauncherApp, ItemIcon?) -> Boolean = { _, _ -> false },
    val importItemIcon: suspend (LauncherApp, Uri) -> Boolean = { _, _ -> false },
    val showShortcutInAppList: (LauncherApp, Boolean) -> Unit = { _, _ -> },
    val rememberShortcut: suspend (LauncherApp) -> Boolean = { false },
    val updatePopup: (LauncherApp, List<LauncherApp>, (List<PopupItem>) -> List<PopupItem>) -> Unit = { _, _, _ -> },
    val addPopupWidget: (LauncherApp, List<LauncherApp>) -> Unit = { _, _ -> },
    val categorize: (LauncherApp, String?) -> Unit = { _, _ -> },
    val copyPackageName: (LauncherApp) -> Unit = {},
    val storePage: (LauncherApp) -> Unit = {},
    val newEvent: () -> Unit = {},
    val openEvent: (ScheduleEvent) -> Unit = {},
    val refreshAgenda: () -> Unit = {},
    val textMode: (WallpaperTextMode) -> Unit = {},
    val themedIcons: (Boolean) -> Unit = {},
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
    val prepareShortcuts: (LauncherApp) -> Unit = {},
    val reorderFavorites: (List<String>) -> Unit = {},
    val launchAppAt: ((LauncherApp, Rect) -> Unit)? = null,
    val launchSearchApp: ((LauncherApp, Rect?) -> Unit)? = null,
    val launchShortcutAt: ((LauncherShortcut, Rect) -> Unit)? = null,
    val launchShortcut: (LauncherShortcut) -> Unit = {},
    val requestMediaAccess: () -> Unit = {},
    val controlMedia: (String, MediaCommand) -> Unit = { _, _ -> },
    val dismissMedia: (String, Long) -> Boolean = { _, _ -> false },
    val dismissNotification: (String, Long) -> Boolean = { _, _ -> false },
    val openNotification: (String, Long) -> Boolean = { _, _ -> false },
    val requestWeatherAccess: () -> Unit = {},
    val requestCalendarAccess: () -> Unit = {},
    val addWidget: () -> Unit = {},
    val configureWidget: () -> Unit = {},
    val removeWidget: () -> Unit = {},
    val moveWidget: () -> Unit = {},
    val refreshWeather: () -> Unit = {},
    val openBreezyWeather: () -> Unit = {},
    val installBreezyWeather: () -> Unit = {},
)
