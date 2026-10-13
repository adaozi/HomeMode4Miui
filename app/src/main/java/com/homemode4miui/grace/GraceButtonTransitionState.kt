/*
 * GraceButton 过渡状态机（v2.9.45-refactor3 从 GraceLauncherRoute 抽离；
 * 上游同步 v1.1.8 S4 英雄过渡语义，沿 FavoritesReorderState 的状态 holder 模式）。
 *
 * 职责：过渡实例唯一性（同一时刻至多一个过渡在飞）+ 前台生命周期清账
 * （退后台清未决过渡、已提交锁屏保留黑场直至亮屏/回前台清残留）+ 搜索页
 * snap 全透明 entrance 标记。锁屏末帧提交协程（withFrameNanos + GraceSystemActions
 * + 1.5s 验证）依赖 context/lifecycle，仍留 Route 的 LaunchedEffect 内。
 */
package com.homemode4miui.grace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.galaxyrio.gracelauncher.ui.components.GraceButtonTransition

internal class GraceButtonTransitionState {

    var transition by mutableStateOf<GraceButtonTransition?>(null)
        private set

    /** 搜索/抽屉 snap 全透明揭幕标记（动画关闭的按钮触发路径置位，回首页清除）。 */
    var instantEntrance by mutableStateOf(false)
        private set

    val searchTransition: GraceButtonTransition?
        get() = transition?.takeIf { it.action == GraceButtonAction.Search }

    val drawerTransition: GraceButtonTransition?
        get() = transition?.takeIf { it.action == GraceButtonAction.AppList }

    /** 是否有过渡在飞（飞入中不接受新动作）。 */
    val active: Boolean
        get() = transition != null

    /** 提交新过渡（唯一性由调用方 performGestureAction 的 active 守卫保证）。 */
    fun begin(transition: GraceButtonTransition) {
        this.transition = transition
    }

    /** 原版语义：动画关闭的按钮触发 Search/AppList 时，搜索/抽屉以 snap 全透明等待揭幕。 */
    fun markInstantEntrance(fromButton: Boolean, animationsEnabled: Boolean, action: GraceButtonAction) {
        instantEntrance = fromButton && !animationsEnabled &&
            action in listOf(GraceButtonAction.Search, GraceButtonAction.AppList)
    }

    /** 首页/抽屉双关后清除 entrance 标记（Route LaunchedEffect(searchOpen, drawerOpen)）。 */
    fun clearInstantEntrance() {
        instantEntrance = false
    }

    /** 清除当前过渡（动画自然结束 / 锁屏撤遮罩 / 生命周期清账共用）。 */
    fun clear() {
        transition = null
    }

    /** 前台生命周期清账：ON_PAUSE 记 leftForeground（已提交锁屏保留黑场），回前台清残留。 */
    fun onLifecycleEvent(event: Lifecycle.Event) {
        val current = transition ?: return
        when (event) {
            Lifecycle.Event.ON_PAUSE -> {
                current.leftForeground = true
                if (!current.lockCommitted) transition = null
            }
            Lifecycle.Event.ON_RESUME -> if (current.leftForeground) transition = null
            else -> Unit
        }
    }
}
