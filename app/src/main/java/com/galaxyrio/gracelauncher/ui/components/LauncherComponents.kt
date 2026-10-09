/*
 * P1 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/components/LauncherComponents.kt
 *
 * 裁剪说明（恢复节点见 PORTING_GraceLauncher.md）：
 *   - folders 阶段：恢复 FolderIcon / FolderRow / AppIcon folderId 兜底分支（原版逐字，
 *     Private Space 指示器仍随 P6 Private Space 阶段）
 *   - P6（B6.3）恢复 LauncherRow notification 分支（AppNotification 桥通道就位）：
 *     label 后缀通知时间 + 正文预览两行 + 行尾 Chevron 快捷方式箭头（原版逐字）
 *   - AppIcon 的 launcherAnimationTarget 挂点 P5 恢复（B5.2）
 *   - 保留 monochromeIcon 渲染分支（模型字段已就位，P1 恒 null 自然走原图）
 *   - 保留 AppRowGestures / 横向拖拽识别（P4 弹层只接回调，无需改结构）
 */
package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R // P1：模块 namespace 为 com.homemode4miui，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId
import com.galaxyrio.gracelauncher.data.RecentlyInstalledFolderId
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance

// Retained desktop lists keep their scroll position under full-screen pages,
// but must cancel the long press that opened the page before being unplaced.
internal val LocalLauncherInputEnabled = staticCompositionLocalOf { true }

@Composable
fun AppIcon(app: LauncherApp, modifier: Modifier = Modifier, size: Dp = 38.dp, applyDisplaySize: Boolean = true) {
    val displaySize = size * if (applyDisplaySize) LocalLauncherAppearance.current.iconSize / 100f else 1f
    // P5（B5.2）：恢复原版动画目标挂点 —— 每个应用图标注册 LauncherIconTarget，
    // 启动时 capture 真实渲染 artwork 做 thumbnail scale-up（home 标记由
    // LocalHomeAnimationTarget 提供，仅首页为 true）。
    val iconModifier = if (app.folderId == null) modifier.launcherAnimationTarget(listOf(app.componentName)) else modifier
    Box(iconModifier.size(displaySize)) {
        if (LocalLauncherAppearance.current.themedIcons && app.monochromeIcon != null) {
            Box(Modifier.size(displaySize).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Image(
                    bitmap = app.monochromeIcon,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                    // Adaptive monochrome drawables include the platform's safe-zone inset.
                    modifier = Modifier.size(displaySize).graphicsLayer { scaleX = app.monochromeScale; scaleY = app.monochromeScale },
                )
            }
        } else if (app.icon != null) {
            Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(displaySize))
        } else if (app.folderId != null) {
            // folders 阶段：恢复原版 folderId 兜底（folder 行/收藏文件夹项的占位图标）
            FolderIcon(Modifier, displaySize, when (app.folderId) {
                PrivateSpaceFolderId -> LauncherSymbol.Lock
                RecentlyInstalledFolderId -> LauncherSymbol.History2
                else -> LauncherSymbol.Folder
            })
        } else {
            val colors = listOf(
                Color(0xFF65D5BE), Color(0xFFFFB3A7), Color(0xFFAEC6FF),
                Color(0xFFFFD18A), Color(0xFFD4B7FF),
            )
            val color = remember(app.key) { colors[(app.key.hashCode() and Int.MAX_VALUE) % colors.size] }
            Box(
                modifier = Modifier.size(displaySize).background(color, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.label.firstOrNull()?.uppercase().orEmpty(),
                    color = Color(0xFF13201E),
                    fontSize = (displaySize.value * 0.43f).sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
fun FolderIcon(modifier: Modifier = Modifier, size: Dp = 40.dp, symbol: LauncherSymbol = LauncherSymbol.Folder) {
    Box(
        modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        LauncherIcon(
            symbol,
            modifier = Modifier.size(size * 0.6f),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
fun LauncherAppRow(
    app: LauncherApp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    onSwipeRight: (Rect) -> Unit = {},
    gestures: AppRowGestures = AppRowGestures(),
    highlighted: Boolean = false,
    notification: AppNotification? = null,
    showLabel: Boolean = true,
) {
    LauncherRow(
        rowKey = "app:${app.key}",
        label = app.label,
        onClick = { _, iconBounds -> gestures.onLaunchAt?.invoke(app, iconBounds) ?: onClick() },
        onLongClick = onLongClick,
        onOpen = onSwipeRight,
        onPrepare = { gestures.onPrepare(app) },
        onDrag = { bounds, expanded -> gestures.onDrag(app, bounds, expanded) },
        onDragEnd = gestures.onDragEnd,
        openDescription = stringResource(R.string.app_shortcuts),
        detailsDescription = stringResource(R.string.app_actions),
        modifier = modifier,
        highlighted = highlighted,
        notification = notification.takeIf { app.shortcut == null && !app.isPrivateSpace },
        showLabel = showLabel,
    ) { iconModifier -> AppIcon(app, modifier = iconModifier, size = 40.dp) }
}

/** folders 阶段：恢复原版 FolderRow（LauncherComponents.kt:172-203 逐字）。 */
@Composable
fun FolderRow(
    folder: LauncherFolder,
    onOpen: (Rect) -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDrag: (Rect, Boolean) -> Unit = { _, _ -> },
    onDragEnd: (Boolean) -> Unit = {},
    highlighted: Boolean = false,
    showLabel: Boolean = true,
    app: LauncherApp = folder.asApp(),
    trailing: (@Composable () -> Unit)? = null,
) {
    LauncherRow(
        rowKey = "folder:${folder.id}",
        label = folder.name,
        onClick = { _, iconBounds -> onOpen(iconBounds) },
        onLongClick = onLongClick,
        onOpen = onOpen,
        onPrepare = {},
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        openDescription = stringResource(R.string.open_folder),
        detailsDescription = stringResource(R.string.folder_actions),
        modifier = modifier,
        highlighted = highlighted,
        showLabel = showLabel,
        trailing = trailing,
    ) { iconModifier ->
        AppIcon(app, modifier = iconModifier.launcherAnimationTarget(
            folder.appKeys.mapNotNull(android.content.ComponentName::unflattenFromString), folder = true), size = 40.dp)
    }
}

/** Apps and folders share hit targets, ripple clipping, and one swipe recognizer. */
@Composable
private fun LauncherRow(
    rowKey: String,
    label: String,
    onClick: (rowBounds: Rect, iconBounds: Rect) -> Unit,
    onLongClick: (() -> Unit)?,
    onOpen: (Rect) -> Unit,
    onPrepare: () -> Unit,
    onDrag: (Rect, Boolean) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    openDescription: String,
    detailsDescription: String,
    modifier: Modifier,
    highlighted: Boolean,
    notification: AppNotification? = null,
    showLabel: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    icon: @Composable (Modifier) -> Unit,
) {
    val appearance = LocalLauncherAppearance.current
    val inputEnabled = LocalLauncherInputEnabled.current
    val currentPrepare by rememberUpdatedState(onPrepare)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var iconBounds by remember { mutableStateOf(Rect.Zero) }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val highlight by animateColorAsState(
        // Press feedback comes from Material ripple. Only a selected details
        // row retains a subtle state layer after the pointer has been released.
        if (highlighted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(100), label = "launcherRowPressSurface",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LauncherLayout.RowMinHeight)
            .testTag(rowKey)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .clip(LauncherLayout.RowShape)
            .background(highlight)
            .pointerInput(rowKey, inputEnabled) {
                if (!inputEnabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    currentPrepare()
                }
            }
            .pointerInput(rowKey, inputEnabled) {
                if (!inputEnabled) return@pointerInput
                val swipe = PopupSwipeIntent(reversalSlop = 8.dp.toPx())
                detectHorizontalDragGestures(
                    onDragStart = { swipe.reset() },
                    onDragCancel = {
                        if (swipe.revealed) currentDragEnd(false)
                        swipe.reset()
                    },
                    onDragEnd = {
                        if (swipe.revealed) currentDragEnd(swipe.expanded)
                        swipe.reset()
                    },
                ) { change, amount ->
                    change.consume()
                    swipe.drag(amount)?.let { expanded ->
                        currentDrag(iconBounds, expanded)
                    }
                }
            }
            .semantics {
                // Visual labels may be hidden, but TalkBack must still identify
                // favorites and expose their existing launch/shortcut actions.
                if (!showLabel) contentDescription = label
                selected = pressed || highlighted
                customActions = buildList {
                    add(CustomAccessibilityAction(openDescription) { onOpen(iconBounds); true })
                    if (onLongClick != null) add(CustomAccessibilityAction(detailsDescription) { onLongClick(); true })
                }
            }
            .combinedClickable(
                enabled = inputEnabled,
                interactionSource = interactionSource, indication = ripple(bounded = true, color = appearance.text),
                onClick = { onClick(bounds, iconBounds) },
                onLongClick = onLongClick,
            )
            .padding(LauncherLayout.ContentInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(Modifier.testTag("$rowKey:icon").onGloballyPositioned { iconBounds = it.boundsInWindow() })
        Spacer(Modifier.width(LauncherLayout.IconLabelGap))
        Column(Modifier.weight(1f)) {
            if (showLabel) Text(
                text = label + (notification?.let { " · ${notificationAge(it.postedAt)}" } ?: ""),
                modifier = Modifier.testTag("$rowKey:label"),
                style = MaterialTheme.typography.bodyLarge.merge(
                    TextStyle(fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp, shadow = appearance.textShadow),
                ),
                color = appearance.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (notification != null) {
                if (showLabel) Spacer(Modifier.height(2.dp))
                listOf(notification.title, notification.text.replace('\n', ' ')).filter { it.isNotBlank() }.forEach { line ->
                    Text(line, color = appearance.text.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = appearance.textShadow),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        trailing?.invoke()
        if (notification != null) {
            IconButton(
                onClick = { onPrepare(); onOpen(iconBounds) },
                enabled = inputEnabled,
                modifier = Modifier.size(48.dp).testTag("notification_arrow:$rowKey")
                    .semantics { contentDescription = openDescription },
            ) {
                LauncherIcon(LauncherSymbol.Chevron,
                    Modifier.rotate(if (LocalLayoutDirection.current == LayoutDirection.Ltr) -90f else 90f),
                    tint = appearance.text)
            }
        }
    }
}

data class AppRowGestures(
    val onPrepare: (LauncherApp) -> Unit = {},
    val onDrag: (LauncherApp, Rect, Boolean) -> Unit = { _, _, _ -> },
    val onDragEnd: (Boolean) -> Unit = {},
    val onLaunchAt: ((LauncherApp, Rect) -> Unit)? = null,
)
