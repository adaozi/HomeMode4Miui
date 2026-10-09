/*
 * P6（B6.5）Private Space 轻量探测 —— 新建文件（降级声明）。
 *
 * 原版 PrivateSpaceController（platform/PrivateSpaceController.kt，310 行）的
 * 解锁/锁定/凭据会话管理依赖抽屉 PrivateApp/文件夹 UI 载体（folders 系统本模块
 * 尚未移植，原版 P3+ 面）—— 完整搬运推迟到 folders 阶段（PORTING_GraceLauncher.md
 * B6.5 恢复节点）。本阶段只提供探测 + 系统设置入口：
 *   - supported：API 35+（Android 15 private profile）
 *   - exists：默认桌面身份下 launcherApps.profiles 含 USER_TYPE_PROFILE_PRIVATE
 *   - locked：QuietMode/未解锁状态
 * 设置落 GraceHomeSettings.privateSpace（原版 JSON 列 privateSpaceJson 已就位）。
 */
package com.galaxyrio.gracelauncher.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.provider.Settings

object PrivateSpaceProbe {

    data class State(val supported: Boolean, val exists: Boolean, val locked: Boolean)

    fun probe(context: Context): State {
        if (Build.VERSION.SDK_INT < 35) return State(false, false, true)
        return runCatching {
            val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)
            val users = context.getSystemService(UserManager::class.java)
            val profile = launcherApps?.profiles?.firstOrNull {
                launcherApps.getLauncherUserInfo(it)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
            }
            if (profile == null) State(true, false, true)
            else State(true, true, runCatching {
                users.isQuietModeEnabled(profile) || !users.isUserUnlocked(profile)
            }.getOrDefault(true))
        }.getOrDefault(State(true, false, true))
    }

    /** 原版 openSettings 降级路径：系统安全设置（private space Activity 非 exported）。 */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
