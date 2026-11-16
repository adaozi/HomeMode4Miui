/*
 * P11a（2026-11-16）：模块桌面状态 → 上游 LauncherUiState 契约的适配层。
 *
 * 为什么需要：模块 GraceHomeViewModel / GraceHomeUiState 是自研状态（26+ 字段，桌面侧语义），
 * 上游 1.2.4 的全部 Composable（HomeScreen / ClockRegionHeader / AlphabetRail / 各类编辑器）
 * 统一吃 `LauncherUiState`（67 个字段 + 派生 getter）。P11b 要把 HomeScreen 等换成上游实现，
 * 就必须先把模块状态投影成这份契约。
 *
 * 本文件在 P11a 只做「能编译 + 字段语义对齐」，**暂不被任何调用方引用**（零行为变化）；
 * P11b 切换 HomeScreen 时才接线，并逐字段回归（尤其 settings 的映射）。
 */
package com.homemode4miui.grace

import com.galaxyrio.gracelauncher.data.GraceHomeSettings
import com.galaxyrio.gracelauncher.data.LauncherSettings
import com.galaxyrio.gracelauncher.ui.ScheduleStatus
import com.galaxyrio.gracelauncher.data.media.MediaSnapshot
import com.galaxyrio.gracelauncher.ui.LauncherUiState

/** 模块桌面侧的设置模型投影成上游完整设置模型（模块没有的字段取上游默认值）。 */
internal fun GraceHomeSettings.toLauncherSettings(): LauncherSettings = LauncherSettings(
    clockEnabled = clockEnabled,
    calendarAgenda = calendarAgenda,
    calendarAboveClock = calendarAboveClock,
    showBatteryPercentage = showBatteryPercentage,
    allowHapticFeedback = allowHapticFeedback,
    useDynamicColors = useDynamicColors,
    themeColor = themeColor,
    paletteStyle = paletteStyle,
    darkMode = darkMode,
    amoledMode = amoledMode,
    iconPackPackages = iconPackPackages,
    iconDesign = iconDesign,
    mediaPlayer = mediaPlayer,
    mediaAlwaysVisible = mediaAlwaysVisible,
    mediaAppKey = mediaAppKey,
    weatherEnabled = weatherEnabled,
    weatherForecastDays = weatherForecastDays,
    clockAppKey = clockAppKey,
    clockStyle = clockStyle,
    homeLayout = homeLayout,
    hideStatusBar = hideStatusBar,
    hideAlphabet = hideAlphabet,
    hideFavoriteNames = hideFavoriteNames,
    dimWallpaper = dimWallpaper,
    wallpaperDimAmount = wallpaperDimAmount,
    blurWallpaper = blurWallpaper,
    wallpaperBlurRadius = wallpaperBlurRadius,
    appFontId = appFontId,
    fontLibrary = fontLibrary,
    fontColors = fontColors,
    listAppearance = listAppearance,
    privateSpace = privateSpace,
    workProfile = workProfile,
    search = searchSettings,
    shortcutsFolders = shortcutsFolders,
    graceButton = graceButton,
    homeGestures = homeGestures,
    calendar = calendar,
    // 上游 1.2.2：类别必须带出 —— 少了这行，launcher.appCategories 恒为 null，
    // 读取侧永远走 migrateHomeCategories 生成内置四类，用户创建的类别到不了桌面
    // （2026-11-16 实测：MUTATE 与 applyDelta 都正常，日志显示 appCategories=6，桌面仍只有 4 类）。
    appCategories = appCategories,
)

/** GraceScheduleStatus → 上游 ScheduleStatus（同名枚举，模块侧独立定义以免与上游耦合）。 */
internal fun GraceScheduleStatus.toScheduleStatus(): ScheduleStatus = when (this) {
    GraceScheduleStatus.PermissionRequired -> ScheduleStatus.PermissionRequired
    GraceScheduleStatus.Loading -> ScheduleStatus.Loading
    GraceScheduleStatus.Ready -> ScheduleStatus.Ready
    GraceScheduleStatus.Error -> ScheduleStatus.Error
}

/**
 * 模块桌面状态 → 上游 UI 契约。
 *
 * 约定：
 *  - 模块没有的字段一律取上游默认值（isDefaultHome 保持 null = 未查询，避免首帧横幅闪烁）
 *  - `favoriteKeys` 由 `favoriteOrder` 派生（模块把收藏顺序存在 order 里，成员集合等价）
 *  - `folderApps` 模块是 Map<folderId, LauncherApp>，上游是 List，取 values
 *  - 类别 / 使用时长等字段 P9 落地后才会被真实填充
 */
internal fun GraceHomeUiState.toLauncherUiState(): LauncherUiState = LauncherUiState(
    apps = apps,
    favoriteKeys = favoriteOrder.toSet(),
    favoriteOrder = favoriteOrder,
    isLoadingApps = isLoadingApps,
    appLoadFailed = appLoadFailed,
    events = events,
    scheduleStatus = scheduleStatus.toScheduleStatus(),
    hasShortcutAccess = hasShortcutAccess,
    settings = settings.toLauncherSettings(),
    hiddenAppKeys = hiddenAppKeys,
    folders = folders,
    media = MediaSnapshot(hasAccess = hasMediaAccess, nowPlaying = homeMedia),
    notifications = notifications,
    weather = weather,
    shortcutApps = savedShortcutApps,
    folderApps = folderApps.values.toList(),
    popups = popups,
    itemIcons = itemIcons,
    workProfiles = workProfiles,
    workProfileApps = workProfileApps,
    shownShortcutKeys = shownShortcutKeys,
    graceButtonApp = null,
    themedIcons = true,
)
