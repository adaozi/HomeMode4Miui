/*
 * P6 桥命令接收器 —— 新建文件（非搬运）。notif4 起由 GraceBridgeEngine.start()
 * 动态注册（manifest 静态注册在 MIUI/HyperOS 上被省电策略拦截：后台模块进程
 * 收不到任何命令广播，装机实锤 receiver 打点零输出），运行在模块 App 进程。
 *
 * 宿主 com.miui.home 进程发来的命令广播（媒体控制/通知打开与清除/天气刷新）经此
 * 转发给 GraceBridgeEngine。安全（2026-11-15 重构）：广播派发路径拿不到真实发送方
 * uid（见 isAuthorized 注释），改为能力令牌鉴权 —— 宿主用 BridgeTokenClient 从
 * FontBridgeProvider.call 取令牌（binder 路径 uid 可靠），第三方应用取不到即被拒。
 */
package com.homemode4miui.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Process
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.homemode4miui.util.ModLog

class GraceCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GraceBridge.ACTION_COMMAND) return
        if (!isAuthorized(context, intent)) return
        // hotfix8 诊断：命令闭环打点（logcat tag HomeMode4Miui）
        com.homemode4miui.util.ModLog.i(
            "GraceCommandReceiver: cmd=${intent.getStringExtra(GraceBridge.EXTRA_CMD)}"
                + " arg1=${intent.getStringExtra(GraceBridge.EXTRA_ARG1)}"
                + " arg2=${intent.getStringExtra(GraceBridge.EXTRA_ARG2)}")
        when (intent.getStringExtra(GraceBridge.EXTRA_CMD)) {
            GraceBridge.Cmd.MEDIA_COMMAND -> {
                val sessionId = intent.getStringExtra(GraceBridge.EXTRA_ARG1) ?: return
                val command = intent.getStringExtra(GraceBridge.EXTRA_ARG2)
                    ?.let(GraceBridge::mediaCommand) ?: return
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.mediaCommand(sessionId, command)
            }
            GraceBridge.Cmd.MEDIA_DISMISS -> {
                val sessionId = intent.getStringExtra(GraceBridge.EXTRA_ARG1) ?: return
                val revision = intent.getStringExtra(GraceBridge.EXTRA_ARG2)?.toLongOrNull() ?: return
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.dismissMedia(sessionId, revision)
            }
            GraceBridge.Cmd.NOTIFICATION_OPEN -> {
                val key = intent.getStringExtra(GraceBridge.EXTRA_ARG1) ?: return
                val revision = intent.getStringExtra(GraceBridge.EXTRA_ARG2)?.toLongOrNull() ?: return
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.openNotification(context, key, revision)
            }
            GraceBridge.Cmd.NOTIFICATION_DISMISS -> {
                val key = intent.getStringExtra(GraceBridge.EXTRA_ARG1) ?: return
                val revision = intent.getStringExtra(GraceBridge.EXTRA_ARG2)?.toLongOrNull() ?: return
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.dismissNotification(key, revision)
            }
            // B6.2 天气：WEATHER_REFRESH 命令废止 —— 读取上移宿主进程
            // （GraceWeatherHostEngine），设置页刷新走 SETTINGS_COMMAND 通道。
            // notif1：宿主挂载拉取（清指纹 + 重发当前快照）
            GraceBridge.Cmd.SNAPSHOT_REFRESH -> {
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.refreshSnapshots()
            }
            // SYSTEM_ACTION 分支废止（hotfix）：全仓零发送方 —— Route 进程内直调
            // GraceSystemActions，跨进程委派路径删除（onReceive 裸奔分支的崩溃面随之消除）。
        }
    }

    /**
     * 命令发送方鉴权（2026-11-15 重构：由「未命中即放行」改为「必须有凭证」）。
     *
     * 原实现注释记录过实测结论：AMS 派发广播时 onReceive 的 Binder.getCallingUid()
     * 恒为 system_server，getSentFromUid()（API 34+）在 MIUI 转发下也可能落空 ——
     * 两个来源都不可靠，于是旧代码在未命中时打印诊断后**仍然放行**，等价于对任意
     * 应用开放命令面（可静默清除他人通知、可无限重绑 listener）。
     *
     * 现在以能力令牌为准（BridgeToken）：宿主经 FontBridgeProvider.call 取得，provider
     * 内 Binder.getCallingUid() 是内核填写的真实 uid，只放行 com.miui.home —— 第三方
     * 应用无法获得令牌。uid 命中保留为次优证据（令牌刷新失败时的兜底）。
     */
    private fun isAuthorized(context: Context, intent: Intent): Boolean {
        val token = intent.getStringExtra(GraceBridge.EXTRA_TOKEN)
        if (BridgeToken.sameSecret(BridgeToken.value, token)) return true
        // 兜底：令牌缺失/过期（宿主曾缓存旧令牌）时，若 uid 能解析为宿主则放行
        if (uidLooksLikeHost(context)) {
            ModLog.i("GraceCommandReceiver: uid fallback accepted (token mismatch, uid=host)")
            return true
        }
        ModLog.i("GraceCommandReceiver: dropped unauthorized command cmd="
                + intent.getStringExtra(GraceBridge.EXTRA_CMD)
                + " (no token, uid not host)")
        return false
    }

    /** uid → 包名命中宿主（仅作兜底证据，不可单独作为主判据）。 */
    private fun uidLooksLikeHost(context: Context): Boolean {
        val candidates = buildList {
            add(Binder.getCallingUid())
            if (Build.VERSION.SDK_INT >= 34) add(getSentFromUid())
        }.distinct()
        return candidates.any { uid ->
            uid != Process.myUid() && uid != 0 &&
                context.packageManager.getPackagesForUid(uid)
                    ?.contains(GraceBridge.PACKAGE_HOST) == true
        }
    }
}
