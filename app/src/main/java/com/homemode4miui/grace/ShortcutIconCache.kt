/*
 * v2.9.155 —— pin 快捷方式图标解码 + 进程内缓存（新建文件）。
 *
 * 为什么需要它：
 *  ① 桌面（GraceHomeViewModel.syncPinnedShortcuts）要图标渲染抽屉/搜索；
 *  ② 设置侧图标设计器要缩略图（模块进程无 LauncherApps 快捷方式权限）——
 *     只能由宿主解码后随快照回带（见 GraceSettingsBridge.Snapshot.savedShortcuts.iconPng）。
 * 两处必须用**同一份**图标：先经 MIUI IconCustomizer 裁成桌面样式，再落缓存，
 * 避免桌面与设计器预览两张不同的图。
 *
 * 线程：decode 是重 IO/binder，调用方负责放到 IO 线程；缓存本身线程安全。
 */
package com.homemode4miui.grace

import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import com.homemode4miui.util.ModLog
import java.util.concurrent.ConcurrentHashMap

object ShortcutIconCache {
    /** 与 GracePinItemHook.persist / LauncherShortcut.key 逐字一致。 */
    fun key(pkg: String, id: String): String = "shortcut:$pkg/" + android.net.Uri.encode(id)

    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun get(key: String): Bitmap? = cache[key]

    fun putAll(values: Map<String, Bitmap>) {
        if (values.isNotEmpty()) cache.putAll(values)
    }

    fun clear() = cache.clear()

    /**
     * MIUI 图标样式遮罩：`IconCustomizer.generateIconStyleDrawable(Drawable)` 是 MIUI 桌面
     * 把任意图标裁成当前主题形状（圆/圆角/内容占比）的入口 —— 快捷方式图标来自应用自带
     * 位图、不经 getBadgedIcon 主题化，必须显式过一遍才与桌面普通应用一致。
     * 反射失败（非 MIUI / 签名漂移）原样返回。
     */
    fun miuiStyle(raw: Drawable): Drawable = runCatching {
        val cls = Class.forName("miui.content.res.IconCustomizer")
        val method = cls.getMethod("generateIconStyleDrawable", Drawable::class.java)
        (method.invoke(null, raw) as? Drawable) ?: raw
    }.getOrDefault(raw)

    /** 解码当前用户所有 pinned 快捷方式图标（key → Bitmap）；查询失败返回空表。 */
    fun decodePinned(launcher: LauncherApps, density: Int): Map<String, Bitmap> {
        val shortcuts = runCatching {
            launcher.getShortcuts(
                LauncherApps.ShortcutQuery()
                    .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
                Process.myUserHandle(),
            )
        }.onFailure { ModLog.e("ShortcutIconCache query failed", it) }.getOrNull() ?: return emptyMap()

        val out = HashMap<String, Bitmap>(shortcuts.size)
        shortcuts.forEach { info ->
            val pkg = info.`package` ?: return@forEach
            runCatching {
                val raw = launcher.getShortcutBadgedIconDrawable(info, density) ?: return@runCatching null
                val styled = miuiStyle(raw)
                val fallback = (96 * density / 160).coerceAtLeast(1)
                val w = styled.intrinsicWidth.takeIf { it > 0 } ?: fallback
                val h = styled.intrinsicHeight.takeIf { it > 0 } ?: fallback
                styled.toBitmap(w, h)
            }.getOrNull()?.let { out[key(pkg, info.id)] = it }
        }
        return out
    }

    /** 缩略图 PNG（跨桥回带用）：160px 兼顾设计器预览清晰度与广播体积（通常 1~3 个）。 */
    fun thumbnailPng(bitmap: Bitmap, size: Int = 160): String? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
        if (scaled !== bitmap) scaled.recycle()
        android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
    }.onFailure { ModLog.e("ShortcutIconCache thumbnailPng failed", it) }.getOrNull()
}
