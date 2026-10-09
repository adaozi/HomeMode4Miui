/*
 * notif7.5：root 保活 —— 新建文件（非搬运）。运行在【模块 App 进程】。
 *
 * 背景（notif7 定案）：MIUI 省电策略在模块退后台后解绑 NotificationListenerService
 * 且系统不自动重绑；notif7 自愈链（看门狗 + rebind + component toggle）是解绑后的
 * 兜底，本文件把防线前移 —— 尽量让 MIUI 根本不解绑。
 *
 * 设计约束：
 *   - LSPosed 环境设备必然已 root，su 通道现成；但 su 必须不阻塞主线程
 *     （Magisk 授权框挂起时 waitFor 会无限等 —— 全程后台线程 + 超时强杀）。
 *   - Magisk 授权框就是开关：授予 = 保活生效；永久拒绝 = su 调用失败，
 *     runCatching 吞掉记 unavailable，进程生命周期内不再重试 —— 不做自定义
 *     设置页开关（设置页是整屏搬运的 LauncherSettingsScreen，注入自定义行
 *     造成移植漂移）。
 *   - 三项手段全部 best-effort、逐项隔离失败、逐项打点：任一失败不影响
 *     其余，全失败时 notif7 自愈链照常兜底 —— 保活是增强不是依赖。
 *
 * 手段清单（按可靠性排序）：
 *   1. Doze 白名单 `dumpsys deviceidle whitelist +pkg` —— 标准 Android，可靠。
 *   2. MIUI PowerKeeper 数据库写 per-app 省电策略（本次解绑 listener 的元凶）
 *      —— 表结构随 HyperOS 版本漂移，多变体尝试，设备缺 sqlite3 或表结构
 *      不符时静默跳过。
 *   3. MIUI 自启动（SecurityCenter provider）—— 老版 MIUI 接口，新版大概率
 *      不存在，纯尝试性；失败无副作用。
 */
package com.homemode4miui.bridge

import android.content.Context
import com.homemode4miui.util.ModLog
import java.util.concurrent.TimeUnit

object RootKeepAlive {

    private const val PKG = "com.homemode4miui"

    /** 进程内只尝试一轮：su 被拒 / 无 root 时不再反复弹框打扰。 */
    @Volatile private var attempted = false

    /** 已成功应用过（进程内幂等）：重复 ensure 直接回调成功，不重跑三项。 */
    @Volatile private var applied = false

    /** gate1：带结果回调的探测 + 应用入口（模块首页按钮调用）。 */
    fun ensure(onResult: (Boolean) -> Unit) {
        Thread {
            val ok = runCatching {
                if (applied) return@runCatching true
                if (su("echo ok") == null) {
                    ModLog.i("RootKeepAlive: probe failed (no root or denied)")
                    return@runCatching false
                }
                ModLog.i("RootKeepAlive: su granted, applying keep-alive")
                applyDozeWhitelist()
                applyPowerKeeperPolicy()
                applyAutostart()
                installBootScript()
                applied = true
                true
            }.getOrDefault(false)
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(ok) }
        }.start()
    }

    /** Java 调用侧入口（MainActivity 匿名 Consumer，免 Kotlin Unit 依赖）。 */
    @JvmName("ensureJava")
    @Suppress("unused")
    fun ensure(callback: java.util.function.Consumer<Boolean>) {
        ensure { callback.accept(it) }
    }

    /** 由引擎启动触发（listener 连接 / 命令首次到达）。后台线程执行，永不阻塞主线程。 */
    fun apply(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (attempted) return
        synchronized(this) {
            if (attempted) return
            attempted = true
            Thread {
                runCatching {
                    // su 可用性探测（无 root / 未授权 = IOException 或非零退出）
                    if (su("echo ok") == null) {
                        ModLog.i("RootKeepAlive: su unavailable (no root or denied) — notif7 self-heal chain remains")
                        return@Thread
                    }
                    ModLog.i("RootKeepAlive: su granted, applying keep-alive")
                    applyDozeWhitelist()
                    applyPowerKeeperPolicy()
                    applyAutostart()
                    installBootScript()
                    applied = true
                }.onFailure { ModLog.e("RootKeepAlive: apply failed", it) }
            }.start()
        }
    }

    /** 1. Doze 白名单：标准 Android 命令，任何 ROM 通用。 */
    private fun applyDozeWhitelist() {
        val out = su("dumpsys deviceidle whitelist +$PKG")
        ModLog.i("RootKeepAlive: doze whitelist -> ${out?.lineSequence()?.firstOrNull()?.take(120) ?: "failed"}")
    }

    /**
     * 2. MIUI PowerKeeper per-app 省电策略（社区已知表结构，多 HyperOS 版本漂移
     * 容忍）：battery_save_policy 0=无限制 / 1=智能限制 / 2=严格限制（部分版本
     * 列名/取值不同 —— 逐变体尝试，成功一条即收）。DB 属 powerkeeper 进程，
     * WAL 模式下外部写需 -wal 一致性 —— sqlite3 不可用或写失败一律静默跳过。
     */
    private fun applyPowerKeeperPolicy() {
        val db = "/data/data/com.miui.powerkeeper/databases/powerkeeper.db"
        val variants = listOf(
            "UPDATE app_info SET battery_save_policy=0 WHERE packageName='$PKG'",
            "UPDATE app_info SET backgroundPolicy=0 WHERE packageName='$PKG'",
        )
        variants.forEach { sql ->
            val out = su("sqlite3 $db \"$sql\"")
            if (out != null) {
                ModLog.i("RootKeepAlive: powerkeeper policy applied (${sql.take(40)}...)")
                return
            }
        }
        ModLog.i("RootKeepAlive: powerkeeper policy skipped (sqlite3 unavailable or schema drift)")
    }

    /** 3. MIUI 自启动（老版 SecurityCenter provider；新版大概率无此 uri，纯尝试）。 */
    private fun applyAutostart() {
        val out = su("content insert --uri content://com.miui.securitycenter.autostart/autostart --bind pkg:s:$PKG")
        ModLog.i("RootKeepAlive: autostart provider -> ${if (out != null) "attempted" else "failed/skipped"}")
    }

    /**
     * 4. Magisk service.d 开机守护脚本（keep1）：开机后常驻循环，每 30s 检查
     * 模块进程，被杀则 root 身份 am startservice 拉起（root 调用方不受后台
     * startService 限制 —— 装机实锤的「模块死在后台无人拉」的 root 侧根治）。
     * 脚本内容经 base64 传输落盘（规避 su -c 的引号嵌套地狱）；重复安装覆盖写
     * 幂等；pkill 旧守护后 nohup 立即启动，不要求重启即生效。
     */
    private fun installBootScript() {
        val script = """
            #!/system/bin/sh
            # HomeMode4Miui process keepalive (keep1, managed by module)
            while true; do
              if ! pidof com.homemode4miui >/dev/null 2>&1; then
                am startservice -n com.homemode4miui/com.homemode4miui.bridge.GraceKeepAliveService >/dev/null 2>&1
              fi
              sleep 30
            done
        """.trimIndent()
        val b64 = android.util.Base64.encodeToString(script.toByteArray(), android.util.Base64.NO_WRAP)
        val path = "/data/adb/service.d/hm4m_keepalive.sh"
        val out = su(
            "mkdir -p /data/adb/service.d && echo $b64 | base64 -d > $path && chmod 700 $path" +
                " && pkill -f hm4m_keepalive.sh; nohup sh $path >/dev/null 2>&1 & echo installed"
        )
        ModLog.i("RootKeepAlive: boot guard script -> ${if (out != null) "installed ($path)" else "failed/skipped"}")
    }

    /**
     * 单条 su 命令：后台线程调用；超时强杀；失败返回 null（含授权框拒绝）。
     * 读流必须放独立线程 —— 若当前线程 readText() 阻塞在授权框挂起的进程上，
     * waitFor 的超时永远不会被执行。
     */
    private fun su(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        var out: String? = null
        val reader = Thread { out = process.inputStream.bufferedReader().readText() }
        reader.start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ModLog.i("RootKeepAlive: su timeout (${command.take(40)})")
            return null
        }
        reader.join(2_000)
        if (process.exitValue() == 0) out else null
    }.getOrNull()
}
