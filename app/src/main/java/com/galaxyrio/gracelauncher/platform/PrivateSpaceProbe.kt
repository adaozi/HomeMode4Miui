/*
 * P6（B6.5）Private Space 系统设置入口 —— 新建文件（降级声明）。
 *
 * 原版 PrivateSpaceController（platform/PrivateSpaceController.kt，310 行）的
 * 解锁/锁定/凭据会话管理依赖抽屉 PrivateApp/文件夹 UI 载体（folders 系统本模块
 * 尚未移植，原版 P3+ 面）—— 完整搬运推迟到 folders 阶段（PORTING_GraceLauncher.md
 * B6.5 恢复节点）。本文件现只保留系统设置入口：
 *   - openSettings：系统安全设置（private space Activity 非 exported，降级路径）
 * 审计修缮（2026-10-16）：删除 probe()/State —— 唯一调用点（GraceLauncherRoute
 * 死变量 privateSpaceState）已删，探测结果全仓零消费；恢复桌面载体时随实现重写。
 * 设置落 GraceHomeSettings.privateSpace（原版 JSON 列 privateSpaceJson 已就位）。
 */
package com.galaxyrio.gracelauncher.platform

import android.content.Context
import android.content.Intent
import android.provider.Settings

object PrivateSpaceProbe {

    /** 原版 openSettings 降级路径：系统安全设置（private space Activity 非 exported）。 */
    fun openSettings(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}
