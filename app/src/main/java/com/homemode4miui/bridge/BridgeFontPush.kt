/*
 * appfont-bridge（v2.9.67）：字体删除同步 —— 单条桥命令广播（无风暴面）。
 * 推送方向已由 FontBridgeProvider 按需拉取替代（分片广播在 MIUI 被系统抑制，弃用）。
 */
package com.homemode4miui.bridge

import android.content.Context

object BridgeFontPush {

    /** 非宿主进程删除时同步清宿主侧副本（fire-and-forget，失败无害）。 */
    fun delete(context: Context, id: String) {
        if (FontCrossProcess.isHostProcess(context)) return
        runCatching {
            context.sendBroadcast(
                GraceSettingsBridge.command(GraceSettingsBridge.Cmd.FONT_DELETE, arg1 = id))
        }
    }
}
