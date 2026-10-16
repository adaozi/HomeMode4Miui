/*
 * 移植自 GraceLauncher v1.1.8（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/platform/GestureNavContract.kt
 * 逐字搬运（hotfix return-anim，P5 曾整体裁剪）：上游自研的 Quickstep 图标回位合约
 *（非 framework hidden API —— wire keys 兼容 AOSP GestureNavContract.java 的
 * bundle 协议，纯公开 API：Bundle/Message/Messenger/SurfaceControl/BundleCompat），
 * 宿主进程内可直接使用，零反射。
 */
package com.galaxyrio.gracelauncher.platform

import android.content.ComponentName
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.os.Message
import android.os.UserHandle
import android.util.Log
import android.view.SurfaceControl
import androidx.annotation.RequiresApi
import androidx.core.os.BundleCompat

/**
 * AOSP Launcher3's versioned, non-system launcher handshake. Quickstep owns the
 * app window; we return only our icon bounds, optional icon surface and cleanup
 * callback. Keep the wire keys compatible with GestureNavContract.java in AOSP.
 */
@RequiresApi(30)
internal class GestureNavContract private constructor(
    val component: ComponentName,
    val user: UserHandle,
    private val callback: Message,
) {
    fun sendEndPosition(bounds: RectF, surface: SurfaceControl?, onFinish: Message): Boolean {
        if (!bounds.isUsable()) return false
        val reply = Message.obtain().apply {
            // Quickstep matches obj against a per-gesture token. A fresh message
            // without copyFrom silently fails even when replyTo is correct.
            copyFrom(this@GestureNavContract.callback)
            data = Bundle().apply {
                putParcelable(EXTRA_ICON_POSITION, RectF(bounds))
                putParcelable(EXTRA_ICON_SURFACE, surface)
                putParcelable(EXTRA_ON_FINISH_CALLBACK, onFinish)
            }
        }
        return try {
            callback.replyTo.send(reply)
            true
        } catch (error: android.os.RemoteException) {
            Log.w(TAG, "Quickstep callback is no longer available", error)
            false
        }
    }

    companion object {
        const val EXTRA_CONTRACT = "gesture_nav_contract_v1"
        const val EXTRA_ICON_POSITION = "gesture_nav_contract_icon_position"
        const val EXTRA_ICON_SURFACE = "gesture_nav_contract_surface_control"
        const val EXTRA_REMOTE_CALLBACK = "android.intent.extra.REMOTE_CALLBACK"
        const val EXTRA_ON_FINISH_CALLBACK = "gesture_nav_contract_finish_callback"
        private const val TAG = "GraceTransitions"

        fun fromIntent(intent: Intent): GestureNavContract? {
            if (intent.action != Intent.ACTION_MAIN ||
                !intent.hasCategory(Intent.CATEGORY_HOME)) return null
            return try {
                val extras = intent.getBundleExtra(EXTRA_CONTRACT) ?: return null
                // An Activity intent may outlive this gesture. Never replay its
                // Binder callback after recreation or a subsequent HOME request.
                intent.removeExtra(EXTRA_CONTRACT)
                val component = BundleCompat.getParcelable(extras, Intent.EXTRA_COMPONENT_NAME, ComponentName::class.java)
                val user = BundleCompat.getParcelable(extras, Intent.EXTRA_USER, UserHandle::class.java)
                val callback = BundleCompat.getParcelable(extras, EXTRA_REMOTE_CALLBACK, Message::class.java)
                if (component == null || user == null || callback?.replyTo == null) null
                else GestureNavContract(component, user, Message.obtain().apply { copyFrom(callback) })
            } catch (error: RuntimeException) {
                intent.removeExtra(EXTRA_CONTRACT)
                Log.w(TAG, "Ignoring malformed gesture contract", error)
                null
            }
        }
    }
}

internal fun RectF.isUsable(): Boolean = !isEmpty &&
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
