/*
 * 上游同步 v1.1.8 S3 root 增强（HomeMode4Miui 专属，非上游代码）——
 * 目标设备要求 root（Magisk），无障碍三动作改为 root 优先：
 *   LockScreen      -> su -c "input keyevent 26"（熄屏即锁，MIUI 语义）
 *   Notifications   -> su -c "cmd statusbar expand-notifications"（shell 命令，Android 9+）
 *   QuickSettings   -> su -c "cmd statusbar expand-settings"
 * root 失败（无 su / 授权被拒 / 命令非零退出）时 worker 线程回退 GraceAccessibilityService
 * （performGlobalAction 经主线程 Handler 投递，规避线程面）。
 * 执行全程 fire-and-forget：su 授权弹窗/耗时绝不上主线程（桌面零崩溃 + 零卡顿铁律）。
 */
package com.galaxyrio.gracelauncher.platform

import android.os.Handler
import android.os.Looper
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.homemode4miui.util.ModLog

internal object GraceRootActions {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val commandFor = mapOf(
        GraceButtonAction.LockScreen to "input keyevent 26",
        GraceButtonAction.Notifications to "cmd statusbar expand-notifications",
        GraceButtonAction.QuickSettings to "cmd statusbar expand-settings",
    )

    /** root 是否可能可用（探测结果仅缓存布尔，探测本身在 worker）。 */
    @Volatile var lastRootResult: Boolean? = null
        private set

    /**
     * fire-and-forget：恒返回 true（调用方无需等待，结果仅日志 + a11y 兜底）。
     * 命令失败（su 不存在 / 授权拒 / 非零退出）→ 主线程回退无障碍服务。
     */
    fun perform(action: GraceButtonAction): Boolean {
        val cmd = commandFor[action] ?: return false
        Thread {
            val ok = try {
                // hotfix：读流与等待并发（读流放独立线程排水）—— 旧实现 waitFor 在关流
                // 之前，输出超管道缓冲区（~64KB）会令子进程写阻塞、waitFor 永不返回。
                // waitFor 带超时强杀 + finally destroy 兜底，杜绝孤儿进程。
                val process = ProcessBuilder("su", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()
                try {
                    val reader = Thread { process.inputStream.bufferedReader().readText() }
                    reader.isDaemon = true
                    reader.start()
                    val exit = if (process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                        // 进程已退出 → 流已 EOF，reader 收尾即完成
                        reader.join(2_000)
                        runCatching { process.exitValue() }.getOrDefault(-1)
                    } else {
                        process.destroyForcibly()
                        ModLog.i("GraceRootActions root timeout cmd=$cmd")
                        Int.MIN_VALUE
                    }
                    lastRootResult = exit == 0
                    if (exit != 0) ModLog.i("GraceRootActions root exit=$exit cmd=$cmd")
                    exit == 0
                } finally {
                    runCatching { process.destroy() }
                }
            } catch (t: Throwable) {
                // IOException = 无 su（未 root 或 Magisk 未装）；SecurityException = 授权策略拒绝
                lastRootResult = false
                ModLog.i("GraceRootActions root unavailable, fallback to a11y: ${t.javaClass.simpleName}")
                false
            }
            if (!ok) mainHandler.post {
                // 兜底：无障碍服务（若已授权且连接）。performGlobalAction 走主线程投递。
                if (!GraceAccessibilityService.perform(action)) {
                    ModLog.i("GraceRootActions a11y fallback also unavailable for ${action.name}")
                }
            }
        }.apply {
            name = "grace-root-action"
            isDaemon = true
        }.start()
        return true
    }
}
