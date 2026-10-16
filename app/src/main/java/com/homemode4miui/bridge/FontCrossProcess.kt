/*
 * appfont-bridge（v2.9.67）：跨进程字体文件定位工具。
 *
 * 历史包袱清理：root 镜像通道（mirrorInstall/mirrorRemove/su）已整体移除 ——
 * KernelSU + MIUI 装机上 root 写入的目录宿主进程 list()=null（DAC/SELinux MCS/
 * 命名空间三重怪癖，2026-10-16 实锤），且 root 路线与"桌面零 root 依赖"方向冲突。
 * 现行方案 = FontBridgeProvider 按需拉取（FontBridgePull，普通 binder 不受 MIUI
 * 广播抑制），宿主文件落宿主自己 filesDir（UID/标签天然正确）。
 */
package com.homemode4miui.bridge

import android.content.Context
import java.io.File

object FontCrossProcess {

    /** 宿主镜像目录（只读枚举打点消费；现行写入方 = 宿主自己 FontBridgePull）。 */
    const val HOST_DIR_PUBLIC = "/data/data/com.miui.home/files/clock_fonts"

    /** 本进程是否宿主（context.packageName 判定；宿主进程持模块 Context 时恒 false，
     *  调用方不得据此决定写路径 —— 见 FontBridgePull.usableFontDir 的反取逻辑）。 */
    fun isHostProcess(context: Context): Boolean = runCatching {
        (context.applicationContext ?: context).packageName == GraceBridge.PACKAGE_HOST
    }.getOrDefault(false)

    /** 宿主目录中的字体文件（仅校验文件名，防路径注入）。 */
    fun mirroredFile(id: String): File? =
        ClockFontId.isValid(id).takeIf { it }?.let { File(HOST_DIR_PUBLIC, id) }?.takeIf { it.isFile }
}

/** 字体 id 校验（ClockFontStore.validId 同规则；此处独立承载避免 internal 暴露链）。 */
private object ClockFontId {
    fun isValid(id: String) = id.length <= 125 &&
        id.matches(Regex("[0-9a-f-]{36}_[\\p{L}\\p{N} _-]+\\.(ttf|otf|ttc)"))
}
