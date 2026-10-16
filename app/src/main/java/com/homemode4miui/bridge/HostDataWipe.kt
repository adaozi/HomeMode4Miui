/*
 * hotfix（首页运维批）：第四桌面数据清除 —— 新建文件（非搬运）。
 *
 * 数据落点全部在【宿主进程 com.miui.home】私有目录（桥架构：设置页经桥落宿主
 * Room/SP），模块 App 进程无法直读直删 —— root 通道（RootKeepAlive/FontCrossProcess
 * 同款 su 协议）一次性完成。不动 launcher_sharedpreference.xml（ModePrefs：模式
 * 开关状态/熔断键保留，模式不关），不动宿主自有任何文件与 files/clock_fonts。
 *
 * wipe-v3 根因修复（v2 装机实测"数据还存在"）：
 *   v2 时序模型错在假设 force-stop 后桌面进程保持死亡。桌面是 HOME 应用，
 *   force-stop 后系统几百 ms 内自动拉起新进程，它会：
 *     1. 在 v2 首删（+0.5s）之前打开旧 DB / 加载旧 SP（旧数据进内存缓存）；
 *     2. rm 删掉文件后 [ -e ] 回读全不存在 → 误报 wiped；
 *     3. 但该进程 SharedPreferencesImpl 的内存缓存仍持有旧数据 —— 之后任意一次
 *        pref 写入（重排收藏/改设置必触发）把旧内容整份写回磁盘，文件"复活"；
 *     4. grace_launcher_settings.xml 复活后，下次启动
 *        GraceSettingsRepository.loadInitial() 发现 Room 无行 → 从复活 SP
 *        seed 回 Room → 设置/收藏/重命名全部回归。
 *   v3 时序改为【杀→删 ×3 轮 + 末次强杀收尾】：
 *     每轮 force-stop 先杀掉自动重启的进程（丢弃其 SP 内存缓存）再删文件，
 *     轮间兜住"上轮删完后又被复活"的文件；末次 force-stop 保证用户下次回到
 *     桌面时进程从干净磁盘冷启动。逐文件回读验证不变。
 *   目标清单补缺：grace_launcher.db-journal（非 WAL 模式日志）与
 *     files/item_icons（ItemIconStore 自定义图标位图，rm -rf 目录；
 *     DB/SP 只清元数据，位图不清则图标文件残留）。
 */
package com.homemode4miui.bridge

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

object HostDataWipe {

    private const val HOST = "com.miui.home"
    private const val DB_DIR = "/data/data/$HOST/databases/custom"
    private const val SP_DIR = "/data/data/$HOST/shared_prefs"
    private const val FILES_DIR = "/data/data/$HOST/files"

    /**
     * 删除目标（rm -rf：兼容文件与目录；-f：不存在不报错不中断）。
     * 刻意保留：files/clock_fonts（导入字体文件本体，协议约定只清引用）。
     */
    private val targets = listOf(
        "$DB_DIR/grace_launcher.db",
        "$DB_DIR/grace_launcher.db-wal",
        "$DB_DIR/grace_launcher.db-shm",
        "$DB_DIR/grace_launcher.db-journal",
        "$SP_DIR/grace_launcher_settings.xml",
        "$SP_DIR/grace_launcher_preferences.xml",
        "$SP_DIR/launcher_appearance.xml",
        "$FILES_DIR/item_icons",
    )

    /** 生成一段 shell：rm 全部目标 → 回读列出残留（left 由 shell 运行时求值，路径字面内联）。 */
    private fun rmAndCheck(): String {
        val rms = targets.joinToString(" ") { "rm -rf '$it'" }
        val checks = targets.joinToString("") {
            "if [ -e '$it' ]; then left=\"\$left $it\"; fi; "
        }
        return "$rms; left=''; $checks"
    }

    /**
     * 杀→删 ×3 轮 + 末次强杀（wipe-v3）：见文件头根因注释。
     * 每轮 force-stop（杀自动重启进程，丢弃其 SP 缓存）→ 0.4s（等杀透）→ rm → 回读。
     * 3 轮后 last-left 为空 = wiped；否则 WIPE_LEFT 清单。末次 force-stop 收尾，
     * 确保下次桌面冷启动读干净磁盘。全程 ~2s，在 su 15s 超时余量内。
     */
    private val CMD: String = buildString {
        repeat(3) { append("am force-stop $HOST; sleep 0.4; ${rmAndCheck()}") }
        append("if [ -z \"\$left\" ]; then echo wiped; else echo \"WIPE_LEFT:\$left\"; fi; ")
        append("am force-stop $HOST")
    }

    /** 后台执行；完成后主线程回调 ok（含验证回读；su 被拒 / 超时 = false）。 */
    fun wipe(onResult: (Boolean) -> Unit) {
        Thread {
            val out = runCatching { su(CMD) }
                .onFailure { com.homemode4miui.util.ModLog.e("HostDataWipe failed", it) }
                .getOrNull()
            val ok = out?.contains("wiped") == true
            // 原始输出打点：WIPE_LEFT 残留清单 / su 拒绝 / 超时，全部可从 logcat 判读
            com.homemode4miui.util.ModLog.i(
                "HostDataWipe: wipe -> $ok, su output: ${out?.lineSequence()?.lastOrNull()?.take(300) ?: "null (denied/timeout)"}")
            Handler(Looper.getMainLooper()).post { onResult(ok) }
        }.start()
    }

    /** Java 调用侧入口（MainActivity 匿名 Consumer，免 Kotlin Unit 返回值适配）。 */
    @JvmName("wipeJava")
    fun wipe(callback: java.util.function.Consumer<Boolean>) {
        wipe { callback.accept(it) }
    }

    /** RootKeepAlive/FontCrossProcess 同款 su 协议（独立实现，互不耦合）。 */
    private fun su(command: String): String? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val out = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val reader = Thread { out.set(process.inputStream.bufferedReader().readText()) }
        reader.isDaemon = true
        reader.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(5_000)
        if (process.exitValue() == 0) out.get() else null
    }.getOrNull()
}
