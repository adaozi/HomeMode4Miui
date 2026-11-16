@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockAgendaField
import com.galaxyrio.gracelauncher.data.ClockWeatherField
import com.galaxyrio.gracelauncher.data.ClockTaskField
import com.galaxyrio.gracelauncher.ui.overlays.FavoritesReorderState
import com.galaxyrio.gracelauncher.ui.overlays.rememberReorderTranslation
import kotlinx.coroutines.isActive

private const val ClockFieldAlignmentDivider = "alignment_divider"
private const val ClockFieldPrimaryDivider = "primary_alignment_divider"
private const val ClockFieldPrimaryHeading = "primary_heading"
private const val ClockFieldSupportingHeading = "supporting_heading"
private const val ClockFieldUnselectedHeading = "unselected_heading"
private val ClockFieldHeadings = setOf(ClockFieldPrimaryHeading, ClockFieldSupportingHeading, ClockFieldUnselectedHeading)

@Composable
internal fun ClockTaskField.label(): String = stringResource(when (this) {
    ClockTaskField.Title -> R.string.clock_task_field_title
    ClockTaskField.Countdown -> R.string.clock_task_field_countdown
    ClockTaskField.DueDate -> R.string.clock_task_field_due_date
    ClockTaskField.DueTime -> R.string.clock_task_field_due_time
    ClockTaskField.StartDate -> R.string.clock_task_field_start_date
    ClockTaskField.Priority -> R.string.clock_task_field_priority
    ClockTaskField.List -> R.string.clock_task_field_list
    ClockTaskField.Notes -> R.string.clock_task_field_notes
    ClockTaskField.Color -> R.string.clock_task_field_color
})

/** Like the color picker, this panel stays below the live preview and edits the current draft. */
@Composable
internal fun <T : Enum<T>> ClockContentPicker(
    title: String,
    order: List<T>,
    selected: Set<T>,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    tag: String,
    label: @Composable (T) -> String,
    onSelect: (T, Boolean) -> Unit,
    onReorder: (List<T>) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    labelShown: (T) -> Boolean? = { null },
    onLabelChange: (T, Boolean) -> Unit = { _, _ -> },
    rightAligned: Set<T>? = null,
    onAlignedReorder: (List<T>, Set<T>) -> Unit = { _, _ -> },
    primaryLine: Set<T>? = null,
    onLineReorder: (List<T>, Set<T>, Set<T>, Set<T>) -> Unit = { _, _, _, _ -> },
    positionIcons: Map<T, Int> = emptyMap(),
    beforeFields: LazyListScope.() -> Unit = {},
) {
    Column(modifier.fillMaxSize().testTag("${tag}_picker")
        .clip(ListItemDefaults.segmentedShapes(0, 1).shape)
        .background(MaterialTheme.colorScheme.surfaceBright)
        .padding(16.dp).padding(bottom = LocalSettingsBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClose, Modifier.testTag("${tag}_close")) {
                Icon(painterResource(R.drawable.ms_close), stringResource(R.string.done))
            }
        }
        ClockFieldOrder(order, selected, enabled, hapticsEnabled, tag, label, onSelect, onReorder,
            Modifier.weight(1f), labelShown, onLabelChange, rightAligned, onAlignedReorder, primaryLine, onLineReorder,
            positionIcons, beforeFields)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onReset, Modifier.testTag("${tag}_reset"), enabled = enabled) {
                Text(stringResource(R.string.reset))
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClose, Modifier.testTag("${tag}_done")) { Text(stringResource(R.string.done)) }
        }
    }
}

/** Checkbox selection and drag ordering; optional lines share a list for cross-line moves. */
@Composable
internal fun <T : Enum<T>> ClockFieldOrder(
    order: List<T>,
    selected: Set<T>,
    enabled: Boolean,
    hapticsEnabled: Boolean,
    tag: String,
    label: @Composable (T) -> String,
    onSelect: (T, Boolean) -> Unit,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    labelShown: (T) -> Boolean? = { null },
    onLabelChange: (T, Boolean) -> Unit = { _, _ -> },
    rightAligned: Set<T>? = null,
    onAlignedReorder: (List<T>, Set<T>) -> Unit = { _, _ -> },
    primaryLine: Set<T>? = null,
    onLineReorder: (List<T>, Set<T>, Set<T>, Set<T>) -> Unit = { _, _, _, _ -> },
    positionIcons: Map<T, Int> = emptyMap(),
    beforeFields: LazyListScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    onEdit: ((T) -> Unit)? = null,
    fieldModifier: @Composable (T) -> Modifier = { Modifier },
    listState: LazyListState = rememberLazyListState(),
) {
    val list = listState
    val fields by rememberUpdatedState(order.associateBy { it.name })
    val grouped = rightAligned != null
    val activeKeys = selected.map { it.name }.toSet() + ClockFieldAlignmentDivider
    val primaryKeys = selected.filter { it in primaryLine.orEmpty() }.map { it.name }.toSet() + ClockFieldPrimaryDivider
    val supportingKeys = activeKeys - primaryKeys
    val commit by rememberUpdatedState<(List<String>) -> Unit> { saved ->
        val updatedOrder = saved.mapNotNull { fields[it] }
        if (primaryLine != null) {
            fun between(start: String, end: String) = saved.subList(saved.indexOf(start) + 1, saved.indexOf(end))
                .mapNotNull { fields[it] }.toSet()
            val primary = between(ClockFieldPrimaryHeading, ClockFieldSupportingHeading)
            val supporting = between(ClockFieldSupportingHeading, ClockFieldUnselectedHeading)
            val visible = primary + supporting
            val right = between(ClockFieldPrimaryDivider, ClockFieldSupportingHeading) +
                between(ClockFieldAlignmentDivider, ClockFieldUnselectedHeading)
            // Unselected fields keep their previous line and side for checkbox selection.
            onLineReorder(updatedOrder, visible, (primaryLine - visible) + primary, (rightAligned.orEmpty() - visible) + right)
        } else if (rightAligned != null) {
            val right = saved.drop(saved.indexOf(ClockFieldAlignmentDivider) + 1)
                .mapNotNull { fields[it] }.filter { it in selected }.toSet()
            // Hidden fields retain their saved side when selection or visible ordering changes.
            onAlignedReorder(updatedOrder, (rightAligned - selected) + right)
        } else onReorder(updatedOrder)
    }
    val canMove by rememberUpdatedState<(String, String) -> Boolean> { from, to ->
        if (primaryLine != null) when (from) {
            ClockFieldPrimaryDivider -> to in primaryKeys
            ClockFieldAlignmentDivider -> to in supportingKeys
            // Headings stay in order; fields may cross them to change their line or selection.
            in fields -> to != ClockFieldPrimaryHeading
            else -> false
        } else !grouped || (from in activeKeys) == (to in activeKeys)
    }
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val feedbackEnabled by rememberUpdatedState(hapticsEnabled)
    val keys = if (primaryLine != null) {
        fun lineFields(primary: Boolean, right: Boolean) = order.filter {
            it in selected && (it in primaryLine) == primary && (it in rightAligned.orEmpty()) == right
        }.map { it.name }
        listOf(ClockFieldPrimaryHeading) + lineFields(true, false) + ClockFieldPrimaryDivider + lineFields(true, true) +
            ClockFieldSupportingHeading + lineFields(false, false) + ClockFieldAlignmentDivider + lineFields(false, true) +
            ClockFieldUnselectedHeading + order.filterNot { it in selected }.map { it.name }
    } else if (rightAligned != null) {
        order.filter { it in selected && it !in rightAligned }.map { it.name } + ClockFieldAlignmentDivider +
            order.filter { it in selected && it in rightAligned }.map { it.name } +
            order.filterNot { it in selected }.map { it.name }
    } else order.map { it.name }
    val reorder = remember(list) {
        FavoritesReorderState(list, keys, { saved -> commit(saved) }, { from, to -> canMove(from, to) }) {
            if (feedbackEnabled) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(keys) { reorder.synchronize(keys) }
    val density = LocalDensity.current
    LaunchedEffect(reorder.draggingKey, density) {
        if (reorder.draggingKey == null) return@LaunchedEffect
        val edge = with(density) { 64.dp.toPx() }
        val speed = with(density) { 640.dp.toPx() }
        var previous = withFrameNanos { it }
        while (isActive && reorder.draggingKey != null) {
            val frame = withFrameNanos { it }
            reorder.autoScroll(edge, speed * ((frame - previous) / 1_000_000_000f).coerceIn(0f, .032f))
            previous = frame
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag("${tag}_list"),
        state = list,
        // Read-only settings still need to expose fields below the viewport.
        userScrollEnabled = reorder.draggingKey == null,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        beforeFields()
        fun fieldItems(itemKeys: List<String>) {
            itemsIndexed(itemKeys, key = { _, key -> FavoritesReorderState.itemKey(key) }) { rowIndex, key ->
                if (primaryLine != null) {
                    val heading = when (key) {
                        ClockFieldPrimaryHeading -> R.string.agenda_primary_line
                        ClockFieldSupportingHeading -> R.string.agenda_supporting_information
                        ClockFieldUnselectedHeading -> R.string.clock_content_unselected
                        else -> null
                    }
                    if (heading != null) {
                        SettingsHeading(stringResource(heading))
                        return@itemsIndexed
                    }
                }
                val field = fields[key]
                val divider = (grouped && key == ClockFieldAlignmentDivider) ||
                    (primaryLine != null && key in setOf(ClockFieldPrimaryDivider, ClockFieldAlignmentDivider))
                if (field == null && !divider) return@itemsIndexed
                val sectionStart = itemKeys.take(rowIndex).indexOfLast { it in ClockFieldHeadings } + 1
                val sectionEnd = (rowIndex + 1 until itemKeys.size).firstOrNull { itemKeys[it] in ClockFieldHeadings }
                    ?: itemKeys.size
                val shape = ListItemDefaults.segmentedShapes(rowIndex - sectionStart, sectionEnd - sectionStart).shape
                val index = reorder.keys.indexOf(key)
                val title = if (divider) stringResource(R.string.clock_content_alignment_divider) else label(requireNotNull(field))
                val up = stringResource(R.string.favorites_move_up)
                val down = stringResource(R.string.favorites_move_down)
                val canMoveUp = enabled && index > 0 && canMove(key, reorder.keys[index - 1])
                val canMoveDown = enabled && index < reorder.keys.lastIndex && canMove(key, reorder.keys[index + 1])
                val dragging = reorder.draggingKey == key
                val offset by rememberReorderTranslation(reorder, key)
                val background by animateColorAsState(
                    if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else containerColor,
                    label = "clockFieldLift",
                )
                Row(
                    modifier = Modifier.then(if (dragging) Modifier else Modifier.animateItem())
                        .zIndex(if (dragging || reorder.drop?.key == key) 1f else 0f)
                        .graphicsLayer { translationY = offset }
                        .then(if (field != null && field !in positionIcons) fieldModifier(field) else Modifier)
                        .fillMaxWidth().height(64.dp).clip(shape).background(background)
                        .then(if (onEdit != null && field != null && field !in positionIcons)
                            Modifier.clickable(enabled = enabled && !dragging) { onEdit(field) } else Modifier)
                        .testTag("$tag:$key")
                        .semantics {
                            customActions = buildList {
                                if (canMoveUp) add(CustomAccessibilityAction(up) { reorder.moveBy(key, -1) })
                                if (canMoveDown) add(CustomAccessibilityAction(down) { reorder.moveBy(key, 1) })
                            }
                        }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val positionIcon = field?.let { positionIcons[it] }
                    if (field != null && positionIcon == null) Checkbox(
                        checked = if (primaryLine != null) index < reorder.keys.indexOf(ClockFieldUnselectedHeading) else field in selected,
                        onCheckedChange = { onSelect(field, it) },
                        enabled = enabled,
                        modifier = Modifier.testTag("${tag}_selected:$key").semantics { contentDescription = title },
                    ) else Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(positionIcon ?: R.drawable.ms_unfold_more), null, Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                    }
                    Text(title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
                    field?.let(labelShown)?.let { shown ->
                        IconToggleButton(
                            checked = shown,
                            onCheckedChange = { onLabelChange(requireNotNull(field), it) },
                            enabled = enabled,
                            modifier = Modifier.testTag("${tag}_label:$key"),
                        ) {
                            Icon(painterResource(if (shown) R.drawable.ms_label else R.drawable.ms_label_off),
                                stringResource(if (shown) R.string.clock_hide_field_label else R.string.clock_show_field_label, title),
                                Modifier.size(24.dp))
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { menu = true },
                            enabled = enabled,
                            modifier = Modifier.testTag("${tag}_drag:$key")
                                .pointerInput(reorder, key, enabled) {
                                    if (enabled) detectDragGestures(
                                        onDragStart = { reorder.start(key) },
                                        onDragEnd = { reorder.finish(false) },
                                        onDragCancel = { reorder.finish(true) },
                                    ) { change, amount -> change.consume(); reorder.drag(amount.y) }
                                },
                        ) {
                            Icon(painterResource(R.drawable.ms_drag_indicator),
                                stringResource(R.string.favorites_reorder, title), Modifier.size(24.dp))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(up) }, enabled = canMoveUp,
                                onClick = { reorder.moveBy(key, -1); menu = false })
                            DropdownMenuItem(text = { Text(down) }, enabled = canMoveDown,
                                onClick = { reorder.moveBy(key, 1); menu = false })
                        }
                    }
                }
            }
        }
        if (primaryLine != null) fieldItems(reorder.keys)
        else if (grouped) {
            item("selected_heading") { SettingsHeading(stringResource(R.string.favorites_selected)) }
            fieldItems(reorder.keys.filter { it in activeKeys })
            val unselected = reorder.keys.filterNot { it in activeKeys }
            if (unselected.isNotEmpty()) {
                item("unselected_heading") { SettingsHeading(stringResource(R.string.clock_content_unselected)) }
                fieldItems(unselected)
            }
        } else fieldItems(reorder.keys)
    }
}

@Composable
internal fun ClockAgendaField.label(): String = stringResource(when (this) {
    ClockAgendaField.Title -> R.string.clock_agenda_field_title
    ClockAgendaField.Countdown -> R.string.clock_agenda_field_countdown
    ClockAgendaField.Time -> R.string.clock_agenda_field_time
    ClockAgendaField.Date -> R.string.clock_agenda_field_date
    ClockAgendaField.Duration -> R.string.clock_agenda_field_duration
    ClockAgendaField.Location -> R.string.clock_agenda_field_location
    ClockAgendaField.Color -> R.string.clock_agenda_field_color
    ClockAgendaField.Calendar -> R.string.clock_agenda_field_calendar
})

@Composable
internal fun ClockWeatherField.label(): String = stringResource(when (this) {
    ClockWeatherField.Icon -> R.string.clock_weather_field_icon
    ClockWeatherField.Temperature -> R.string.clock_weather_field_temperature
    ClockWeatherField.Description -> R.string.clock_weather_field_description
    ClockWeatherField.FeelsLike -> R.string.clock_weather_field_feels_like
    ClockWeatherField.Humidity -> R.string.clock_weather_field_humidity
    ClockWeatherField.Wind -> R.string.clock_weather_field_wind
    ClockWeatherField.AirQuality -> R.string.clock_weather_field_air_quality
    ClockWeatherField.UV -> R.string.clock_weather_field_uv
    ClockWeatherField.Location -> R.string.clock_weather_field_location
    ClockWeatherField.HighLow -> R.string.clock_weather_field_high_low
    ClockWeatherField.UpdatedAt -> R.string.clock_weather_field_updated_at
})
