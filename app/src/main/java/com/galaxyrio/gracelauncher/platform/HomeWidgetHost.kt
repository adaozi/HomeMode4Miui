/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 逐字拷贝（P4），原路径 platform/HomeWidgetHost.kt；AppWidgetHost（HOST_ID 0x47524143 与宿主桌面 host 隔离）+ 手势观察 HostView。
 */

package com.galaxyrio.gracelauncher.platform

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/** Stable across process restarts. Setup activities allocate IDs but only HOME listens. */
class HomeWidgetHost(context: Context) : AppWidgetHost(context, HOST_ID) {
    var onProvidersUpdated: (() -> Unit)? = null

    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo): AppWidgetHostView =
        HomeWidgetHostView(context)

    override fun onProvidersChanged() { onProvidersUpdated?.invoke() }
    override fun onAppWidgetRemoved(appWidgetId: Int) { super.onAppWidgetRemoved(appWidgetId); onProvidersUpdated?.invoke() }

    companion object { const val HOST_ID = 0x47524143 }
}

/**
 * 长按观察手势逻辑（G1 抽取）：原 HomeWidgetHostView 的 dispatchTouchEvent 语义原样
 * 上移为委托，供 AppWidget host view 与 MIUI gadget 手势壳（GadgetHostView）共用。
 * cancelSink：长按触发时向原视图链发 ACTION_CANCEL（AppWidgetHostView 必须 super，
 * 普通 ViewGroup 是自身 dispatchTouchEvent），以解耦"观察"与"消费"。
 */
internal class WidgetGestureDelegate(
    private val host: ViewGroup,
    private val cancelSink: (MotionEvent) -> Boolean,
) {
    var onWidgetLongPress: (() -> Unit)? = null
    var interactionEnabled = true
    var hapticsEnabled = true
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var held = false
    private val slop = ViewConfiguration.get(host.context).scaledTouchSlop
    private val longPress = Runnable {
        if (onWidgetLongPress == null || !host.isAttachedToWindow || !interactionEnabled) return@Runnable
        held = true
        val cancel = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, downX, downY, 0)
        cancelSink(cancel)
        cancel.recycle()
        host.parent?.requestDisallowInterceptTouchEvent(true)
        if (hapticsEnabled) host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onWidgetLongPress?.invoke()
    }

    fun handle(event: MotionEvent): Boolean {
        if (!interactionEnabled) { host.removeCallbacks(longPress); held = false; return true }
        if (held) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) held = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downTime = event.downTime
                host.removeCallbacks(longPress)
                host.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) host.removeCallbacks(longPress)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host.removeCallbacks(longPress)
        }
        return cancelSink(event)
    }

    fun cancel() { host.removeCallbacks(longPress); held = false }
}

/** Observe the native widget's gestures without stealing its buttons or scrolling. */
class HomeWidgetHostView(context: Context) : AppWidgetHostView(context) {
    private val gesture = WidgetGestureDelegate(this, ::dispatchSuper)
    var onWidgetLongPress: (() -> Unit)?
        get() = gesture.onWidgetLongPress
        set(value) { gesture.onWidgetLongPress = value }
    var interactionEnabled: Boolean
        get() = gesture.interactionEnabled
        set(value) { gesture.interactionEnabled = value }
    var hapticsEnabled: Boolean
        get() = gesture.hapticsEnabled
        set(value) { gesture.hapticsEnabled = value }

    init { isClickable = true }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = gesture.handle(event)

    private fun dispatchSuper(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    override fun onDetachedFromWindow() {
        gesture.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * G1：MIUI 内置部件（gadget）手势壳。gadget 不是 AppWidgetHostView，无法走 host 链，
 * 用普通 FrameLayout 承载 GadgetFactory.createGadget 的视图并复用同一长按观察语义
 * （WidgetGestureDelegate，自身 dispatchTouchEvent 作 cancelSink）。
 */
class GadgetHostView(context: Context) : FrameLayout(context) {
    private val gesture = WidgetGestureDelegate(this, ::dispatchSuper)
    var onWidgetLongPress: (() -> Unit)?
        get() = gesture.onWidgetLongPress
        set(value) { gesture.onWidgetLongPress = value }
    var interactionEnabled: Boolean
        get() = gesture.interactionEnabled
        set(value) { gesture.interactionEnabled = value }
    var hapticsEnabled: Boolean
        get() = gesture.hapticsEnabled
        set(value) { gesture.hapticsEnabled = value }

    init { isClickable = true }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = gesture.handle(event)

    private fun dispatchSuper(event: MotionEvent): Boolean = super.dispatchTouchEvent(event)

    override fun onDetachedFromWindow() {
        gesture.cancel()
        super.onDetachedFromWindow()
    }
}
