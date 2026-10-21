/*
 * 移植自 GraceLauncher v1.1.8（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 上游同步 2026-10-11（PORTING_GraceLauncher.md §9）：按上游 v1.1.8 逐字更新。
 */
package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.IconDisplaySettings
import com.galaxyrio.gracelauncher.data.IconDisplayStyle
import com.galaxyrio.gracelauncher.data.IconShape
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.PrivateSpaceFolderId
import com.galaxyrio.gracelauncher.data.ShortcutsFoldersSettings
import com.galaxyrio.gracelauncher.data.WorkProfileFolderId
import com.galaxyrio.gracelauncher.data.RecentlyInstalledFolderId
import com.galaxyrio.gracelauncher.data.icons.iconShapePath
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.effectiveDisplay

// Retained desktop lists keep their scroll position under full-screen pages,
// but must cancel the long press that opened the page before being unplaced.
internal val LocalLauncherInputEnabled = staticCompositionLocalOf { true }
// 上游同步 1.2.0：弹层滑动手势灵敏度（Lab 可调）经此下发到列表行，
// 避免把 ShortcutsFoldersSettings 逐层透传到每个 LauncherRow。
internal val LocalPopupGestureSettings = staticCompositionLocalOf { ShortcutsFoldersSettings() }

private val FallbackIconColors = listOf(
    Color(0xFF65D5BE), Color(0xFFFFB3A7), Color(0xFFAEC6FF),
    Color(0xFFFFD18A), Color(0xFFD4B7FF),
)

private fun fallbackIconColor(key: String): Color =
    FallbackIconColors[(key.hashCode() and Int.MAX_VALUE) % FallbackIconColors.size]

@Composable
internal fun IconBadge(app: LauncherApp, badgeSize: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(badgeSize).testTag("${if (app.isWorkProfile) "work" else "private"}_indicator:${app.key}")
        .background(MaterialTheme.colorScheme.surface, CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
        LauncherIcon(if (app.isWorkProfile) LauncherSymbol.Work else LauncherSymbol.Encrypted,
            Modifier.size(badgeSize * 0.68f), MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AppIcon(app: LauncherApp, modifier: Modifier = Modifier, size: Dp = 38.dp, applyDisplaySize: Boolean = true,
    display: IconDisplaySettings = IconDisplaySettings()) {
    val appearance = LocalLauncherAppearance.current
    val displaySize = size * if (applyDisplaySize) appearance.iconSize / 100f else 1f
    val style = display.style
    val iconModifier = if (app.folderId == null) modifier.launcherAnimationTarget(listOf(app.componentName)) else modifier
    val showBadge = (app.isPrivateSpace && app.showPrivateIndicator) || (app.isWorkProfile && app.showWorkIndicator)
    Box(iconModifier.size(displaySize)) {
        when {
            style == IconDisplayStyle.Hidden -> Unit
            style == IconDisplayStyle.Dot -> {
                val dotSize = (display.dotSize * if (applyDisplaySize) appearance.iconSize / 100f else 1f).dp
                val dotShape = remember(display.dotShape, display.dotCookieSides,
                    display.dotPebbleRoundness, display.dotSquareCornerRadius, display.dotSquareAspectRatio) {
                    if (display.dotShape == IconShape.Circle || display.dotShape == IconShape.None) CircleShape
                    else GenericShape { bounds, _ ->
                        addPath(iconShapePath(display.dotShape, display.dotCookieSides, bounds.minDimension,
                            display.dotPebbleRoundness, display.dotSquareCornerRadius, display.dotSquareAspectRatio).asComposePath())
                    }
                }
                // Decorative only; rows keep their own label semantics.
                val dotColor = Color(display.color.resolve(MaterialTheme.colorScheme.primary.toArgb(), app.originalIconColor))
                Box(Modifier.align(Alignment.Center).size(dotSize)) {
                    Box(Modifier.fillMaxSize().background(dotColor, dotShape))
                    if (showBadge) IconBadge(app, (dotSize * 0.55f).coerceAtLeast(9.dp), Modifier.align(Alignment.BottomEnd))
                }
            }
            appearance.themedIcons && app.monochromeIcon != null -> {
                Box(Modifier.size(displaySize).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = app.monochromeIcon,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer),
                        // Adaptive monochrome drawables include the platform's safe-zone inset.
                        modifier = Modifier.size(displaySize).graphicsLayer { scaleX = app.monochromeScale; scaleY = app.monochromeScale },
                    )
                }
            }
            app.icon != null -> {
                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(displaySize))
            }
            app.folderId != null -> {
                FolderIcon(Modifier, displaySize, when (app.folderId) {
                    PrivateSpaceFolderId -> LauncherSymbol.Lock
                    WorkProfileFolderId -> LauncherSymbol.Work
                    RecentlyInstalledFolderId -> LauncherSymbol.History2
                    else -> if (app.isAppStack) LauncherSymbol.Steppers else LauncherSymbol.Folder
                })
            }
            else -> {
                val color = remember(app.key) { fallbackIconColor(app.key) }
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
        if (showBadge && style != IconDisplayStyle.Dot && style != IconDisplayStyle.Hidden) {
            IconBadge(app, displaySize * 0.42f, Modifier.align(Alignment.BottomEnd))
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
    val display = app.effectiveDisplay(LocalLauncherAppearance.current)
    LauncherRow(
        rowKey = "app:${app.key}",
        label = app.label,
        onClick = { _, iconBounds -> gestures.onLaunchAt?.invoke(app, iconBounds) ?: onClick() },
        onLongClick = onLongClick,
        onOpen = onSwipeRight,
        onPrepare = { gestures.onPrepare(app) },
        onDrag = { bounds, expanded -> gestures.onDrag(app, bounds, expanded) },
        onDragEnd = gestures.onDragEnd,
        popupsEnabled = gestures.popupsEnabled,
        onSwipeLeft = gestures.onOpenFirst?.let { open -> { bounds -> open(app, bounds) } },
        openDescription = stringResource(R.string.app_shortcuts),
        detailsDescription = stringResource(R.string.app_actions),
        modifier = modifier,
        highlighted = highlighted,
        notification = notification.takeIf { app.shortcut == null && app.user == null },
        showLabel = showLabel,
        animationComponents = listOf(app.componentName),
        hiddenIcon = display.style == IconDisplayStyle.Hidden,
    ) { iconModifier -> AppIcon(app, modifier = iconModifier, size = 40.dp, display = display) }
}

@Composable
fun FolderRow(
    folder: LauncherFolder,
    onOpen: (Rect) -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onDrag: (Rect, Boolean) -> Unit = { _, _ -> },
    onDragEnd: (Boolean) -> Unit = {},
    onSwipeLeft: ((Rect) -> Unit)? = null,
    highlighted: Boolean = false,
    showLabel: Boolean = true,
    app: LauncherApp = folder.asApp(),
    trailing: (@Composable () -> Unit)? = null,
) {
    val display = app.effectiveDisplay(LocalLauncherAppearance.current)
    LauncherRow(
        rowKey = "folder:${folder.id}",
        label = folder.name,
        onClick = { _, iconBounds -> onOpen(iconBounds) },
        onLongClick = onLongClick,
        onOpen = onOpen,
        onPrepare = {},
        onDrag = onDrag,
        onDragEnd = onDragEnd,
        onSwipeLeft = onSwipeLeft,
        openDescription = stringResource(R.string.open_folder),
        detailsDescription = stringResource(R.string.folder_actions),
        modifier = modifier,
        highlighted = highlighted,
        showLabel = showLabel,
        trailing = trailing,
        animationComponents = folder.appKeys.mapNotNull(android.content.ComponentName::unflattenFromString),
        animationFolder = true,
        hiddenIcon = display.style == IconDisplayStyle.Hidden,
    ) { iconModifier ->
        AppIcon(app, modifier = iconModifier.launcherAnimationTarget(
            folder.appKeys.mapNotNull(android.content.ComponentName::unflattenFromString), folder = true),
            size = 40.dp, display = display)
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
    popupsEnabled: Boolean = true,
    onSwipeLeft: ((Rect) -> Unit)? = null,
    notification: AppNotification? = null,
    showLabel: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    animationComponents: List<android.content.ComponentName> = emptyList(),
    animationFolder: Boolean = false,
    hiddenIcon: Boolean? = null,
    icon: @Composable (Modifier) -> Unit,
) {
    val appearance = LocalLauncherAppearance.current
    val inputEnabled = LocalLauncherInputEnabled.current
    // 上游同步 1.2.0：弹层滑动灵敏度（Lab 距离/角度）由 GraceLauncherRoute 提供
    val popupSettings = LocalPopupGestureSettings.current
    val list = LocalListAppearance.current
    val labelStyle = listLabelStyle()
    val openFirstDescription = stringResource(R.string.popup_swipe_left_first)
    val currentPrepare by rememberUpdatedState(onPrepare)
    val currentDrag by rememberUpdatedState(onDrag)
    val currentDragEnd by rememberUpdatedState(onDragEnd)
    val currentSwipeLeft by rememberUpdatedState(onSwipeLeft)
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var iconCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // Query at activation time: a graphics-layer translation need not trigger layout.
    fun iconBounds(): Rect = iconCoordinates?.takeIf { it.isAttached }?.boundsInWindow() ?: Rect.Zero
    var dragging by remember(rowKey) { mutableStateOf(false) }
    var leftPull by remember(rowKey) { mutableFloatStateOf(0f) }
    val resistanceDistance = with(LocalDensity.current) { 72.dp.toPx() }
    val pullOffset by animateFloatAsState(
        targetValue = resistedPopupPull(leftPull, resistanceDistance),
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.58f, stiffness = 420f),
        label = "launcherRowPull",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // A row whose icon is hidden (global or per-app) must keep its label —
    // otherwise hideFavoriteNames would leave an invisible, tappable app.
    val hideIcon = hiddenIcon ?: (appearance.iconDisplay.style == IconDisplayStyle.Hidden)
    val labelVisible = showLabel || hideIcon
    val highlight by animateColorAsState(
        // Keep the long-press state layer while a left action is being pulled.
        if (highlighted || leftPull > 0f) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f) else Color.Transparent,
        animationSpec = tween(100), label = "launcherRowPressSurface",
    )
    // The hit area stays still; only the surface below follows the finger.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(rowKey)
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .pointerInput(rowKey, inputEnabled, popupsEnabled) {
                if (!inputEnabled || !popupsEnabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    currentPrepare()
                }
            }
            .pointerInput(rowKey, inputEnabled, popupsEnabled, onSwipeLeft != null,
                popupSettings.popupSwipeDistanceDp, popupSettings.popupSwipeAngleDegrees) {
                if (!inputEnabled || !popupsEnabled) return@pointerInput
                val swipe = PopupSwipeIntent(reversalSlop = 8.dp.toPx(),
                    firstItemSlop = if (currentSwipeLeft != null) 40.dp.toPx() else Float.POSITIVE_INFINITY)
                fun resetPull() { dragging = false; leftPull = 0f }
                try {
                    // 上游同步 1.2.0：Pop-up 触发灵敏度 ——
                    // extraDistance（Lab 0..48dp，默认 8dp）在 touchSlop 之外额外要求的手指行程，
                    // maxAngleDegrees（Lab 10..90°，默认 45°）拒绝偏角过大的斜滑（交还给列表滚动）。
                    detectPopupDragGestures(
                        extraDistance = popupSettings.popupSwipeDistanceDp.coerceIn(0, 48).dp.toPx(),
                        maxAngleDegrees = popupSettings.popupSwipeAngleDegrees.coerceIn(10, 90),
                        leftEnabled = currentSwipeLeft != null,
                        onDragStart = { swipe.reset(); leftPull = 0f; dragging = true },
                        onDragCancel = {
                            if (swipe.revealed) currentDragEnd(false)
                            swipe.reset()
                            resetPull()
                        },
                        onDragEnd = {
                            if (swipe.revealed) currentDragEnd(swipe.expanded)
                            else if (swipe.opensFirst) currentSwipeLeft?.invoke(iconBounds())
                            swipe.reset()
                            resetPull()
                        },
                    ) { change, amount ->
                        change.consume()
                        swipe.drag(amount)?.let { expanded ->
                            currentDrag(iconBounds(), expanded)
                        }
                        leftPull = swipe.firstItemPullDistance
                    }
                } finally {
                    // Covering the page or disabling the gesture must never leave a row displaced.
                    resetPull()
                }
            }
            .semantics {
                // Visual labels may be hidden, but TalkBack must still identify
                // favorites and expose their existing launch/shortcut actions.
                if (!labelVisible) contentDescription = label
                selected = pressed || highlighted
                customActions = buildList {
                    if (popupsEnabled) add(CustomAccessibilityAction(openDescription) { onOpen(iconBounds()); true })
                    if (popupsEnabled && onSwipeLeft != null) add(CustomAccessibilityAction(openFirstDescription) { onSwipeLeft(iconBounds()); true })
                    if (onLongClick != null) add(CustomAccessibilityAction(detailsDescription) { onLongClick(); true })
                }
            }
            .combinedClickable(
                enabled = inputEnabled,
                interactionSource = interactionSource, indication = null,
                onClick = { onClick(bounds, iconBounds()) },
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .graphicsLayer { translationX = pullOffset }
                .testTag("$rowKey:surface")
                .clip(LauncherLayout.RowShape)
                .background(highlight)
                .indication(interactionSource, ripple(bounded = true, color = appearance.text))
                .padding(horizontal = LauncherLayout.ContentInset, vertical = (list.appSpacing / 2f).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!hideIcon) {
                icon(Modifier.testTag("$rowKey:icon").onGloballyPositioned { iconCoordinates = it })
                Spacer(Modifier.width(list.iconNameGap.dp))
            }
            Column(Modifier.weight(1f).then(
                // Hidden mode drops the icon slot, so the label cell keeps the
                // popup anchor and the launch/return animation target alive.
                if (hideIcon) Modifier
                    .onGloballyPositioned { iconCoordinates = it }
                    .then(if (animationComponents.isNotEmpty())
                        Modifier.launcherAnimationTarget(animationComponents, animationFolder) else Modifier)
                else Modifier)) {
                if (labelVisible) Text(
                    text = label + (notification?.let { " · ${notificationAge(it.postedAt)}" } ?: ""),
                    modifier = Modifier.testTag("$rowKey:label"),
                    style = labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (notification != null) {
                    if (labelVisible) Spacer(Modifier.height(2.dp))
                    listOf(notification.title, notification.text.replace('\n', ' ')).filter { it.isNotBlank() }.forEach { line ->
                        Text(line, color = labelStyle.color.copy(alpha = 0.88f),
                            style = labelStyle.copy(fontSize = (list.fontSize - 2).sp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            trailing?.invoke()
            if (notification != null) {
                IconButton(
                    onClick = { onPrepare(); onOpen(iconBounds()) },
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
}

data class AppRowGestures(
    val onPrepare: (LauncherApp) -> Unit = {},
    val onDrag: (LauncherApp, Rect, Boolean) -> Unit = { _, _, _ -> },
    val onDragEnd: (Boolean) -> Unit = {},
    val onLaunchAt: ((LauncherApp, Rect) -> Unit)? = null,
    val popupsEnabled: Boolean = true,
    val onOpenFirst: ((LauncherApp, Rect) -> Unit)? = null,
)
