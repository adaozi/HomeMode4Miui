/*
 * su 执行协议（唯一实现）—— 新建文件（2026-11-15 收敛）。
 *
 * 背景：RootKeepAlive / HostDataWipe / GraceRootActions 原先各写一份 ProcessBuilder("su")
 * 逻辑，超时（10s / 15s）、输出传递方式、finally 清理已经出现漂移 —— 改一处漏两处。
 *
 * 协议要点（三处原实现共同验证过的坑）：
 *   1. **调用方必须在后台线程**：waitFor 会阻塞当前线程。调用方可以是普通 Thread，
 *      也可以是 runBlocking(Dispatchers.IO)，但绝不能是主线程。
 *   2. **输出必须独立线程排水**：子进程写满管道缓冲（~64KB）后会阻塞，waitFor 永不返回。
 *   3. **超时强杀 + finally destroy**：Magisk/KSU 授权框挂起时清掉孤儿进程。
 *   4. 非零退出 / 超时 / 无 su（IOException/SecurityException）一律返回 null，
 *      由调用方做降级 —— 本方法不抛异常。
 */
package com.homemode4miui.bridge

import com.homemode4miui.util.ModLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SuShell {

    /** 默认超时（wipe 这类多轮 am force-stop + sleep 的脚本留 15s 余量）。 */
    const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * 执行一条 su 命令，返回 stdout+stderr（合并）；失败返回 null。
     * 阻塞当前线程，见文件头第 1 条。
     */
    fun run(command: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        try {
            val out = AtomicReference<String?>(null)
            val reader = Thread { out.set(process.inputStream.bufferedReader().readText()) }
            reader.isDaemon = true
            reader.start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                ModLog.i("SuShell: timeout after ${timeoutMs}ms (${command.take(48)})")
                return null
            }
            // 进程已退出 → 流已 EOF，等 reader 赋值完成（跨线程可见性靠 AtomicReference）
            reader.join(3_000)
            if (process.exitValue() == 0) out.get() else null
        } finally {
            runCatching { process.destroy() }
        }
    }.getOrNull()
}
