/*
 * P4 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/overlays/ShortcutPopup.kt
 *
 * 保留：ShortcutRevealState / ShortcutPopup 主体 / SwipeRevealPanel（同窗口 morph 弹层）
 *   / PopupContainerShape / PopupContentClip，morph 几何在 PopupMorphGeometry.kt。
 *
 * 裁剪说明（相对原版）：
 *   - 删 FolderPopup（文件夹体系 P6+）
 *   - 删 notifications / NotificationPopupItem（P6 通知通道）
 *   - 删 Private Space 分支（P6）
 *   - 删弹层 widget 分支（entry.widget / EditPopup 编辑器，依赖 LauncherItemsRepository，
 *     随 C 组设置页落地）
 *   - LauncherActions → GracePopupActions（ui/GracePopupActions.kt，P4 子集）
 *   - entries = 快捷方式 + menuEntries 附加行（P4 适配：收藏增删入口从 P1/P2 临时
 *     长按菜单迁入弹层，保持零功能回退）
 *   - 同窗口实现（Layout 自绘，非 Popup window）→ LocalContext 模块覆盖正常穿透，
 *     stringResource 可用（hotfix2 规则仅适用于 Popup/Dialog 独立窗口子树）
 */
package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.contentDescription
import com.galaxyrio.gracelauncher.ui.components.LocalListAppearance
import com.galaxyrio.gracelauncher.ui.components.listLabelStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.homemode4miui.R // P4：模块 namespace，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.ShortcutResult
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import com.galaxyrio.gracelauncher.ui.GracePopupActions
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Stable
class ShortcutRevealState(expanded: Boolean = true, dragging: Boolean = false, val animationsEnabled: Boolean = true) {
    var dragging by mutableStateOf(dragging)
    var expanded by mutableStateOf(expanded)
}

/** P4 适配：弹层尾部附加动作行（收藏增删等）。label 必须主树解析后传入。 */
data class PopupEntry(val key: String, val label: String, val symbol: LauncherSymbol = LauncherSymbol.Star, val onClick: () -> Unit)/** Same-window overlay: adding a popup window during DOWN would cancel the row's drag. */
@Composable
fun ShortcutPopup(
    app: LauncherApp,
    anchor: Rect,
    hasAccess: Boolean,
    actions: GracePopupActions,
    onLaunchApp: () -> Unit,
    onDismiss: () -> Unit,
    reveal: ShortcutRevealState = remember { ShortcutRevealState() },
    hapticsEnabled: Boolean = true,
    // 上游同步 v1.1.8 S8b（v1.1.7）：行文字可见性/样式跟随列表外观
    showNames: Boolean = true,
    menuEntries: List<PopupEntry> = emptyList(),
    onDetails: (LauncherApp) -> Unit = {},
) {
    var retry by remember { mutableIntStateOf(0) }
    val loadShortcuts by rememberUpdatedState(actions.shortcuts)
    val initialResult = remember(app.key, hasAccess) {
        actions.cachedShortcuts(app) ?: ShortcutResult(ShortcutStatus.Loading)
    }
    val result by produceState(initialResult, app.key, hasAccess, retry) {
        // Keep cached content while refreshing; never insert a progress indicator.
        value = loadShortcuts(app)
    }
    // 原版经 uiState.findItem 复用已存条目；本模块 shortcut 不入应用列表，直接 asApp 包装。
    // 上游同步 v1.1.8 S8b：行样式跟随列表外观（字号/字体/名字可见性/间距）
    val listAppearance = LocalListAppearance.current
    val labelStyle = listLabelStyle(onWallpaper = false)
    val shortcuts = result.shortcuts.map { it.asApp(app) }
    SwipeRevealPanel(
        anchor = anchor,
        reveal = reveal,
        panelTag = "shortcut_popup",
        title = stringResource(R.string.app_shortcuts),
        onDismiss = onDismiss,
    ) { maxListHeight ->
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("shortcut_header")
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onLongClick = { onDetails(app) }, onClick = onLaunchApp,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherIcon(LauncherSymbol.Outbound, Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(app.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.heightIn(max = maxListHeight).testTag("shortcut_list")) {
            if (!hasAccess) item {
                // 模块 hook 宿主桌面（本身即默认启动器），无访问时仅提示。
                Text(stringResource(R.string.shortcut_permission), Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            else when (result.status) {
                // Cold apps may need one binder query; keep the layout quiet.
                ShortcutStatus.Loading -> item { Spacer(Modifier.height(56.dp)) }
                ShortcutStatus.DefaultLauncherRequired -> item {
                    // 中继降级状态，无"设为默认"动作可给，只提示。
                    Text(stringResource(R.string.shortcut_permission), Modifier.padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                ShortcutStatus.Error -> item {
                    Column {
                    Text(stringResource(R.string.shortcut_error), Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                    }
                }
                ShortcutStatus.Ready -> {
                    if (result.shortcuts.isEmpty() && menuEntries.isEmpty()) item {
                        Text(stringResource(R.string.no_shortcuts), Modifier.padding(horizontal = 12.dp, vertical = 18.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            items(shortcuts, key = { "shortcut:${it.key}" }) { itemApp ->
                    var iconBounds by remember { mutableStateOf(Rect.Zero) }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("shortcut:${itemApp.shortcut?.id ?: itemApp.key}")
                            .clip(RoundedCornerShape(16.dp))
                            .combinedClickable(enabled = !reveal.dragging && reveal.expanded, onLongClick = { onDetails(itemApp) }, onClick = {
                                itemApp.shortcut?.let { shortcut ->
                                    actions.launchShortcutAt?.invoke(shortcut, iconBounds) ?: actions.launchShortcut(shortcut)
                                }
                                onDismiss()
                            }).padding(horizontal = 12.dp, vertical = (listAppearance.appSpacing / 2f).dp)
                            // 上游同步 v1.1.8 S8b：名字隐藏时进无障碍描述
                            .semantics { if (!showNames) contentDescription = itemApp.label },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(itemApp, Modifier.onGloballyPositioned { iconBounds = it.boundsInWindow() }, size = 36.dp)
                        if (showNames) {
                            Spacer(Modifier.width(listAppearance.iconNameGap.dp))
                            Text(itemApp.label, style = labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
            }
            items(menuEntries, key = { "entry:${it.key}" }) { entry ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("entry:${entry.key}")
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(enabled = !reveal.dragging && reveal.expanded, onClick = {
                            entry.onClick()
                            onDismiss()
                        }).padding(horizontal = 12.dp, vertical = (listAppearance.appSpacing / 2f).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LauncherIcon(entry.symbol, Modifier.size(23.dp))
                    Spacer(Modifier.width(22.dp))
                    Text(entry.label, style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One same-window reveal/positioning implementation for app shortcuts and folders. */
@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal fun SwipeRevealPanel(
    anchor: Rect,
    reveal: ShortcutRevealState,
    panelTag: String,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.(maxListHeight: Dp) -> Unit,
) {
    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val margin = with(density) { 32.dp.roundToPx() }
    val maxListHeight = with(density) { windowSize.height.toDp() } * 0.55f
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // 上游同步 v1.1.8 S8b：动画关闭 = snapTo 瞬时（GraceButton instant 语义）；触觉反馈随展开/收起
    val spatial = remember(reveal) { Animatable(if (!reveal.animationsEnabled && reveal.expanded) 1f else 0f) }
    val effects = remember(reveal) { Animatable(spatial.value) }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    var feedbackExpanded by remember(reveal) { mutableStateOf(false) }
    LaunchedEffect(reveal) {
        snapshotFlow { reveal.expanded }.collect { expanded ->
            if (expanded != feedbackExpanded) {
                haptics.performHapticFeedback(if (expanded) HapticFeedbackType.GestureThresholdActivate else HapticFeedbackType.GestureEnd)
                feedbackExpanded = expanded
            }
        }
    }
    androidx.compose.runtime.DisposableEffect(reveal) {
        // Back、启动条目、打开详情会直接移除面板——滑动/点按关闭的反馈已在上面发过。
        onDispose { if (feedbackExpanded) haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) }
    }
    LaunchedEffect(reveal, spatialSpec, effectsSpec) {
        snapshotFlow { reveal.expanded }.collect { expanded ->
            val destination = if (expanded) 1f else 0f
            // Let Animatable interrupt its previous animateTo. Cancelling the
            // owning effect on every target change would reset its velocity.
            launch { if (reveal.animationsEnabled) effects.animateTo(destination, effectsSpec) else effects.snapTo(destination) }
            launch { if (reveal.animationsEnabled) spatial.animateTo(destination, spatialSpec) else spatial.snapTo(destination) }
        }
    }
    val finishedClosing = !reveal.dragging && !reveal.expanded && !spatial.isRunning && spatial.value == 0f
    LaunchedEffect(reveal, finishedClosing) {
        // Keep the original gesture alive at zero so a rightward reversal can
        // reopen the same panel without lifting the finger.
        if (finishedClosing) currentOnDismiss()
    }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.boundsInWindow().topLeft }) {
        Box(Modifier.matchParentSize().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            onClick = { reveal.expanded = false },
        ).clearAndSetSemantics {})
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                LauncherPanelTheme {
                    Surface(
                        modifier = Modifier.testTag(panelTag).semantics {
                            paneTitle = title
                            dismiss { reveal.expanded = false; true }
                            progressBarRangeInfo = ProgressBarRangeInfo(spatial.value.coerceIn(0f, 1f), 0f..1f)
                        }.graphicsLayer { alpha = (spatial.value * 12f).coerceIn(0f, 1f) },
                        shape = PopupContainerShape(anchor, spatial.value),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 16.dp * spatial.value.coerceIn(0f, 1f),
                    ) {}
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        Column(Modifier.padding(horizontal = 6.dp, vertical = 12.dp)) {
                            content(maxListHeight)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val panelWidth = (constraints.maxWidth - margin * 2).coerceIn(1, 440.dp.roundToPx())
            // Measure content at its final size: only the container/clip morphs,
            // so neither text nor icons are stretched during the hero transition.
            val panel = measurables[1].measure(Constraints(
                minWidth = panelWidth, maxWidth = panelWidth,
                maxHeight = (constraints.maxHeight - margin * 2).coerceAtLeast(1),
            ))
            val left = (constraints.maxWidth - panel.width) / 2
            val top = (anchor.center.y - origin.y - panel.height / 2f).roundToInt()
                .coerceIn(margin, (constraints.maxHeight - panel.height - margin).coerceAtLeast(margin))
            val target = Rect(left.toFloat(), top.toFloat(), (left + panel.width).toFloat(), (top + panel.height).toFloat())
            val frame = popupMorphFrame(anchor.translate(-origin), target, spatial.value, 24.dp.toPx())
            val container = measurables[0].measure(Constraints.fixed(
                frame.bounds.width.roundToInt().coerceAtLeast(1), frame.bounds.height.roundToInt().coerceAtLeast(1),
            ))
            layout(constraints.maxWidth, constraints.maxHeight) {
                container.place(frame.bounds.left.roundToInt(), frame.bounds.top.roundToInt())
                panel.placeWithLayer(left, top) {
                    shape = PopupContentClip(frame.bounds.translate(-target.topLeft), frame.radius)
                    clip = true
                    alpha = effects.value * ((spatial.value - 0.15f) / 0.85f).coerceIn(0f, 1f)
                }
            }
        }
    }
}

/** The source is always circular, regardless of the actual icon pack's outline. */
private class PopupContainerShape(private val anchor: Rect, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val start = minOf(anchor.width, anchor.height).coerceAtLeast(1f) / 2f
        val radius = androidx.compose.ui.util.lerp(start, with(density) { 24.dp.toPx() }, progress.coerceAtLeast(0f))
            .coerceIn(0f, size.minDimension / 2f)
        return Outline.Rounded(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius)))
    }
}

private class PopupContentClip(private val bounds: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(bounds, CornerRadius(radius)))
}
