/*
 * P5 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/platform/AppLaunchTransition.kt
 * 逐字搬运（B5.1，零改动）：坐标换算 + thumbnail/scale-up ActivityOptions 构造，
 * 只依赖 View 与 ActivityOptions，不依赖 ComponentActivity —— 宿主进程内可用。
 */
package com.galaxyrio.gracelauncher.platform

import android.app.ActivityOptions
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.window.SplashScreen
import androidx.core.graphics.scale

/** Public launch animation data; all coordinates supplied by the UI are in pixels. */
data class AppLaunchTransition(
    val sourceBounds: Rect,
    val options: Bundle,
) {
    companion object {
        /**
         * Compose's boundsInWindow and ActivityOptions use different coordinate spaces.
         * Convert explicitly even when the host view is inset. These are animation
         * requests: Android 16 can ignore them for task transitions without privileged
         * overrideTaskTransition access. Never simulate a second app window in HOME.
         */
        fun fromIcon(view: View, boundsInWindow: Rect, thumbnail: Bitmap? = null): AppLaunchTransition? {
            if (!view.isAttachedToWindow || boundsInWindow.isEmpty) return null

            val viewInWindow = IntArray(2)
            val viewOnScreen = IntArray(2)
            view.getLocationInWindow(viewInWindow)
            view.getLocationOnScreen(viewOnScreen)
            val sourceBounds = Rect(boundsInWindow).apply {
                offset(viewOnScreen[0] - viewInWindow[0], viewOnScreen[1] - viewInWindow[1])
            }
            val x = boundsInWindow.left - viewInWindow[0]
            val y = boundsInWindow.top - viewInWindow[1]
            // Supply the actual rendered icon (including icon packs/theming), not
            // a clip reveal, which does not scale the app window out of the icon.
            val options = if (thumbnail != null && !thumbnail.isRecycled) {
                val scaled = thumbnail.scale(boundsInWindow.width(), boundsInWindow.height())
                ActivityOptions.makeThumbnailScaleUpAnimation(view, scaled, x, y)
            } else {
                ActivityOptions.makeScaleUpAnimation(view, x, y,
                    boundsInWindow.width(), boundsInWindow.height())
            }
            view.display?.let { options.launchDisplayId = it.displayId }
            if (Build.VERSION.SDK_INT >= 33) {
                options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_ICON)
            }
            return AppLaunchTransition(sourceBounds, options.toBundle())
        }
    }
}
