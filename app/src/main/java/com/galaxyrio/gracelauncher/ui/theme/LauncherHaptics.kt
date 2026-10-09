/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/theme/LauncherHaptics.kt
 * P0 移植：逐字拷贝，未改动（P0 骨架暂未消费，随 theme 全量引入）。
 */
package com.galaxyrio.gracelauncher.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback

/** The launcher can disable feedback, but never overrides the system's choice. */
@Composable
internal fun rememberLauncherHaptics(enabled: Boolean): HapticFeedback {
    val context = LocalContext.current
    val delegate = LocalHapticFeedback.current
    return remember(context, delegate, enabled) {
        object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                if (enabled && Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                    delegate.performHapticFeedback(hapticFeedbackType)
                }
            }
        }
    }
}
