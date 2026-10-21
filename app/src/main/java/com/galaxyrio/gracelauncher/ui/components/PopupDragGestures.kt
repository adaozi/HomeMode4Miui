package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange

/** Reject off-angle movement before claiming the drag; reveal only after the extra distance. */
internal suspend fun PointerInputScope.detectPopupDragGestures(
    extraDistance: Float,
    maxAngleDegrees: Int,
    leftEnabled: Boolean,
    onDragStart: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onHorizontalDrag: (PointerInputChange, Float) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val start = PopupSwipeStart(slop, extraDistance, maxAngleDegrees, leftEnabled)
        var pointerId = down.id
        var travel = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: return@awaitEachGesture
            if (change.isConsumed) return@awaitEachGesture
            if (!change.pressed) {
                val next = event.changes.firstOrNull { it.pressed } ?: return@awaitEachGesture
                pointerId = next.id
                continue
            }
            travel += change.positionChange()
            when (val decision = start.evaluate(travel.x, travel.y)) {
                PopupSwipeStart.Decision.Reject -> return@awaitEachGesture
                PopupSwipeStart.Decision.Pending -> {
                    if (travel.x > slop) {
                        // Direction is accepted, but the opening distance is not met yet.
                        // Reserve this horizontal drag before the home pager claims it.
                        change.consume()
                    } else {
                        awaitPointerEvent(PointerEventPass.Final)
                        if (change.isConsumed) return@awaitEachGesture
                    }
                }
                PopupSwipeStart.Decision.Right, PopupSwipeStart.Decision.Left -> {
                    change.consume()
                    onDragStart(change.position)
                    // Keep the existing left-action distance and reversal behavior.
                    val overSlop = if (decision == PopupSwipeStart.Decision.Right) travel.x - slop else travel.x + slop
                    onHorizontalDrag(change, overSlop)
                    if (horizontalDrag(pointerId) {
                            onHorizontalDrag(it, it.positionChange().x)
                            it.consume()
                        }) onDragEnd() else onDragCancel()
                    return@awaitEachGesture
                }
            }
        }
    }
}
