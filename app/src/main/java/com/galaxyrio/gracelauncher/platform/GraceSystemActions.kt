/*
 * 上游同步 v1.1.8 S3（2026-10-11）—— 移植自 GraceLauncher v1.1.8 原版（GPL-3.0）。
 */
package com.galaxyrio.gracelauncher.platform

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.galaxyrio.gracelauncher.data.GraceButtonTarget
import java.net.URI

/** Only explicit Grace-button actions; no scheduled or automatic system actions. */
internal object GraceSystemActions {
    val hasAccessibility: Boolean get() = GraceAccessibilityService.connected

    fun websiteUri(input: String): Uri? = runCatching {
        val text = input.trim()
        if (text.isEmpty() || text.length > 2048) return null
        val uri = URI(if ("://" in text) text else "https://$text")
        if (uri.scheme?.lowercase() !in setOf("https", "http") || uri.host.isNullOrBlank() ||
            uri.rawUserInfo != null || uri.port !in -1..65535) return null
        Uri.parse(uri.toASCIIString())
    }.getOrNull()

    fun perform(context: Context, target: GraceButtonTarget): Boolean = when (target.action) {
        GraceButtonAction.Website -> target.url?.let(::websiteUri)?.let { uri ->
            open(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } ?: false
        GraceButtonAction.Assistant -> open(context, Intent(Intent.ACTION_ASSIST)) ||
            open(context, Intent(Intent.ACTION_VOICE_COMMAND))
        GraceButtonAction.LockScreen, GraceButtonAction.Notifications, GraceButtonAction.QuickSettings ->
            // HomeMode4Miui：三层兜底链 —— MIUI 原生（逆向实锤，宿主/模块进程均可用）
            // → root（su 命令行）→ 无障碍服务。原生层逆自 MiuiHomeOS2/OS3 双版本一致实现。
            HostSystemActions.run {
                when (target.action) {
                    GraceButtonAction.LockScreen -> lockScreen(context)
                    GraceButtonAction.Notifications -> expandNotifications(context)
                    GraceButtonAction.QuickSettings -> expandQuickSettings(context)
                    else -> false
                }
            } || GraceRootActions.perform(target.action) || GraceAccessibilityService.perform(target.action)
        else -> false
    }

    fun openAccessibilitySettings(context: Context): Boolean = open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun open(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/** No event subscriptions, window-content retrieval, key filtering or gesture injection. */
class GraceAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply { eventTypes = 0 }
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: GraceAccessibilityService? = null
        internal val connected: Boolean get() = current != null

        internal fun perform(action: GraceButtonAction): Boolean {
            val id = when (action) {
                GraceButtonAction.LockScreen -> GLOBAL_ACTION_LOCK_SCREEN // API 28, our minimum.
                GraceButtonAction.Notifications -> GLOBAL_ACTION_NOTIFICATIONS
                GraceButtonAction.QuickSettings -> GLOBAL_ACTION_QUICK_SETTINGS
                else -> return false
            }
            return runCatching { current?.performGlobalAction(id) == true }.getOrDefault(false)
        }
    }
}
