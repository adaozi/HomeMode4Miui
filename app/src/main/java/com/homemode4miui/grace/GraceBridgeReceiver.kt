/*
 * P6 桥快照接收器 —— 新建文件（非搬运）。运行在【宿主进程 com.miui.home】。
 *
 * 动态注册（非 manifest），随 GraceHomeView 挂载期存活。
 *
 * hotfix1（装机 2026-10-08 实锤）：原 NOT_EXPORTED 注册收不到模块进程的快照 ——
 * Android 13+ 的 NOT_EXPORTED 动态 receiver 只放行【同 UID 与系统】广播，跨包
 * 显式 setPackage 广播一律被系统丢弃（授权后媒体行不出现的根因）。改用
 * RECEIVER_EXPORTED；信任面评估：
 *   - 快照方向仅驱动显示层（媒体标题/通知预览/天气），伪造广播最多污染桌面显示，
 *     无崩溃面（全链 runCatching）、无数据泄露面、无命令面；
 *   - 命令方向（宿主→模块）保持 GraceCommandReceiver 的 uid 校验不变；
 *   - 收紧路径 = 桥升级 AIDL 绑定（Binder.getCallingUid 双向校验），待后续评估。
 */
package com.homemode4miui.grace

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.homemode4miui.bridge.GraceBridge
import com.homemode4miui.bridge.GraceSettingsBridge
import com.homemode4miui.util.ModLog

class GraceBridgeReceiver(private val viewModel: GraceHomeViewModel) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        // hotfix3 诊断：快照到达即打点（引擎侧指纹去重，频率低）——
        // modules 日志可直接确认桥通否；action=media 的 title 即媒体行渲染源
        val json = intent?.getStringExtra(GraceBridge.EXTRA_JSON)
        ModLog.i("bridge snapshot: action=${intent?.action} len=${json?.length ?: 0}")
        when (intent?.action) {
            GraceBridge.ACTION_MEDIA -> {
                val art = intent.getByteArrayExtra(GraceBridge.EXTRA_ART)
                val media = if (json == "null") null
                else runCatching { GraceBridge.decodeMedia(json, art) }.getOrNull()
                viewModel.onBridgeMedia(media, art != null)
            }
            GraceBridge.ACTION_NOTIFICATIONS -> {
                val notifications = runCatching {
                    GraceBridge.decodeNotifications(intent.getStringExtra(GraceBridge.EXTRA_JSON))
                }.getOrDefault(emptyMap())
                viewModel.onBridgeNotifications(notifications)
            }
            // B6.2 天气不再走广播：provider 白名单（com.miui.home=true /
            // com.homemode4miui=false）使读取上移宿主进程，GraceWeatherHostEngine
            // 进程内直推 viewModel.onWeatherState；设置页经快照 weatherJson 回带。
            // C 组（C3）：设置命令（模块设置页 → 本进程落库）。命令面导出后的信任面
            // 评估见 GraceSettingsBridge 头注（伪造 = 等同用户操作设置数据，无宿主安全边界）。
            GraceSettingsBridge.ACTION_SETTINGS_COMMAND -> viewModel.onSettingsCommand(
                intent.getStringExtra(GraceBridge.EXTRA_CMD),
                intent.getStringExtra(GraceBridge.EXTRA_ARG1),
                intent.getStringExtra(GraceBridge.EXTRA_ARG2),
                intent.getStringExtra(GraceBridge.EXTRA_JSON),
            )
            // 未知 action 忽略：exported 后任意 app 可发广播到本 receiver，
            // 只处理认识的 action（伪造面 = 显示/设置数据污染，见头注信任面评估）
        }
    }

    companion object {
        fun intentFilter() = android.content.IntentFilter().apply {
            addAction(GraceBridge.ACTION_MEDIA)
            addAction(GraceBridge.ACTION_NOTIFICATIONS)
            addAction(GraceSettingsBridge.ACTION_SETTINGS_COMMAND)
        }
    }
}
