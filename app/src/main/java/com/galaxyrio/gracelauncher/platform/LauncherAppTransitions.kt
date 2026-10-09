/*
 * P5 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/platform/LauncherAppTransitions.kt
 * 裁剪版搬运（B5.1，PORTING_GraceLauncher.md「砍功能不砍体验」路径）：
 *   - 保留：LauncherIconTarget（图标注册/隐藏/屏幕坐标换算，逐字）+
 *     LauncherAppTransitions 骨架（register/unregister/launch 防抖 + 图标 capture +
 *     AppLaunchTransition 构造 + close 取消），逐字。
 *   - 裁剪：HomeReturnAnimation + GestureNavContract + selectHomeTarget
 *     （Quickstep 手势返回桌面的图标回位动画）。降级理由：
 *     ① 底座 HomeView.onHomeIntent() 不携带 Intent 参数，拿到 contract 需扩展底座
 *       hook（onNewIntent 拦截），违反「A 组零改动」边界；
 *     ② MIUI 快速手势是否注入 AOSP GestureNavContract（gesture_nav_contract_v1）
 *       未实测，多半由 MIUI 自有返回动画接管；
 *     ③ HomeReturnAnimation 依赖往宿主 Activity content 注入 SurfaceView，
 *       在 MIUI 桌面窗口结构（DragLayer）内的表现未验证。
 *     恢复节点：P6/后续阶段实测 contract 后逐字补回（本文件头部 diff 即可）。
 */
package com.galaxyrio.gracelauncher.platform

import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** An icon's lifetime follows its Compose node, not a previous click position. */
internal class LauncherIconTarget(
    val components: List<ComponentName>,
    val home: Boolean,
    val folder: Boolean,
    val view: View,
    val boundsInWindow: () -> Rect?,
    val capture: suspend () -> Bitmap?,
) {
    var hidden by mutableStateOf(false)

    fun screenBounds(): RectF? {
        if (!view.isAttachedToWindow) return null
        val bounds = boundsInWindow()?.takeUnless { it.isEmpty } ?: return null
        val window = IntArray(2).also(view::getLocationInWindow)
        val screen = IntArray(2).also(view::getLocationOnScreen)
        return RectF(bounds).apply { offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat()) }
    }
}

/**
 * 启动动画中枢（P5 裁剪版）。activity 参数为宿主 Launcher 实例，本裁剪版暂未使用
 * （原版用于 HomeReturnAnimation 的窗口装饰视图/Surface 注入），保留签名以对齐
 * 原版调用点（MainActivity → 本模块 GraceLauncherRoute），恢复时零签名变更。
 */
internal class LauncherAppTransitions(@Suppress("unused") private val activity: Activity, private val scope: CoroutineScope) {
    private val targets = linkedSetOf<LauncherIconTarget>()
    private var launchJob: Job? = null

    fun register(target: LauncherIconTarget) { targets.add(target) }
    fun unregister(target: LauncherIconTarget) { targets.remove(target); target.hidden = false }

    fun launch(view: View, bounds: Rect, start: (AppLaunchTransition?) -> Unit) {
        if (launchJob?.isActive == true) return
        // Pick the clicked instance, not another copy of this app in a retained
        // drawer or a folder. Capture before a popup can dispose its icon layer.
        val target = targets.lastOrNull { it.view == view && it.boundsInWindow() == bounds }
        launchJob = scope.launch {
            val bitmap = try {
                withTimeoutOrNull(80) { target?.capture?.invoke() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            val transition = AppLaunchTransition.fromIcon(view, bounds, bitmap)
            Log.d(TAG, "Launch bounds=${transition?.sourceBounds} thumbnail=${bitmap != null}")
            start(transition)
        }
    }

    /** Home 键复位/卸载时取消进行中的 capture（原版此处同时关闭 Quickstep 合约动画，已裁剪）。 */
    fun close() {
        launchJob?.cancel()
    }

    companion object { const val TAG = "GraceTransitions" }
}
