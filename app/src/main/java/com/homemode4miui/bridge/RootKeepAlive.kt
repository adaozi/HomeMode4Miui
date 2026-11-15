/*
 * notif7.5：root 保活 —— 新建文件（非搬运）。运行在【模块 App 进程】。
 * 背景（notif7 定案）：MIUI 省电策略在模块退后台后解绑 NotificationListenerService
 * 且系统不自动重绑；notif7 自愈链（看门狗 + rebind + component toggle）是解绑后的
 * 兜底，本文件把防线前移 —— 尽量让 MIUI 根本不解绑。
 * 设计约束：
 *   - LSPosed 环境设备必然已 root，su 通道现成；但 su 必须不阻塞主线程
 *     （Magisk 授权框挂起时 waitFor 会无限等 —— 全程后台线程 + 超时强杀）。
 *   - Magisk 授权框就是开关：授予 = 保活生效；永久拒绝 = su 调用失败，
 *     runCatching 吞掉记 unavailable，进程生命周期内不再重试 —— 不做自定义
 *     设置页开关（设置页是整屏搬运的 LauncherSettingsScreen，注入自定义行
 *     造成移植漂移）。
 *   - 三项手段全部 best-effort、逐项隔离失败、逐项打点：任一失败不影响
 *     其余，全失败时 notif7 自愈链照常兜底 —— 保活是增强不是依赖。
 * 手段清单（按可靠性排序；v2.9.144 移除 service.d 开机脚本——死循环卡 KSU
 *   启动 + 卸载残留，见 removeLegacyBootScript 注释）：
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

object RootKeepAlive {

    private const val PKG = "com.homemode4miui"

    /** 进程内只尝试一轮：su 被拒 / 无 root 时不再反复弹框打扰。 */
    @Volatile private var attempted = false

    /** 已成功应用过（进程内幂等）：重复 ensure 直接回调成功，不重跑三项。 */
    @Volatile private var applied = false

    /** hotfix：ensure/apply 双入口收敛到同一把锁 + 同一协议 —— 旧实现两者互不感知
     *  （apply 后台执行三项时用户点按钮触发 ensure → 并发重复 su 写 PowerKeeper/装脚本）。 */
    private val gate = Any()

    /** gate1：带结果回调的探测 + 应用入口（模块首页按钮调用）。 */
    fun ensure(onResult: (Boolean) -> Unit) {
        Thread {
            val ok = tryRun()
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
        Thread { tryRun() }.start()
    }

    /**
     * 单飞：整个流程持锁执行（全 su 命令各带 10s 超时，最坏有界）。
     * 锁语义保证正确性：ensure 获取锁时若 apply 正在执行会等它完成后复判 ——
     * applied=true 返回成功；apply 已完整跑过且失败（attempted=true）返回 false，
     * 不重试不重入（「进程内只尝试一轮」文档语义）。
     */
    private fun tryRun(): Boolean {
        synchronized(gate) {
            if (applied) return true
            if (attempted) return false
            attempted = true
            return runCatching {
                // su 可用性探测（无 root / 未授权 = IOException 或非零退出）
                if (su("echo ok") == null) {
                    ModLog.i("RootKeepAlive: su unavailable (no root or denied) — notif7 self-heal chain remains")
                    return false
                }
                ModLog.i("RootKeepAlive: su granted, applying keep-alive")
                // 清理优先：历史上写入的开机脚本会卡死启动流程，必须最先摘掉，
                // 不能因为后面某项 su 失败而被跳过（2026-11-12 整机反复重启实锤）。
                removeLegacyBootScript()
                applyDozeWhitelist()
                applyPowerKeeperPolicy()
                applyAutostart()
                applied = true
                true
            }.onFailure { ModLog.e("RootKeepAlive: apply failed", it) }.getOrDefault(false)
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
            // hotfix：退出码 0 ≠ 生效（0 行 UPDATE 同样成功）；同连接追加 SELECT changes()
            // 校验实改行数（sqlite3 每次 invocations 是新连接，必须与 UPDATE 同进程内执行）
            val out = su("sqlite3 $db \"$sql; SELECT changes();\"")
            val changed = out?.trim()?.lineSequence()?.lastOrNull()?.toIntOrNull()
            if (out != null && changed != null && changed > 0) {
                ModLog.i("RootKeepAlive: powerkeeper policy applied (${sql.take(40)}... rows=$changed)")
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
     * 4.（已移除，v2.9.144）Magisk/KSU service.d 开机保活脚本（keep1）—— 运行时以 su
     *    写入 /data/adb/service.d/ 的方案废弃：
     *    ① while true 死循环在 KSU service 阶段被串行执行等待完成，永久卡住启动流程
     *      → 开机失败 → KSU 自动进安全模式（2026-11-12 用户设备实锤）；
     *    ② 运行时写入的脚本不属于模块刷入包，卸载模块后残留，持续影响用户环境。
     *    保活完全依赖主链路 pokeFromHost（用户回桌面即拉起，零延迟）+ Doze 白名单 +
     *    PowerKeeper 策略；旧设备残留由 removeLegacyBootScript() 自动清理。
     */
    /** 清理历史版本写入的 service.d 保活脚本残留（幂等，每次 apply 最先执行）。 */
    private fun removeLegacyBootScript() {
        val cmd = listOf(
            "/data/adb/service.d/hm4m_keepalive.sh",
            "/data/adb/post-fs-data.d/hm4m_keepalive.sh",
        ).joinToString("; ") { p -> "if [ -f $p ]; then rm -f $p; echo removed:$p; fi" }
        val out = su(cmd)
        if (out == null) {
            ModLog.i("RootKeepAlive: legacy boot script check failed (su)")
        } else if (out.contains("removed:")) {
            // 命中残留 = 用户设备曾因此反复重启，必须留痕（后续排障的唯一线索）
            ModLog.i("RootKeepAlive: legacy boot script REMOVED -> "
                    + out.lineSequence().filter { it.contains("removed:") }.joinToString(", "))
        } else {
            ModLog.i("RootKeepAlive: no legacy boot script (clean)")
        }
    }

    /**
     * 本模块保活命令统一 10s 超时（su 授权框挂起时尽快放弃，不无限等）。
     * 实现见 SuShell —— 2026-11-15 起三处重复的 ProcessBuilder 逻辑收敛到一处。
     */
    private fun su(command: String): String? = SuShell.run(command, 10_000L)
}
