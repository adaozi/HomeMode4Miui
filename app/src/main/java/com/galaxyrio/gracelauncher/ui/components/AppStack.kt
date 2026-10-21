package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance

/**
 * 上游同步 1.2.0：App Stack 横向应用堆（主页/抽屉/文件夹内联容器，无可见名称）。
 *
 * 与上游的差异（模块适配，唯一改动）：
 *  上游签名吃整个 LauncherUiState 并自行解析成员（popupItems/findItem），
 *  本模块主页状态是 GraceHomeUiState（无 popupItems），故改为由调用方传入
 *  已解析好的 [apps]；`gridManaged`（widget 网格托管）随 widget 网格一并省略。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AppStack(
    stack: LauncherFolder,
    apps: List<LauncherApp>,
    onLaunch: (LauncherApp, Rect) -> Unit,
    onDetails: (LauncherApp) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    iconSize: Dp = 40.dp,
    onWallpaper: Boolean = true,
) {
    val inputEnabled = enabled && LocalLauncherInputEnabled.current
    val appearance = LocalLauncherAppearance.current
    val displayedIconSize = iconSize * (appearance.iconSize / 100f)
    val symbolColor = if (onWallpaper) appearance.text else LocalContentColor.current
    var localExpanded by rememberSaveable(stack.id) { mutableStateOf(false) }
    val open = expanded ?: localExpanded
    val expandLabel = stringResource(if (open) R.string.app_stack_collapse else R.string.app_stack_expand)
    val editLabel = stringResource(R.string.app_stack_edit)
    BoxWithConstraints(modifier.fillMaxWidth().testTag("app_stack:${stack.id}")) {
        val density = LocalDensity.current
        val columns = appStackColumns(maxWidth.value, displayedIconSize.value)
        val inset = AppStackInset.dp
        // Anchor the first icon's LEFT edge, rather than centering it in a wide cell.
        val step = (maxWidth - inset * 2 - displayedIconSize) / (columns - 1)
        val rowStep = displayedIconSize + AppStackRowGap.dp
        val overflow = apps.size > columns
        val rows = appStackRows(apps.size, columns, open)
        val height by animateDpAsState(appStackHeight(rows, displayedIconSize.value).dp,
            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "stackHeight")
        val radius by animateDpAsState(if (open && overflow) 16.dp else displayedIconSize / 2 + inset,
            MaterialTheme.motionScheme.defaultSpatialSpec(), label = "stackShape")
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (onWallpaper) 0.25f else 1f))
            .combinedClickable(enabled = inputEnabled, onClick = {}, onLongClick = onEdit)
            .semantics {
                contentDescription = stack.name
                customActions = listOf(CustomAccessibilityAction(editLabel) { if (inputEnabled) onEdit(); inputEnabled })
            }) {
            apps.forEachIndexed { index, app -> key(app.key) {
                val visible = open || !overflow || index < columns - 1
                val alpha by animateFloatAsState(if (visible) 1f else 0f,
                    MaterialTheme.motionScheme.defaultEffectsSpec(), label = "stackIcon")
                var bounds by remember { mutableStateOf(Rect.Zero) }
                val interactions = remember { MutableInteractionSource() }
                val rippleMask = app.icon.takeUnless { appearance.themedIcons && app.monochromeIcon != null }
                Box(Modifier.offset(x = inset + step * (index % columns), y = inset + rowStep * (index / columns))
                    .size(displayedIconSize).graphicsLayer { this.alpha = alpha }
                    .then(if (!visible) Modifier.clearAndSetSemantics {} else Modifier)
                    .combinedClickable(enabled = inputEnabled && visible,
                        interactionSource = interactions, indication = null,
                        onClick = { onLaunch(app, bounds) },
                        onLongClick = { onDetails(app) })
                    .semantics { if (visible) contentDescription = app.label },
                    contentAlignment = Alignment.Center) {
                    AppIcon(app, Modifier.onGloballyPositioned { bounds = it.boundsInWindow() }, size = iconSize)
                    // Mask only the feedback, not the icon or its profile badges.
                    Box(Modifier.matchParentSize()
                        .then(if (rippleMask == null) Modifier.clip(CircleShape) else Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                drawImage(rippleMask, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                    blendMode = BlendMode.DstIn)
                            })
                        .indication(interactions, ripple()))
                }
            } }
            if (overflow) {
                val target = with(density) { IntOffset((inset + step * (columns - 1)).roundToPx(),
                    (inset + rowStep * (rows - 1)).roundToPx()) }
                val arrow by animateIntOffsetAsState(target, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "stackArrow")
                val rotation by animateFloatAsState(if (open) 180f else 0f,
                    MaterialTheme.motionScheme.defaultSpatialSpec(), label = "stackChevron")
                IconButton(onClick = { localExpanded = !open; onExpandedChange?.invoke(!open) }, enabled = inputEnabled,
                    modifier = Modifier.offset { arrow }.size(displayedIconSize).testTag("app_stack_toggle:${stack.id}")
                        .semantics { contentDescription = expandLabel }) {
                    LauncherIcon(LauncherSymbol.Chevron, Modifier.graphicsLayer { rotationZ = rotation }, tint = symbolColor)
                }
            }
            if (apps.isEmpty()) IconButton(onClick = onEdit, enabled = inputEnabled,
                modifier = Modifier.align(Alignment.Center).semantics { contentDescription = editLabel }) {
                LauncherIcon(LauncherSymbol.Plus, tint = symbolColor)
            }
        }
    }
}
