/*
 * appfont-bridge（v2.9.67）：导入字体跨进程只读 Provider —— 新建文件（非搬运）。
 *
 * 广播分片方案（v2.9.66）在 MIUI 实测被系统吞掉（76 发 4 收）—— HyperOS 对跨应用
 * 广播风暴有强抑制，分片推送不可行。Provider 是普通 binder 调用不受限：宿主进程
 * 解析字体缺文件时按需拉取（FontBridgePull），模块进程被 ContentProvider 自动拉起。
 *
 * 安全面（2026-11-15 加固）：exported=true 仍无 manifest 权限（宿主无法声明模块权限），
 * 但所有入口在 binder 路径上校验 Binder.getCallingUid() —— provider 的 call/openFile/
 * query 都是 binder 调用，callingUid 由内核填写不可伪造（与广播 callingUid 恒
 * system_server 本质不同），只放行 com.miui.home 与本进程。
 *   - openFile 强制 mode=r；id 必须过 ClockFontStore.isValidId 严格校验
 *     （UUID_名称.ttf/otf/ttc），无路径穿越面；
 *   - call("bridge_token") 是能力令牌（BridgeToken）的唯一出口 —— 第三方拿不到令牌
 *     即无法伪造门禁广播与设置命令。
 */
package com.homemode4miui.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.File
import java.io.FileNotFoundException

class FontBridgeProvider : ContentProvider() {

    private fun fontDir(): File = File(requireNotNull(context).filesDir, "clock_fonts")

    /**
     * 调用方是否可信（宿主桌面 / 本进程 / system）。
     * binder 路径的 callingUid 是内核填写的真实 uid —— 这是本模块跨进程鉴权的根。
     */
    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true
        val pkgs = runCatching { context?.packageManager?.getPackagesForUid(uid) }.getOrNull()
        return pkgs?.contains(GraceBridge.PACKAGE_HOST) == true
    }

    private fun fontFile(id: String?): File {
        require(!id.isNullOrBlank()) { "missing font id" }
        require(com.galaxyrio.gracelauncher.data.ClockFontStore.isValidId(id)) { "invalid font id" }
        val file = File(fontDir(), id)
        if (!file.isFile) throw FileNotFoundException(id)
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(isTrustedCaller()) { "untrusted caller" }
        require(mode == "r") { "read-only provider" }
        return ParcelFileDescriptor.open(fontFile(uri.lastPathSegment), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(isTrustedCaller()) { "untrusted caller" }
        val cursor = MatrixCursor(arrayOf("id", "size"))
        fontDir().listFiles().orEmpty()
            .filter { it.isFile && com.galaxyrio.gracelauncher.data.ClockFontStore.isValidId(it.name) }
            .sortedBy { it.name.lowercase() }
            .forEach { cursor.addRow(arrayOf(it.name, it.length())) }
        return cursor
    }

    /** 能力令牌出口（见 BridgeToken 头注）。不可信调用方返回 null，不泄露任何信息。 */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_BRIDGE_TOKEN) return null
        if (!isTrustedCaller()) return null
        return Bundle().apply { putString(KEY_TOKEN, BridgeToken.value) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
    override fun onCreate(): Boolean = true

    companion object {
        /** 与 AndroidManifest 的 android:authorities 必须一致（BridgeTokenClient 引用）。 */
        const val AUTHORITY = "com.homemode4miui.fontbridge"
        const val METHOD_BRIDGE_TOKEN = "bridge_token"
        const val KEY_TOKEN = "token"
    }
}
