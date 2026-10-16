/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/ClockFontStore.kt
 * P1 移植：逐字拷贝，未改动。落库位置 = com.miui.home 进程 filesDir/clock_fonts，
 *   与宿主数据自然隔离；导入入口（设置界面）属 C 组模块 App 进程，后续接入。
 */
package com.galaxyrio.gracelauncher.data

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ClockFontFile(val id: String, val name: String)

/** Shared by clock and app typography. Retain the original directory and ids for saved clocks. */
class ClockFontStore(context: Context) {
    // hotfix（appFont 跨进程）：createPackageContext 来的远端包 Context 其
    // getApplicationContext() 在目标包未于本进程实例化 Application 时返回 null
    // （装机实锤 2026-10-15：宿主进程持模块包 Context → getFilesDir() NPE）。
    // 回退原 context：filesDir 语义仍指向"导入方进程私有目录"。
    private val context = runCatching { context.applicationContext }.getOrNull() ?: context
    private val directory get() = File(context.filesDir, "clock_fonts")
    /** appfont-bridge（v2.9.67）：宿主主目录被 root 时代污染时（root:root 700 / 标签错，
     *  桥推送探测失败自动换此备用目录），读取端双目录兜底。 */
    private val altDirectory get() = File(context.filesDir, "clock_fonts_h")
    private fun resolveFile(id: String): File? {
        candidates().map { File(it, id) }.firstOrNull { it.isFile }?.let { return it }
        // appfont-bridge（v2.9.67）：宿主进程缺文件 → 从模块 FontBridgeProvider 按需拉取
        // （零 root，普通 binder 不受 MIUI 广播抑制），落宿主自己目录常驻。
        return com.homemode4miui.bridge.FontBridgePull.pull(context, id)
    }
    private fun candidates(): List<File> {
        val dirs = mutableListOf(directory, altDirectory)
        // appfont-bridge（v2.9.67）：补宿主自己的私有目录 —— 拉取落点（FontBridgePull 用
        // createPackageContext 反取宿主 filesDir；主目录被 root 污染时落 clock_fonts_h）。
        // 宿主进程内构造的 ClockFontStore 可能持模块 Context（app 字体链），其 filesDir
        // 指向模块私有目录（宿主不可读写），必须补宿主视角目录才能读到拉取结果。
        runCatching {
            val hostCtx = if (com.homemode4miui.bridge.FontCrossProcess.isHostProcess(context)) context
                else context.createPackageContext("com.miui.home", android.content.Context.CONTEXT_IGNORE_SECURITY)
            dirs += File(hostCtx.filesDir, "clock_fonts")
            dirs += File(hostCtx.filesDir, "clock_fonts_h")
        }
        return dirs.distinct()
    }

    suspend fun list(): List<ClockFontFile> = withContext(Dispatchers.IO) {
        val files = candidates().flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && validId(it.name) }
        }.distinctBy { it.name }
        files.map { ClockFontFile(it.name, it.name.substringAfter('_').substringBeforeLast('.')) }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun import(uri: Uri, requireClockDigits: Boolean = true): ClockFontFile = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Font.ttf"
        context.contentResolver.openInputStream(uri)?.use { importStream(it, name, requireClockDigits) }
            ?: throw IllegalArgumentException("Unable to read font")
    }

    /** Bounded private copy survives moved/deleted documents and revoked URI grants. */
    internal fun importStream(input: InputStream, displayName: String, requireClockDigits: Boolean = true): ClockFontFile {
        check(directory.isDirectory || directory.mkdirs())
        val extension = displayName.substringAfterLast('.', "").lowercase()
        require(extension in setOf("ttf", "otf", "ttc")) { "Select a TTF, OTF or TTC font" }
        val label = displayName.substringBeforeLast('.').map { if (it.isLetterOrDigit() || it in " _-") it else '_' }
            .joinToString("").trim().take(80).ifBlank { "Font" }
        val target = File(directory, "${UUID.randomUUID()}_$label.$extension")
        try {
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Font exceeds 20 MB" }
                    output.write(buffer, 0, count)
                }
            }
            // Reject corrupt/unrelated files before accepting Android's fallback.
            val signature = target.inputStream().use { stream -> ByteArray(4).also { require(stream.read(it) == 4) } }
            require(signature.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                String(signature, Charsets.US_ASCII) in setOf("OTTO", "ttcf", "true")) { "Invalid font" }
            val face = requireNotNull(Typeface.Builder(target).build())
            if (requireClockDigits) {
                val paint = Paint().apply { typeface = face }
                require("0123456789".all { paint.hasGlyph(it.toString()) }) { "Font does not include clock digits" }
            }
            return ClockFontFile(target.name, label).also {
                // appfont-bridge（v2.9.67）：宿主侧不再需要主动推送 —— 宿主解析缺文件时
                // 从 FontBridgeProvider 按需拉取（FontBridgePull），零 root 零广播风暴。
            }
        } catch (error: Exception) {
            target.delete() // Only the incomplete copy created by this import.
            throw error
        }
    }

    suspend fun typeface(id: String, weight: Int): Typeface? = withContext(Dispatchers.IO) {
        if (!validId(id)) return@withContext null
        val file = resolveFile(id) ?: return@withContext null
        val key = "${file.absolutePath}:$weight"
        synchronized(cache) { cache.get(key)?.typeface } ?: runCatching {
            Typeface.Builder(file).setFontVariationSettings("'wght' $weight")
                .setWeight(weight).setItalic(false).setFallback("sans-serif").build()
                ?.let { Typeface.create(it, weight, false) }
        }.getOrNull()?.also { synchronized(cache) { cache.put(key, CachedTypeface(it, file.length().toInt().coerceAtLeast(1))) } }
    }

    /** 上游同步 v1.1.8 S5：仅私有导入字体可解析为文件（bundled preset 永不落此目录）。
     *  appfont-bridge（v2.9.66）：双目录解析（主目录被 root 时代污染时宿主落备用目录）。 */
    fun file(id: String): File? = if (validId(id)) resolveFile(id) else null

    /** 上游同步 v1.1.8 S5：删除导入字体并清空 typeface 缓存（FontSettings 管理页消费）。
     *  appfont-bridge（v2.9.66）：双目录删除 + 桥命令同步清宿主侧副本（root 镜像弃用）。 */
    suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        val hostProcess = com.homemode4miui.bridge.FontCrossProcess.isHostProcess(context)
        ids.forEach { id ->
            candidates().forEach { dir -> File(dir, id).takeIf { it.isFile }?.delete() }
            // 非宿主进程删除 → 单条桥命令同步删宿主侧副本（单广播，无风暴面）
            if (!hostProcess) runCatching {
                context.sendBroadcast(
                    com.homemode4miui.bridge.GraceSettingsBridge.command(
                        com.homemode4miui.bridge.GraceSettingsBridge.Cmd.FONT_DELETE, arg1 = id))
            }
        }
        synchronized(cache) { cache.evictAll() }
    }

    companion object {
        fun displayName(id: String): String? = id.takeIf(::validId)?.substringAfter('_')?.substringBeforeLast('.')

        private const val MAX_BYTES = 20 * 1024 * 1024
        private data class CachedTypeface(val typeface: Typeface, val bytes: Int)
        private val cache = object : LruCache<String, CachedTypeface>(32 * 1024 * 1024) {
            override fun sizeOf(key: String, value: CachedTypeface) = value.bytes
        }
        private fun validId(id: String) = id.length <= 125 &&
            id.matches(Regex("[0-9a-f-]{36}_[\\p{L}\\p{N} _-]+\\.(ttf|otf|ttc)"))

        /** appfont-bridge（v2.9.67）：FontBridgeProvider 的 id 校验入口（防路径穿越）。 */
        fun isValidId(id: String) = validId(id)
    }
}
