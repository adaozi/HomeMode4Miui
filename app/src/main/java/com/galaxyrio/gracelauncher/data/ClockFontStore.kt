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
    private val context = context.applicationContext
    private val directory get() = File(context.filesDir, "clock_fonts")

    suspend fun list(): List<ClockFontFile> = withContext(Dispatchers.IO) {
        directory.listFiles().orEmpty().filter { it.isFile && validId(it.name) }
            .map { ClockFontFile(it.name, it.name.substringAfter('_').substringBeforeLast('.')) }
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
            return ClockFontFile(target.name, label)
        } catch (error: Exception) {
            target.delete() // Only the incomplete copy created by this import.
            throw error
        }
    }

    suspend fun typeface(id: String, weight: Int): Typeface? = withContext(Dispatchers.IO) {
        if (!validId(id)) return@withContext null
        val file = File(directory, id)
        if (!file.isFile) return@withContext null
        val key = "${file.absolutePath}:$weight"
        synchronized(cache) { cache.get(key)?.typeface } ?: runCatching {
            Typeface.Builder(file).setFontVariationSettings("'wght' $weight")
                .setWeight(weight).setItalic(false).setFallback("sans-serif").build()
                ?.let { Typeface.create(it, weight, false) }
        }.getOrNull()?.also { synchronized(cache) { cache.put(key, CachedTypeface(it, file.length().toInt().coerceAtLeast(1))) } }
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
    }
}
