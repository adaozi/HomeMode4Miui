/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 逐字拷贝（P4），原路径 ui/overlays/PopupMorphGeometry.kt；弹层圆形→面板 morph 几何（纯函数，与原版 PopupMorphGeometryTest 对齐）。
 */

package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.util.lerp

internal data class PopupMorphFrame(val bounds: Rect, val radius: Float)

internal fun popupMorphFrame(source: Rect, target: Rect, progress: Float, targetRadius: Float): PopupMorphFrame {
    val diameter = minOf(source.width, source.height).coerceAtLeast(1f)
    val radius = diameter / 2f
    val circle = Rect(source.center.x - radius, source.center.y - radius, source.center.x + radius, source.center.y + radius)
    // Keep the expressive spring's small overshoot; only guard negative sizes on
    // the closing spring. Clamping at 1 would turn its settling motion into a snap.
    val fraction = progress.coerceAtLeast(0f)
    val bounds = Rect(
        lerp(circle.left, target.left, fraction), lerp(circle.top, target.top, fraction),
        lerp(circle.right, target.right, fraction), lerp(circle.bottom, target.bottom, fraction),
    )
    return PopupMorphFrame(bounds, lerp(radius, targetRadius, fraction).coerceIn(0f, minOf(bounds.width, bounds.height) / 2f))
}
