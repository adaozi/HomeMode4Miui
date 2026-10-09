/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/components/LauncherSystemBars.kt
 * hotfix6 移植：逐字拷贝，唯一适配 —— 原版 (LocalContext as? Activity)?.window 在 grace
 * 主树必为 null（LocalContext 是模块包装 context，GraceHomeRoot 覆盖），改为调用方
 * 显式传入 hostWindow（hostActivity.window）。宿主桩策略同 WallpaperBlur。
 */
package com.galaxyrio.gracelauncher.ui.components

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Build
import android.os.CancellationSignal
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areStatusBarsVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationControlListenerCompat
import androidx.core.view.WindowInsetsAnimationControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Own visibility once per window, rather than re-hiding the bar on every recomposition. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun rememberLauncherSystemBars(
    hostWindow: Window?,
    autoHide: Boolean,
    darkIcons: Boolean,
    allowPullDown: Boolean,
): NestedScrollConnection {
    val window = hostWindow
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val bars = remember(window, view) { window?.let { LauncherStatusBar(it, view, autoHide) } }
    val canPullDown by rememberUpdatedState(allowPullDown)
    SideEffect { bars?.configure(autoHide, darkIcons) }
    DisposableEffect(bars, lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> bars?.resume()
                Lifecycle.Event.ON_PAUSE -> bars?.pause()
                else -> Unit
            }
        }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bars?.resume()
            else if (!focused) bars?.pause()
        }
        lifecycle.addObserver(observer)
        val tree = view.viewTreeObserver
        tree.addOnWindowFocusChangeListener(focusListener)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bars?.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(focusListener)
            bars?.pause()
        }
    }
    val visible = WindowInsets.areStatusBarsVisible
    LaunchedEffect(bars, visible) { bars?.onVisibilityChanged(visible) }
    return remember(bars) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Only the downward motion left AFTER the list has scrolled reaches
                // the top edge stretch. Match Android's 0.5px overscroll tolerance
                // so rounding noise, ordinary scrolling and flings cannot reveal it.
                if (canPullDown && source == NestedScrollSource.UserInput && available.y > 0.5f) bars?.reveal()
                // Leave the remaining motion to the native overscroll effect.
                return Offset.Zero
            }
        }
    }
}

@Suppress("DEPRECATION")
private class LauncherStatusBar(window: Window, private val view: View, private var autoHide: Boolean) {
    private val insets = WindowInsetsControllerCompat(window, view)
    private val type = WindowInsetsCompat.Type.statusBars()
    private var active = false
    private var targetShown: Boolean? = null
    private var request: CancellationSignal? = null
    private var animationController: WindowInsetsAnimationControllerCompat? = null
    private var animator: ValueAnimator? = null
    private val hideLater = Runnable {
        if (active && autoHide && view.hasWindowFocus()) setShown(false, animate = true)
    }

    init {
        // Regular transparent bars, not Android's transient immersive overlay/scrim.
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        if (Build.VERSION.SDK_INT < 35) window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
    }

    fun configure(hide: Boolean, darkIcons: Boolean) {
        insets.isAppearanceLightStatusBars = darkIcons
        insets.isAppearanceLightNavigationBars = darkIcons
        if (autoHide == hide) return
        autoHide = hide
        view.removeCallbacks(hideLater)
        if (active) setShown(!autoHide, animate = false)
    }

    fun resume() {
        if (active) return
        active = true
        setShown(!autoHide, animate = false)
    }

    fun pause() {
        active = false
        view.removeCallbacks(hideLater)
        cancelAnimation()
        targetShown = null
    }

    fun reveal() {
        if (!active || !autoHide || !view.hasWindowFocus()) return
        setShown(true, animate = true)
        scheduleHide()
    }

    fun onVisibilityChanged(visible: Boolean) {
        // Edge swipes remain supported, and use the same timeout as a pull in the list.
        if (active && autoHide && visible && request == null) {
            targetShown = true
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        view.removeCallbacks(hideLater)
        view.postDelayed(hideLater, 3_000L)
    }

    private fun applyVisibility(shown: Boolean) {
        if (shown) insets.show(type) else insets.hide(type)
    }

    private fun cancelAnimation() {
        val previous = request
        request = null
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
        animationController = null
        previous?.cancel()
    }

    private fun setShown(shown: Boolean, animate: Boolean) {
        if (targetShown == shown) return
        val fromAlpha = animationController?.takeIf { it.isReady }?.currentAlpha ?: if (shown) 0f else 1f
        cancelAnimation()
        targetShown = shown
        if (!animate || Build.VERSION.SDK_INT < 30 || !view.hasWindowFocus() ||
            ViewCompat.getRootWindowInsets(view)?.isVisible(type) == shown) {
            applyVisibility(shown)
            return
        }
        val signal = CancellationSignal()
        request = signal
        val easing = DecelerateInterpolator()
        runCatching {
            insets.controlWindowInsetsAnimation(type, 220L, easing, signal,
                object : WindowInsetsAnimationControlListenerCompat {
                    override fun onReady(controller: WindowInsetsAnimationControllerCompat, types: Int) {
                        if (request !== signal || !active || !controller.isReady) return
                        animationController = controller
                        // Keep the system window in its shown position. Only opacity changes;
                        // finish() applies the final visibility after the fade has completed.
                        controller.setInsetsAndAlpha(controller.shownStateInsets, fromAlpha, 0f)
                        animator = ValueAnimator.ofFloat(fromAlpha, if (shown) 1f else 0f).apply {
                            duration = 220L
                            interpolator = easing
                            addUpdateListener {
                                if (request === signal && controller.isReady) controller.setInsetsAndAlpha(
                                    controller.shownStateInsets, it.animatedValue as Float, it.animatedFraction,
                                )
                            }
                            addListener(object : AnimatorListenerAdapter() {
                                override fun onAnimationEnd(animation: Animator) {
                                    if (request === signal && controller.isReady) controller.finish(shown)
                                }
                            })
                            start()
                        }
                    }

                    override fun onFinished(controller: WindowInsetsAnimationControllerCompat) {
                        if (request !== signal) return
                        request = null
                        animator = null
                        animationController = null
                    }

                    override fun onCancelled(controller: WindowInsetsAnimationControllerCompat?) {
                        if (request !== signal) return
                        cancelAnimation()
                        // Unsupported/unavailable animation control falls back to public show/hide.
                        // Do not fight a system gesture that takes over an already granted controller.
                        if (controller == null && active && view.hasWindowFocus()) applyVisibility(shown)
                        else targetShown = null
                    }
                })
        }.onFailure {
            if (request === signal) {
                cancelAnimation()
                applyVisibility(shown)
            }
        }
    }
}
