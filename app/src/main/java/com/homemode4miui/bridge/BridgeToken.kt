/*
 * 桥命令能力令牌 —— 新建文件（2026-11-15 安全收敛）。
 *
 * 根因：广播派发时 Binder.getCallingUid() 恒为 system_server（MIUI 转发下
 * getSentFromUid() 也可能落空，GraceCommandReceiver 上有装机实锤注释），
 * 接收端无法可靠识别发送方。此前三处「未命中即放行」使任意应用可：
 *   ① 伪造 ACTION_SET_ROOT_READY / SET_AUTOSTART_READY → 绕过 PermissionGate
 *      三道门禁（root/自启动/通知）直接开启第四桌面；
 *   ② 伪造 ACTION_GRACE_SETTINGS_COMMAND + cmd=settings_wipe_data → 清空用户
 *      桌面全部数据并杀死桌面进程；
 *   ③ 伪造媒体/通知命令 → 静默清除他人通知。
 *
 * 方案：模块进程启动时生成随机令牌，唯一出口是 ContentProvider.call() —— provider
 * 内的 Binder.getCallingUid() 由内核填写，不可伪造，只放行 com.miui.home 与本包。
 * 第三方应用拿不到令牌，接收端比对失败即丢弃。
 *
 * 进程重启 = 令牌轮换：接收端比对失败时先向 provider 刷新一次再判（见
 * BridgeTokenClient.verify），不需要额外的版本握手。
 */
package com.homemode4miui.bridge

import java.security.MessageDigest
import java.util.UUID

object BridgeToken {

    /** 本进程（模块 App 进程）的当前令牌；宿主进程加载本类时该值无意义，勿用于校验。 */
    @Volatile
    var value: String = UUID.randomUUID().toString()
        private set

    /** Java 调用侧入口（MainActivity 广播发送）。 */
    @JvmStatic
    fun current(): String = value

    /**
     * 常数时间比较（防逐字节时序侧信道）。
     * 纯函数，不依赖 value —— 宿主进程用它比对 provider 下发的令牌。
     */
    @JvmStatic
    fun sameSecret(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
