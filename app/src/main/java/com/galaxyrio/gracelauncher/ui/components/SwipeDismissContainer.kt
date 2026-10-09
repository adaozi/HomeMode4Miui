package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shared bidirectional Material gesture; vertical scrolling and child clicks remain native. */
@Composable
internal fun SwipeDismissContainer(
    itemKey: Any,
    onDismiss: () -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    clipContent: Boolean = true,
    content: @Composable () -> Unit,
) = key(itemKey) {
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    // A restored offscreen anchor must not dismiss a new notification after process death.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { minOf(threshold, it * 0.35f) } }
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    var width by remember { mutableIntStateOf(1) }
    val label = stringResource(R.string.notification_dismiss)
    val dismiss = remember(state) {
        { _: SwipeToDismissBoxValue ->
            scope.launch {
                val accepted = currentDismiss()
                // Normally the data callback removes this item. Recover if the
                // system rejects cancellation (e.g. it became ongoing meanwhile).
                if (accepted) delay(600)
                state.reset()
            }
            Unit
        }
    }
    SwipeToDismissBox(
        state = state,
        gesturesEnabled = enabled,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        onDismiss = dismiss,
        modifier = modifier.then(if (clipContent) Modifier.clip(LauncherLayout.RowShape) else Modifier)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                // Non-clearable notifications still consume a horizontal drag.
                // Otherwise clickable treats an in-bounds swipe as a message tap.
                if (!enabled) detectHorizontalDragGestures { change, _ -> change.consume() }
            }
            .semantics {
                if (enabled) customActions = listOf(CustomAccessibilityAction(label) { currentDismiss() })
            },
        backgroundContent = {
            if (state.dismissDirection != SwipeToDismissBoxValue.Settled) {
                Row(
                    Modifier.fillMaxSize().background(backgroundColor, LauncherLayout.RowShape).padding(horizontal = 16.dp),
                    horizontalArrangement = if (state.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Arrangement.Start else Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = contentColor, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(12.dp))
                    Icon(painterResource(R.drawable.ms_close), null, Modifier.size(24.dp), tint = contentColor)
                }
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().graphicsLayer {
            val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
            alpha = (1f - abs(offset) / width * 0.85f).coerceIn(0f, 1f)
        }) { content() }
    }
}
