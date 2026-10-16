/*
 * appfont-bridge（v2.9.67）：导入字体跨进程只读 Provider —— 新建文件（非搬运）。
 *
 * 广播分片方案（v2.9.66）在 MIUI 实测被系统吞掉（76 发 4 收）—— HyperOS 对跨应用
 * 广播风暴有强抑制，分片推送不可行。Provider 是普通 binder 调用不受限：宿主进程
 * 解析字体缺文件时按需拉取（FontBridgePull），模块进程被 ContentProvider 自动拉起。
 *
 * 安全面：exported 只读 —— 仅 openFile（强制 mode=r）与 id 列表 query；id 必须过
 * ClockFontStore.validId 严格校验（UUID_名称.ttf/otf/ttc），无路径穿越面；泄露面 =
 * 用户导入的字体文件本身（非敏感数据）。
 */
package com.homemode4miui.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

class FontBridgeProvider : ContentProvider() {

    private fun fontDir(): File = File(requireNotNull(context).filesDir, "clock_fonts")

    private fun fontFile(id: String?): File {
        require(!id.isNullOrBlank()) { "missing font id" }
        require(com.galaxyrio.gracelauncher.data.ClockFontStore.isValidId(id)) { "invalid font id" }
        val file = File(fontDir(), id)
        if (!file.isFile) throw FileNotFoundException(id)
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "read-only provider" }
        return ParcelFileDescriptor.open(fontFile(uri.lastPathSegment), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "size"))
        fontDir().listFiles().orEmpty()
            .filter { it.isFile && com.galaxyrio.gracelauncher.data.ClockFontStore.isValidId(it.name) }
            .sortedBy { it.name.lowercase() }
            .forEach { cursor.addRow(arrayOf(it.name, it.length())) }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
    override fun onCreate(): Boolean = true
}
