/*
 * 上游同步 v1.1.8 S3（2026-10-11）—— 移植自 GraceLauncher v1.1.8 原版（GPL-3.0）。
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2

/** Visual bounds, not the untransformed touch target. Velocity is in root px/s. */
internal data class GraceButtonOrigin(val bounds: Rect, val velocity: Offset = Offset.Zero, val artwork: ImageBitmap? = null)

// Launcher-style fast-out/slow-in timing, with a longer travel phase than the
// old spring. Keep geometry, timing and the wave's opacity independent.
private val OpeningEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/** One handoff owns both pages until the destination has replaced the button. */
@Stable
internal class GraceButtonTransition(val action: GraceButtonAction, val origin: GraceButtonOrigin, val originIsButton: Boolean = true,
    private val durationMillis: Int = 500, private val animationsEnabled: Boolean = true) {
    val progress = Animatable(if (animationsEnabled) 0f else 1f)
    var viewport by mutableStateOf(Rect.Zero)
    var searchBounds by mutableStateOf(Rect.Zero)
    var lockCommitted = false
    var leftForeground = false

    val fraction: Float get() = progress.value.coerceIn(0f, 1f)
    val searchBackground: Float get() = smoothStep(0.04f, 0.78f, fraction)
    val searchBarAlpha: Float get() = smoothStep(0.84f, 0.94f, fraction)
    val searchResultsAlpha: Float get() = smoothStep(0.58f, 0.98f, fraction)

    suspend fun animate() {
        // A disabled lock animation still supplies the black endpoint used to
        // prevent a bright frame before Android turns the display off.
        if (!animationsEnabled) return
        // Measure the real M3 input (including font scale/insets), not an estimated endpoint.
        withTimeoutOrNull(350) {
            snapshotFlow { !viewport.isEmpty && (action != GraceButtonAction.Search || !searchBounds.isEmpty) }.first { it }
        }
        val velocity = if (action == GraceButtonAction.Search) {
            val tangent = (searchControls().first - origin.bounds.center) * 3f
            // Project the released velocity onto the curve's initial tangent.
            // In particular, an upward fling must not restart at zero velocity.
            ((origin.velocity.x * tangent.x + origin.velocity.y * tangent.y) /
                (tangent.x * tangent.x + tangent.y * tangent.y).coerceAtLeast(1f)).coerceIn(0f, 2.5f)
        } else (origin.velocity.getDistance() / viewport.height.coerceAtLeast(1f)).coerceIn(0f, 2.5f)
        if (action == GraceButtonAction.LockScreen) {
            progress.animateTo(1f, tween(230, easing = CubicBezierEasing(0.3f, 0f, 0.7f, 1f)))
        } else {
            val duration = durationMillis.coerceIn(100, 1500)
            val initialSlope = velocity * duration / 1_000f
            val easing = Easing { time ->
                val remaining = 1f - time
                // Tween itself ignores initialVelocity. This short-lived coast
                // preserves the release velocity while still reaching zero speed
                // at the endpoint. Taps retain the slower, zero-speed departure.
                val coast = initialSlope * time * remaining * remaining * remaining * remaining
                (OpeningEasing.transform(time) + coast).coerceIn(0f, 1f)
            }
            progress.animateTo(1f, tween(duration, easing = easing))
        }
    }

    fun searchControls(): Pair<Offset, Offset> {
        val from = origin.bounds.center
        val to = searchBounds.takeUnless { it.isEmpty }?.center ?: from
        val lift = (from.y - to.y).coerceAtLeast(0f) * 0.72f
        val momentum = origin.velocity * 0.16f
        val bend = Offset(momentum.x.coerceIn(-viewport.width * 0.24f, viewport.width * 0.24f),
            momentum.y.coerceIn(-viewport.height * 0.16f, viewport.height * 0.16f))
        // Vertical departure, horizontal arrival; momentum bends this same curve.
        return (from + Offset(bend.x, -lift + bend.y)) to
            (to + Offset((from.x - to.x) * 0.85f + bend.x * 0.2f, 0f))
    }

    fun revealCenter(): Offset {
        val p = fraction
        // The wave inherits the release direction, then settles back to the button origin.
        val limit = origin.bounds.maxDimension * 3f
        val drift = origin.velocity * (0.16f * p * (1f - p) * (1f - p))
        return origin.bounds.center + Offset(drift.x.coerceIn(-limit, limit), drift.y.coerceIn(-limit, limit))
    }

    fun revealRadius(softEdge: Float): Float {
        val center = revealCenter()
        val farthest = listOf(viewport.topLeft, viewport.topRight, viewport.bottomLeft, viewport.bottomRight)
            .maxOf { (it - center).getDistance() } + softEdge
        return mix(origin.bounds.maxDimension / 2f, farthest, fraction)
    }

    fun revealEdge(maximum: Float): Float = mix(maximum / 26f, maximum, smoothStep(0f, 0.25f, fraction))
}

// 审计修缮（2026-10-16）：删除 Modifier.graceReveal —— 全仓零调用（含定义文件内部），
// 英雄过渡的实际呈现走 GraceButtonTransitionOverlay。

/** Rendered above both pages; consumes input until the handoff (or screen-off) completes. */
@Composable
internal fun GraceButtonTransitionOverlay(transition: GraceButtonTransition) {
    val buttonColor = MaterialTheme.colorScheme.primaryContainer
    val buttonInk = MaterialTheme.colorScheme.onPrimaryContainer
    val fieldColor = SearchBarDefaults.colors().containerColor
    val glyph = painterResource(R.drawable.ic_launcher_foreground)
    val artwork = remember(transition.origin.artwork) { transition.origin.artwork?.let { BitmapPainter(it) } }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { transition.viewport = it.boundsInRoot() }
        .clearAndSetSemantics {}
        .pointerInput(transition) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        }) {
        val p = transition.fraction
        val root = transition.viewport.topLeft
        when (transition.action) {
            GraceButtonAction.Search -> {
                val source = transition.origin.bounds
                val target = transition.searchBounds.takeUnless { it.isEmpty } ?: source
                val from = source.center
                val to = target.center
                val (control1, control2) = transition.searchControls()
                val center = bezier(from, control1, control2, to, p) - root
                val tangent = bezierTangent(from, control1, control2, to, p)
                val morph = smoothStep(0.66f, 0.98f, p)
                val departure = smoothStep(0f, 0.14f, p)
                val speed = tangent.getDistance() * transition.progress.velocity.coerceAtLeast(0f)
                val pull = (speed / (source.height.coerceAtLeast(1f) * 32f)).coerceIn(0f, 0.3f) * departure * (1f - morph)
                val diameter = mix(source.height, target.height, p)
                val width = mix(mix(source.width, diameter, departure) * (1f + pull), target.width, morph)
                val height = mix(diameter / (1f + pull * 0.55f), target.height, morph)
                val angle = (atan2(tangent.y, tangent.x) * 180f / Math.PI.toFloat()) * departure *
                    (1f - smoothStep(0.44f, 0.66f, p))
                val alpha = 1f - smoothStep(0.94f, 1f, p)
                val appearanceFade = smoothStep(0f, 0.26f, p)
                withTransform({ rotate(angle, center) }) {
                    drawRoundRect(lerp(buttonColor, fieldColor, morph).copy(alpha = alpha * if (artwork == null) 1f else appearanceFade),
                        topLeft = center - Offset(width / 2f, height / 2f), size = Size(width, height),
                        // Elliptical corners in flight become the exact horizontal M3 pill.
                        cornerRadius = CornerRadius(mix(width / 2f, height / 2f, morph), height / 2f))
                    if (artwork != null) drawGlyph(artwork, center, Size(width, height), null, 1f - appearanceFade)
                }
                if (artwork == null) drawGlyph(glyph, center, source.size * (48f / 54f), buttonInk, 1f - smoothStep(0.02f, 0.26f, p))
            }
            GraceButtonAction.AppList -> {
                val edge = transition.revealEdge(52.dp.toPx())
                val center = transition.revealCenter() - root
                val radius = transition.revealRadius(edge)
                // Lose most of the solid fill early; let the faint tail trail
                // behind the wave without changing its reveal radius/soft edge.
                val remainingFill = (1f - p / 0.48f).coerceIn(0f, 1f)
                drawRect(revealBrush(center, radius, edge, buttonColor),
                    alpha = remainingFill * remainingFill * remainingFill)
                if (artwork != null) drawGlyph(artwork, center, transition.origin.bounds.size, null, 1f - smoothStep(0f, 0.14f, p))
                else drawGlyph(glyph, center, transition.origin.bounds.size * (48f / 54f), buttonInk,
                    1f - smoothStep(0f, 0.14f, p))
            }
            GraceButtonAction.LockScreen -> {
                if (p >= 1f) drawRect(Color.Black) else {
                    val center = transition.origin.bounds.center - root
                    val edge = 42.dp.toPx()
                    val farthest = listOf(Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
                        .maxOf { (it - center).getDistance() } + edge
                    val radius = (farthest * (1f - p)).coerceAtLeast(0.5f)
                    val inner = (1f - edge / radius).coerceIn(0f, 0.999f)
                    drawRect(Brush.radialGradient(0f to Color.Transparent, inner to Color.Transparent,
                        1f to Color.Black, center = center, radius = radius))
                }
            }
            else -> Unit
        }
    }
}

private fun revealBrush(center: Offset, radius: Float, edge: Float, color: Color = Color.White): Brush =
    Brush.radialGradient(0f to color, (1f - edge / radius.coerceAtLeast(0.5f)).coerceIn(0f, 0.999f) to color,
        1f to color.copy(alpha = 0f), center = center, radius = radius.coerceAtLeast(0.5f))

private fun DrawScope.drawGlyph(painter: Painter, center: Offset, size: Size, color: Color?, alpha: Float) {
    if (alpha <= 0f) return
    withTransform({ translate(center.x - size.width / 2f, center.y - size.height / 2f) }) {
        with(painter) { draw(size, alpha = alpha, colorFilter = color?.let { ColorFilter.tint(it) }) }
    }
}

private fun bezier(a: Offset, b: Offset, c: Offset, d: Offset, t: Float): Offset {
    val u = 1f - t
    return a * (u * u * u) + b * (3f * u * u * t) + c * (3f * u * t * t) + d * (t * t * t)
}

private fun bezierTangent(a: Offset, b: Offset, c: Offset, d: Offset, t: Float): Offset {
    val u = 1f - t
    return (b - a) * (3f * u * u) + (c - b) * (6f * u * t) + (d - c) * (3f * t * t)
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smoothStep(start: Float, end: Float, value: Float): Float {
    val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
