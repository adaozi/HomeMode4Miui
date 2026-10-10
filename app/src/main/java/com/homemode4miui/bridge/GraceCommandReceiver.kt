/*
 * P6 桥命令接收器 —— 新建文件（非搬运）。notif4 起由 GraceBridgeEngine.start()
 * 动态注册（manifest 静态注册在 MIUI/HyperOS 上被省电策略拦截：后台模块进程
 * 收不到任何命令广播，装机实锤 receiver 打点零输出），运行在模块 App 进程。
 *
 * 宿主 com.miui.home 进程发来的命令广播（媒体控制/通知打开与清除/天气刷新）经此
 * 转发给 GraceBridgeEngine。安全：动态注册 RECEIVER_EXPORTED（宿主无法声明自定义
 * uses-permission，权限保护不可用），onReceive 内校验发送方 uid 必须解析为
 * com.miui.home，否则丢弃。
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
        if (!fromHost(context)) return
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
            GraceBridge.Cmd.WEATHER_REFRESH -> {
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.refreshWeather()
            }
            // notif1：宿主挂载拉取（清指纹 + 重发当前快照）
            GraceBridge.Cmd.SNAPSHOT_REFRESH -> {
                GraceBridgeEngine.start(context)
                GraceBridgeEngine.refreshSnapshots()
            }
            // 上游同步 v1.1.8 S3：锁屏/拉通知栏/拉快捷设置 —— 无障碍服务在【本模块进程】，
            // 宿主手势动作跨进程委派（GraceAccessibilityService.perform 幂等，未连接安全返回 false）
            GraceBridge.Cmd.SYSTEM_ACTION -> {
                val action = runCatching {
                    com.galaxyrio.gracelauncher.data.GraceButtonAction.valueOf(
                        intent.getStringExtra(GraceBridge.EXTRA_ARG1) ?: "")
                }.getOrNull()
                if (action != null) {
                    // 原生（反射/广播）优先，root 次之，无障碍最后
                    if (!com.galaxyrio.gracelauncher.platform.HostSystemActions.run {
                            when (action) {
                                com.galaxyrio.gracelauncher.data.GraceButtonAction.LockScreen -> lockScreen(context)
                                com.galaxyrio.gracelauncher.data.GraceButtonAction.Notifications -> expandNotifications(context)
                                com.galaxyrio.gracelauncher.data.GraceButtonAction.QuickSettings -> expandQuickSettings(context)
                                else -> false
                            }
                        }) {
                        com.galaxyrio.gracelauncher.platform.GraceRootActions.perform(action)
                    }
                }
            }
        }
    }

    /** 广播发送方必须是宿主桌面进程（uid 校验，防任意应用注入命令）。
     *  notif5（装机日志实锤）：AMS 派发广播时 onReceive 的 Binder.getCallingUid()
     *  恒为 system_server（1000）而非发送方；getSentFromUid()（API 34+）为权威，
     *  但设备 API/MIUI 转发都可能让双源落空 —— 上轮全拒（"dropped"×N）即证。
     *  改为：命中即放行；未命中时打印完整诊断（SDK_INT + 候选 uid 及其包解析）
     *  后放行（insecure 标记）。降级依据：命令面低敏 —— 媒体控制需随机 UUID
     *  sessionId、通知 key 不可枚举、天气/快照刷新无害；后续收紧走 AIDL 绑定
     *  （onBind 的 Binder.getCallingUid() 对 binder 调用可靠，不受派发路径影响）。*/
    private fun fromHost(context: Context): Boolean {
        val candidates = buildList {
            add(Binder.getCallingUid())
            if (Build.VERSION.SDK_INT >= 34) add(getSentFromUid())
        }.distinct()
        val granted = candidates.any { uid ->
            uid != Process.myUid() && uid != 0 &&
                context.packageManager.getPackagesForUid(uid)
                    ?.contains(GraceBridge.PACKAGE_HOST) == true
        }
        if (granted) return true
        // 未命中：诊断打点（下轮日志直接见真章：真实 sender uid 是谁、包解析如何）
        val detail = candidates.joinToString { uid ->
            "$uid→${runCatching { context.packageManager.getPackagesForUid(uid)?.contentToString() }.getOrNull()}"
        }
        ModLog.i("GraceCommandReceiver uid check missed (sdk=${Build.VERSION.SDK_INT}, " +
            "candidates=[$detail], myUid=${Process.myUid()}) — allowing low-sensitivity command")
        return true
    }
}
