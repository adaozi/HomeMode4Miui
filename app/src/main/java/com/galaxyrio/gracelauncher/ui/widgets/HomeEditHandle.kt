/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/widgets/HomeEditHandle.kt
 * P4 移植：逐字拷贝，未改动。编辑模式拖动把手（内容区位置 / widget 高度）。
 */

package com.galaxyrio.gracelauncher.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R

@Composable
internal fun HomeEditHandle(
    label: String, changed: Boolean, tag: String, onReset: () -> Unit,
    onDelta: (Float) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val latestDelta by rememberUpdatedState(onDelta)
    val latestFinish by rememberUpdatedState(onFinished)
    val interactions = remember { MutableInteractionSource() }
    val color = MaterialTheme.colorScheme.onSurface
    val foreground = MaterialTheme.colorScheme.surface
    val up = stringResource(R.string.widget_adjust_up)
    val down = stringResource(R.string.widget_adjust_down)
    Box(modifier.fillMaxWidth().height(40.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(color, CircleShape))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (changed) Surface(shape = CircleShape, color = color, contentColor = foreground) {
                IconButton(onClick = onReset, modifier = Modifier.size(40.dp).testTag("${tag}_reset")) {
                    Icon(painterResource(R.drawable.ms_restart_alt), stringResource(R.string.reset))
                }
            } else Spacer(Modifier.size(40.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(color)
                .indication(interactions, ripple(color = foreground))
                .semantics {
                    contentDescription = label
                    customActions = listOf(
                        CustomAccessibilityAction(up) { latestDelta(-16f); latestFinish(); true },
                        CustomAccessibilityAction(down) { latestDelta(16f); latestFinish(); true },
                    )
                }
                .pointerInput(density) {
                    detectVerticalDragGestures(
                        onDragEnd = { latestFinish() }, onDragCancel = { latestFinish() },
                        onVerticalDrag = { change, amount -> change.consume(); latestDelta(amount / density) },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { position ->
                            val press = PressInteraction.Press(position)
                            interactions.emit(press)
                            interactions.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                        },
                        onTap = { latestDelta(if (it.y < size.height / 2) -16f else 16f); latestFinish() },
                    )
                }, contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ms_unfold_more), null, tint = foreground)
            }
        }
    }
}
