@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.GraceButtonAlignment
import com.galaxyrio.gracelauncher.data.GraceButtonLayout
import com.galaxyrio.gracelauncher.data.GraceButtonVisibility
import com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.GraceButtonArtwork
import com.galaxyrio.gracelauncher.ui.components.stableStatusBarInset
import kotlin.math.roundToInt

@Composable
internal fun GraceButtonPositionSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val saved = uiState.settings.graceButton.layout.normalized()
    var draft by remember(saved) { mutableStateOf(saved) }
    val defaults = remember { GraceButtonLayout() }
    val enabled = LocalSettingsStorageState.current.canEdit
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    val viewportLeft = with(density) { insets.getLeft(this, direction).toDp().value }
    val viewportWidth = with(density) {
        (window.width - insets.getLeft(this, direction) - insets.getRight(this, direction)).toDp().value.coerceAtLeast(1f)
    }
    val viewportHeight = with(density) { window.height.toDp().value.coerceAtLeast(1f) }
    val topInset = stableStatusBarInset().value
    val bottomInset = insets.asPaddingValues().calculateBottomPadding().value
    fun commit(value: GraceButtonLayout = draft) {
        if (!enabled) return
        val normalized = value.normalized()
        draft = normalized.copy(bottomMargin = normalized.bottomMargin.coerceAtMost(
            normalized.maxBottomMargin(viewportHeight, topInset, bottomInset)))
        val placement = draft
        actions.updateSettings { it.copy(graceButton = it.graceButton.copy(layout = placement)) }
    }
    SettingsScaffold(stringResource(R.string.grace_button_position_size), "grace_button_position_page", onBack,
        fixedCollapsed = true) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
          // A roughly 2:1 window onto the desktop; only the mask changes, never the button scale.
          val previewHeight = ((maxWidth - 32.dp) / 2f).coerceIn(0.dp, maxHeight / 2f)
          Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("grace_button_position_controls"),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                item {
                    DesignerSegment(0, 4, "grace_button_alignment") {
                        Text(stringResource(R.string.grace_button_alignment))
                        GraceButtonChoices(GraceButtonAlignment.entries, draft.alignment, enabled, "grace_button_alignment",
                            label = { stringResource(when (it) {
                                GraceButtonAlignment.Left -> R.string.grace_button_align_left
                                GraceButtonAlignment.Center -> R.string.grace_button_align_center
                                GraceButtonAlignment.Right -> R.string.grace_button_align_right
                            }) }, onSelect = { commit(draft.copy(alignment = it)) })
                    }
                }
                item {
                    DesignerSegment(1, 4, "grace_button_show_on") {
                        Text(stringResource(R.string.grace_button_show_on))
                        GraceButtonChoices(GraceButtonVisibility.entries, draft.showOn, enabled, "grace_button_show_on",
                            label = { stringResource(when (it) {
                                GraceButtonVisibility.Favorites -> R.string.settings_folder_favorites
                                GraceButtonVisibility.AppList -> R.string.settings_folder_app_list
                                GraceButtonVisibility.Both -> R.string.grace_button_show_both
                            }) }, onSelect = { commit(draft.copy(showOn = it)) })
                    }
                }
                item {
                    DesignerSegment(2, 4, "grace_button_distances") {
                        val sideLimit = (viewportWidth - draft.size).coerceIn(1f, 2000f)
                        DesignerSlider(stringResource(R.string.grace_button_side_distance), draft.sideMargin.coerceAtMost(sideLimit),
                            defaults.sideMargin.coerceAtMost(sideLimit), 0f..sideLimit,
                            enabled && draft.alignment != GraceButtonAlignment.Center, "grace_button_side_distance", suffix = " dp",
                            onFinished = { commit() }, onReset = { commit(draft.copy(sideMargin = defaults.sideMargin)) }) {
                            draft = draft.copy(sideMargin = it)
                        }
                        val bottomLimit = draft.maxBottomMargin(viewportHeight, topInset, bottomInset)
                        DesignerSlider(stringResource(R.string.grace_button_bottom_distance), draft.bottomMargin.coerceAtMost(bottomLimit),
                            defaults.bottomMargin.coerceAtMost(bottomLimit), 0f..bottomLimit, enabled && bottomLimit > 0f, "grace_button_bottom_distance", suffix = " dp",
                            onFinished = { commit() }, onReset = { commit(draft.copy(bottomMargin = defaults.bottomMargin)) }) {
                            draft = draft.copy(bottomMargin = it)
                        }
                    }
                }
                item {
                    DesignerSegment(3, 4, "grace_button_size_control") {
                        DesignerSlider(stringResource(R.string.grace_button_size), draft.size, defaults.size, 32f..120f,
                            enabled, "grace_button_size", suffix = " dp", onFinished = { commit() },
                            onReset = { commit(draft.copy(size = defaults.size)) }) { draft = draft.copy(size = it) }
                    }
                }
                item {
                    Text(stringResource(R.string.grace_button_position_hint), Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GraceButtonPositionPreview(uiState, draft, viewportLeft, viewportWidth, viewportHeight, topInset, bottomInset, enabled,
                onCommit = { commit(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 12.dp + LocalSettingsBottomInset.current).height(previewHeight))
          }
        }
    }
}

@Composable
private fun <T : Enum<T>> GraceButtonChoices(options: List<T>, selected: T, enabled: Boolean, tag: String,
    label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            ToggleButton(checked = selected == option, onCheckedChange = { onSelect(option) }, enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                    .testTag("$tag:${option.name}").semantics { role = Role.RadioButton },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
                Text(label(option))
            }
        }
    }
}

/** A window onto the real lower half: margins mask the desktop instead of moving or scaling it. */
@Composable
private fun GraceButtonPositionPreview(uiState: LauncherUiState, layout: GraceButtonLayout,
    viewportLeft: Float, viewportWidth: Float, viewportHeight: Float, topInset: Float, bottomInset: Float, enabled: Boolean,
    onCommit: (GraceButtonLayout) -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(layout)
    val commit by rememberUpdatedState(onCommit)
    // Follow the finger in placement only. Controls and persistence update once
    // on release, rather than rebuilding the page at pointer-event frequency.
    var dragLayout by remember { mutableStateOf<GraceButtonLayout?>(null) }
    val density = LocalDensity.current
    val shape = ListItemDefaults.segmentedShapes(0, 1).shape
    val description = stringResource(R.string.grace_button_position_hint)
    val artwork = uiState.graceButtonApp?.icon.takeIf { GraceButtonIcon.key in uiState.itemIcons }
    val design = GraceButtonIcon.choice(uiState.itemIcons[GraceButtonIcon.key]).design ?: GraceButtonIcon.defaults
    var previewOrigin by remember { mutableStateOf<Offset?>(null) }
    val origin by rememberUpdatedState(previewOrigin)
    val pixelsPerDp = density.density
    val bounds = layout.bounds(viewportWidth, viewportHeight, topInset, bottomInset)
    Box(modifier
            .testTag("grace_button_position_preview").semantics { contentDescription = description }
            .onGloballyPositioned { coordinates ->
                previewOrigin = coordinates.positionInWindow() / pixelsPerDp
            }
            .drawWithContent {
                drawOutline(shape.createOutline(size, layoutDirection, this), Color.Transparent, blendMode = BlendMode.Clear)
                drawContent()
            }.clip(shape)
            .pointerInput(viewportLeft, viewportWidth, viewportHeight, topInset, bottomInset, pixelsPerDp, enabled) {
                if (!enabled) return@pointerInput
                var dragging = false
                var position = Offset.Zero
                try { detectDragGestures(
                    onDragStart = { point ->
                        val preview = origin ?: return@detectDragGestures
                        val current = latest.bounds(viewportWidth, viewportHeight, topInset, bottomInset)
                        val left = (viewportLeft + current.left - preview.x) * pixelsPerDp
                        val top = (current.top - preview.y) * pixelsPerDp
                        val hit = Rect(left, top, left + current.size * pixelsPerDp, top + current.size * pixelsPerDp)
                        dragging = hit.inflate(24.dp.toPx()).contains(point)
                        position = Offset(current.left, current.top)
                        dragLayout = latest.takeIf { dragging }
                    },
                    onDragEnd = { if (dragging) dragLayout?.let(commit); dragging = false; dragLayout = null },
                    onDragCancel = { dragging = false; dragLayout = null },
                ) { event, amount ->
                    if (dragging) {
                        event.consume()
                        val current = dragLayout ?: latest
                        val side = current.bounds(viewportWidth, viewportHeight, topInset, bottomInset).size
                        val moved = position + amount / pixelsPerDp
                        val lowestTop = (viewportHeight - bottomInset - side).coerceAtLeast(topInset)
                        val highestTop = (lowestTop - current.maxBottomMargin(viewportHeight, topInset, bottomInset)).coerceAtLeast(topInset)
                        position = Offset(moved.x.coerceIn(0f, (viewportWidth - side).coerceAtLeast(0f)),
                            moved.y.coerceIn(highestTop, lowestTop))
                        dragLayout = current.movedTo(position.x, position.y, viewportWidth, viewportHeight, topInset, bottomInset)
                    }
                } } finally { dragLayout = null }
            }) {
        previewOrigin?.let { preview ->
            GraceButtonArtwork(artwork, design, bounds.size.dp,
                modifier = Modifier.align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset {
                        val current = (dragLayout ?: layout).bounds(viewportWidth, viewportHeight, topInset, bottomInset)
                        IntOffset(((viewportLeft + current.left - preview.x) * pixelsPerDp).roundToInt(),
                            ((current.top - preview.y) * pixelsPerDp).roundToInt())
                    }
                    .wrapContentSize(AbsoluteAlignment.TopLeft, unbounded = true))
        }
    }
}
