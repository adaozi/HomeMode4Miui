/*
 * P4 裁剪版 actions —— 对应原版 ui/LauncherActions.kt（GPL-3.0，
 * https://github.com/Galaxy-rio/GraceLauncher）的 P4 子集（新建文件，非搬运）。
 *
 * 原版 LauncherActions 是 70+ 字段的聚合接口（依赖 LauncherUiState 全家），
 * 本模块 ViewModel 为轻量自建（GraceHomeViewModel），P4 只需弹层/widget 相关
 * 入口，故拆出本子集；C 组设置全家桶落地后评估并回聚合。
 */
package com.galaxyrio.gracelauncher.ui

import androidx.compose.ui.geometry.Rect
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.ShortcutStatus

data class GracePopupActions(
    val shortcuts: suspend (LauncherApp) -> ShortcutResult = { ShortcutResult(ShortcutStatus.DefaultLauncherRequired) },
    val cachedShortcuts: (LauncherApp) -> ShortcutResult? = { null },
    val launchAppAt: ((LauncherApp, Rect) -> Unit)? = null,
    val launchShortcutAt: ((LauncherShortcut, Rect) -> Unit)? = null,
    val launchShortcut: (LauncherShortcut) -> Unit = {},
    // ---- P4 widget 管理入口（bind/configure 链路在宿主进程内完成）----
    val appInfo: (LauncherApp) -> Unit = {},
    val addWidget: () -> Unit = {},
    val configureWidget: () -> Unit = {},
    val removeWidget: () -> Unit = {},
    val moveWidget: () -> Unit = {},
    /** 原版导航设置页 ClockStyle；本模块 P4 以循环切换承载（P1 演示入口迁入）。 */
    val cycleClockStyle: () -> Unit = {},
    // ---- P6（B6.4）：图标包选择面板入口 ----
    val openIconPacks: () -> Unit = {},
)
