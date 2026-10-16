/*
 * appfont-bridge（v2.9.67）：宿主侧字体按需拉取 —— 新建文件（非搬运）。
 *
 * SelectedTypefaceApi29 / ClockFontStore 双目录都缺文件时（宿主进程缺该导入字体），
 * 从模块 FontBridgeProvider 单次 binder 流式拉取，落宿主自己的 filesDir（UID/SELinux
 * 标签天然正确，零 root、零广播风暴）。主目录被 root 时代污染（root:root 700，装机
 * hostDir=[null] 实锤）时探测失败自动换 clock_fonts_h 备用目录 —— 读取端 ClockFontStore
 * 双目录兜底。每进程每 id 只尝试一次失败（负缓存），成功后文件常驻。
 */
package com.homemode4miui.bridge

import android.content.Context
import android.net.Uri
import com.homemode4miui.util.ModLog
import java.io.File

object FontBridgePull {

    private const val TAG = "FontBridgePull"
    private const val AUTHORITY = "com.homemode4miui.fontbridge"

    /** 每 id 只允许一次失败重试（进程级负缓存；成功 id 不入集，文件常驻无重拉）。 */
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 双目录都缺文件时的按需拉取（宿主进程持模块 Context 时 isHostProcess 判定不可用，
     *  不做进程守卫 —— 模块进程缺文件时 Provider 404 由负缓存兜住，无反复重试面）。
     *  用 acquireContentProviderClient 显式取异常（openInputStream 会把 SecurityException
     *  /FNFE 吞成 null，装机排障实锤）。 */
    fun pull(context: Context, id: String): File? {
        if (!failed.add(id)) return null
        return runCatching {
            val dir = usableFontDir(context) ?: return null
            val uri = Uri.parse("content://$AUTHORITY/font/$id")
            val client = context.contentResolver.acquireContentProviderClient(uri)
                ?: throw IllegalStateException("provider not reachable: $uri")
            try {
                val pfd = client.openFile(uri, "r")
                    ?: throw IllegalStateException("openFile returned null")
                val tmp = File(dir, "$id.part")
                val target = File(dir, id)
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                if (tmp.renameTo(target)) {
                    ModLog.i("$TAG: pulled $id (${target.length()}B)")
                    target
                } else {
                    tmp.delete()
                    throw IllegalStateException("rename failed")
                }
            } finally {
                client.close()
            }
        }.getOrElse {
            ModLog.e("$TAG: pull $id failed", it)
            null
        }
    }

    /** 落盘基准目录 = 【宿主自己的】filesDir（装机二次实锤：此前用 context.filesDir =
     *  模块 Context 的私有目录，宿主进程 mkdirs failed —— 跨 UID 不可写）。主子目录
     *  clock_fonts 被 root 时代污染（root:root 700）时探测失败 → 换 clock_fonts_h。 */
    internal fun usableFontDir(context: Context): File? {
        val base = runCatching {
            if (FontCrossProcess.isHostProcess(context)) context
            else context.createPackageContext(GraceBridge.PACKAGE_HOST,
                android.content.Context.CONTEXT_IGNORE_SECURITY)
        }.getOrNull() ?: context
        val primary = File(base.filesDir, "clock_fonts")
        val chosen = runCatching {
            check(primary.isDirectory || primary.mkdirs()) { "mkdirs failed" }
            val probe = File(primary, ".probe")
            check(probe.createNewFile()) { "probe refused" }
            probe.delete()
            primary
        }.getOrElse {
            ModLog.i("$TAG: primary font dir unusable, fallback clock_fonts_h (${it.message})")
            File(base.filesDir, "clock_fonts_h").apply { runCatching { mkdirs() } }
        }
        return if (chosen.isDirectory) chosen else null
    }
}
