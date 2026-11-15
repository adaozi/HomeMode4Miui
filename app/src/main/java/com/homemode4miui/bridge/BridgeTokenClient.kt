/*
 * 令牌客户端 —— 新建文件（2026-11-15 安全收敛）。运行在【宿主进程 com.miui.home】。
 *
 * 职责：向模块进程的 FontBridgeProvider 取/校验能力令牌（见 BridgeToken 头注）。
 * provider 不可达（模块未装/被禁用）时返回 null，调用方按「拒绝高危命令」降级 ——
 * 不因为鉴权组件异常而放行。
 */
package com.homemode4miui.bridge

import android.content.Context
import android.net.Uri
import com.homemode4miui.util.ModLog

object BridgeTokenClient {

    private val URI: Uri = Uri.parse("content://${FontBridgeProvider.AUTHORITY}")

    /** 最近一次成功取到的令牌（模块进程重启后失效，verify/current 会再刷）。 */
    @Volatile
    private var cached: String? = null

    /** 令牌取得时刻（elapsedRealtime）：短缓存避免挂载关键路径每次命令都走 binder。 */
    @Volatile
    private var cachedAt = 0L

    private const val CACHE_TTL_MS = 5_000L

    /**
     * 取令牌（5s 短缓存）。
     *
     * 不能永久缓存：模块进程重启会轮换令牌，缓存一旦过期会让宿主后续命令全部被拒
     * （媒体/通知控制静默失效）。也不能每次现取：桌面挂载路径（HomeController.attach
     * 的 snapshotRefreshIntent）在主线程，首次 provider call 会拉起模块进程，可能
     * 几十 ms —— 5s TTL 让挂载只付一次、连点命令走缓存。
     */
    @JvmStatic
    fun current(context: Context): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        val hit = cached
        if (hit != null && now - cachedAt < CACHE_TTL_MS) return hit
        return refresh(context) ?: hit
    }

    @JvmStatic
    fun refresh(context: Context): String? = runCatching {
        context.contentResolver
            .call(URI, FontBridgeProvider.METHOD_BRIDGE_TOKEN, null, null)
            ?.getString(FontBridgeProvider.KEY_TOKEN)
            ?.takeIf { it.isNotBlank() }
    }.onFailure {
        ModLog.e("BridgeTokenClient refresh failed (provider unreachable)", it)
    }.getOrNull()?.also {
        cached = it
        cachedAt = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * 校验命令携带的令牌。命中缓存即真；未命中刷新一次（覆盖模块进程重启的轮换）。
     * 全程 runCatching —— 鉴权失败只拒绝命令，绝不抛到广播/UI 线程。
     */
    @JvmStatic
    fun verify(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        cached?.let { if (BridgeToken.sameSecret(it, candidate)) return true }
        val fresh = refresh(context) ?: return false
        return BridgeToken.sameSecret(fresh, candidate)
    }
}
