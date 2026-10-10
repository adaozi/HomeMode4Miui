/*
 * 上游同步 v1.1.8 S3 宿主原生增强（HomeMode4Miui 专属，非上游代码）——
 * 逆向实锤（MiuiHomeOS3.apk + MiuiHomeOS2.apk 双版本一致，androguard 静态分析）：
 *
 *   锁屏     = MIUI 桌面双击锁屏的原生广播（SuperDraglayer$DoubleTapListener.onDoubleTapEvent /
 *              旧版 Workspace.onDoubleTap 逐字语义）：
 *              sendBroadcast(Intent("com.miui.app.ExtraStatusBarManager.action_TRIGGER_TOGGLE")
 *                  .addFlags(FLAG_ACTIVITY_NEW_TASK)
 *                  .putExtra("com.miui.app.ExtraStatusBarManager.extra_TOGGLE_ID", 10))
 *              —— TOGGLE_ID 10 = 锁屏 toggle，OS2/OS3 双版本 const/16 2,10 完全一致
 *   通知栏   = LauncherUtils.expandStatusBar(Context) 原生复刻：
 *              getSystemService("statusbar") + 反射 expandNotificationsPanel
 *   快捷设置 = 同 manager 反射 expandSettingsPanel（无参重载）
 *
 * 全部走宿主原生身份（hook 原版桌面角色），无 root / 无无障碍依赖；
 * 失败时由 GraceSystemActions 兜底链接 GraceRootActions（root）→ GraceAccessibilityService。
 * runCatching 全兜底，零崩溃铁律。
 */
package com.galaxyrio.gracelauncher.platform

import android.content.Context
import android.content.Intent
import com.homemode4miui.util.ModLog

internal object HostSystemActions {
    private const val ACTION_TRIGGER_TOGGLE = "com.miui.app.ExtraStatusBarManager.action_TRIGGER_TOGGLE"
    private const val EXTRA_TOGGLE_ID = "com.miui.app.ExtraStatusBarManager.extra_TOGGLE_ID"
    /** MIUI 预定义 toggle：10 = 锁屏（OS2/OS3 双版本逆向一致）。 */
    private const val TOGGLE_ID_LOCK_SCREEN = 10

    /** 锁屏：MIUI 原生 toggle 广播（桌面双击锁屏同款，无需任何授权）。 */
    fun lockScreen(context: Context): Boolean = runCatching {
        context.sendBroadcast(
            Intent(ACTION_TRIGGER_TOGGLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_TOGGLE_ID, TOGGLE_ID_LOCK_SCREEN),
        )
        true
    }.onFailure { ModLog.i("HostSystemActions lockScreen failed: ${it.javaClass.simpleName}") }
        .getOrDefault(false)

    /** 通知栏：StatusBarManager 反射（复刻 LauncherUtils.expandStatusBar）。 */
    fun expandNotifications(context: Context): Boolean = invokeStatusBar(context, "expandNotificationsPanel")

    /** 快捷设置：StatusBarManager 反射 expandSettingsPanel（无参重载）。 */
    fun expandQuickSettings(context: Context): Boolean = invokeStatusBar(context, "expandSettingsPanel")

    private fun invokeStatusBar(context: Context, method: String): Boolean = runCatching {
        val manager = context.getSystemService("statusbar") ?: return false
        manager.javaClass.getMethod(method).invoke(manager)
        true
    }.onFailure { ModLog.i("HostSystemActions $method failed: ${it.javaClass.simpleName}") }
        .getOrDefault(false)
}
