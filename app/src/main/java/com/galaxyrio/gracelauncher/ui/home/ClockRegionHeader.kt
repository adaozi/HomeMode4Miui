package com.galaxyrio.gracelauncher.ui.home

import android.text.format.DateFormat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onLongClick as semanticOnLongClick
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockAlignment
import com.galaxyrio.gracelauncher.data.ClockColorPart
import com.galaxyrio.gracelauncher.data.ClockElement
import com.galaxyrio.gracelauncher.data.ClockLayout
import com.galaxyrio.gracelauncher.data.ClockRegion
import com.galaxyrio.gracelauncher.data.ClockRegionRow
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.visibleAgendaEvents
import com.galaxyrio.gracelauncher.data.weather.WeatherCurrent
import com.galaxyrio.gracelauncher.data.weather.WeatherSnapshot
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.theme.LauncherBatteryState
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import com.galaxyrio.gracelauncher.ui.theme.rememberBatteryState
import com.galaxyrio.gracelauncher.ui.theme.resolvedFontColor
import com.galaxyrio.gracelauncher.ui.weather.HomeWeather
import com.galaxyrio.gracelauncher.ui.weather.hasHomeWeatherContent
import java.time.Instant
import com.galaxyrio.gracelauncher.data.tasks.TaskItem
import com.galaxyrio.gracelauncher.data.tasks.TasksPreferences
import com.galaxyrio.gracelauncher.data.tasks.visibleTaskItems
import kotlin.math.roundToInt
import kotlin.math.ceil

/** Saved legacy layouts keep their appearance until the region editor is applied. */
@Composable
internal fun HomeClockHeader(
    now: Instant,
    event: ScheduleEvent?,
    onDateClick: () -> Unit,
    onClockClick: () -> Unit,
    showBattery: Boolean,
    clockStyle: ClockStyle,
    weather: WeatherCurrent?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    clockTag: String = "home_clock",
    dateTag: String = "home_date",
    onLongClick: () -> Unit = {},
    onAnchorOffsetChange: (Int) -> Unit = {},
    showClock: Boolean = true,
    showCalendar: Boolean = true,
    calendarAboveClock: Boolean = false,
    onWeatherClick: () -> Unit = {},
    weatherSnapshot: WeatherSnapshot? = null,
    agendaEvents: List<ScheduleEvent> = listOfNotNull(event),
    clockActive: Boolean = true,
    deprioritizeAllDay: Boolean = true,
    batteryOverride: LauncherBatteryState? = null,
    tasks: List<TaskItem> = emptyList(),
    showTasks: Boolean = false,
    onTaskClick: (TaskItem) -> Unit = {},
    taskPreferences: TasksPreferences = TasksPreferences(),
) {
    val regions = clockStyle.regions ?: if (showTasks) clockStyle.resolvedRegions(calendarAboveClock) else null
    if (regions == null) {
        LegacyHomeClockHeader(now, event, onDateClick, onClockClick, showBattery, clockStyle, weather ?: weatherSnapshot?.current,
            modifier, interactive, clockTag, dateTag, onLongClick, onAnchorOffsetChange,
            showClock, showCalendar, calendarAboveClock, onWeatherClick)
        return
    }
    val appearance = LocalLauncherAppearance.current
    val colors = ClockColorPart.entries.associateWith { resolvedFontColor(clockStyle.colors[it], onWallpaper = true) }
    val digitColors = listOf(ClockColorPart.HourTens, ClockColorPart.HourOnes, ClockColorPart.MinuteTens, ClockColorPart.MinuteOnes,
        ClockColorPart.SecondTens, ClockColorPart.SecondOnes)
        .map(colors::getValue)
    val locale = LocalConfiguration.current.locales[0]
    val maxRotatedHeightPx = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp * 1.2f).roundToPx().coerceAtLeast(1)
    }
    val clockFace = regions.face(ClockElement.Clock)
    val completeDate = clockCalendarDate(now, locale, true, true,
        colors.getValue(ClockColorPart.Weekday), colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day))
    val dateDescription = stringResource(R.string.date_calendar_action, completeDate.text)
    val batterySelected = regions.primary == ClockElement.Battery ||
        ClockElement.Battery in regions.secondary.selected || ClockElement.Battery in regions.tertiary.selected
    val battery = if (showCalendar && showBattery && batterySelected &&
        (regions.battery.showIcon || regions.battery.showPercentage)) batteryOverride ?: rememberBatteryState() else null
    val weatherVisible = hasHomeWeatherContent(weather, regions.weather, weatherSnapshot, now)
    val agendaRows = if (showCalendar) visibleAgendaEvents(agendaEvents, now, limit = 0, deprioritizeAllDay = deprioritizeAllDay)
        .mapNotNull { current ->
            clockAgendaFields(current, regions.agenda.visibleFields).takeIf { it.isNotEmpty() }
                ?.let { fields -> current to fields }
        }
        .let { rows -> if (regions.agenda.eventCount > 0) rows.take(regions.agenda.eventCount) else rows }
        else emptyList()
    val taskRows = if (showTasks) visibleTaskItems(tasks, taskPreferences, locale = locale).mapNotNull { task ->
        clockTaskFields(task, regions.tasks.visibleFields).takeIf { it.isNotEmpty() }?.let { task to it }
    }.let { rows -> if (regions.tasks.taskCount > 0) rows.take(regions.tasks.taskCount) else rows } else emptyList()
    fun isVisible(element: ClockElement): Boolean = when (element) {
        ClockElement.Clock -> showClock
        ClockElement.Weekday, ClockElement.Date -> showCalendar
        ClockElement.Battery -> battery != null
        ClockElement.Weather -> weatherVisible
    }
    val primaryVisible = regions.primary?.let(::isVisible) == true
    val reportAnchor by rememberUpdatedState(onAnchorOffsetChange)
    LaunchedEffect(primaryVisible, regions.rotateAll) {
        if (!primaryVisible || regions.rotateAll) reportAnchor(0)
    }
    fun faceStyle(element: ClockElement): ClockStyle {
        val face = regions.face(element)
        return ClockStyle(
            layout = if (element == ClockElement.Clock && regions.stackedClock) ClockLayout.TwoLines else ClockLayout.SingleLine,
            singleLine = face, twoLines = face, colors = clockStyle.colors,
        )
    }

    @Composable
    fun element(element: ClockElement, primary: Boolean, textStyle: TextStyle, elementModifier: Modifier,
        showSeconds: Boolean = false, useLatinLetters: Boolean = false, fixedClockWidth: Boolean = false) {
        val calendarLocale = remember(locale, useLatinLetters) { clockCalendarLocale(locale, useLatinLetters) }
        val action = when (element) {
            ClockElement.Clock -> onClockClick
            ClockElement.Weekday, ClockElement.Date -> onDateClick
            ClockElement.Weather -> onWeatherClick
            ClockElement.Battery -> null
        }
        val description = when (element) {
            ClockElement.Weekday, ClockElement.Date -> stringResource(R.string.date_calendar_action,
                clockCalendarDate(now, calendarLocale, true, true, colors.getValue(ClockColorPart.Weekday),
                    colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day)).text)
            else -> null
        }
        val tag = when (element) {
            ClockElement.Clock -> if (primary) clockTag else "${clockTag}_time"
            ClockElement.Weekday -> "${dateTag}_weekday"
            ClockElement.Date -> dateTag
            ClockElement.Battery -> "${dateTag}_battery"
            ClockElement.Weather -> "home_weather"
        }
        // Compact rows own their agenda action; only weather keeps an independent pointer target.
        val contentModifier = elementModifier.testTag(tag).then(
            if (!primary || element == ClockElement.Weather) Modifier else Modifier
                .then(if (description == null) Modifier else Modifier.semantics { contentDescription = description })
                .then(if (interactive && action != null) Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = appearance.text),
                    role = Role.Button,
                    onClick = action, onLongClick = onLongClick,
                ) else if (interactive) Modifier
                    .pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick() }) }
                    .semantics { semanticOnLongClick { onLongClick(); true } }
                else Modifier),
        )
        when (element) {
            ClockElement.Clock -> ClockRegionTime(now, primary, showSeconds, clockActive,
                if (primary) faceStyle(element) else ClockStyle(singleLine = clockFace),
                textStyle, appearance.text, digitColors, contentModifier, fixedClockWidth = fixedClockWidth)
            ClockElement.Weekday -> {
                val weekday = clockCalendarDate(now, calendarLocale, true, false,
                    colors.getValue(ClockColorPart.Weekday), colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day))
                if (primary) ClockFace(weekday.text, faceStyle(element), colors.getValue(ClockColorPart.Weekday), contentModifier)
                else Text(weekday, contentModifier, color = appearance.text, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ClockElement.Date -> {
                val date = clockCalendarDate(now, calendarLocale, false, true,
                    colors.getValue(ClockColorPart.Weekday), colors.getValue(ClockColorPart.Month), colors.getValue(ClockColorPart.Day))
                Text(date, contentModifier, color = appearance.text, style = textStyle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ClockElement.Battery -> battery?.let { state ->
                ClockBatteryContent(state, regions.battery, textStyle, colors.getValue(ClockColorPart.Battery), contentModifier,
                    iconColor = colors.getValue(ClockColorPart.BatteryIcon))
            }
            ClockElement.Weather -> {
                HomeWeather(weather, colors.getValue(ClockColorPart.Weather), textStyle, contentModifier,
                    onClick = onWeatherClick.takeIf { interactive }, onLongClick = onLongClick.takeIf { interactive },
                    iconColor = colors.getValue(ClockColorPart.WeatherIcon), config = regions.weather, snapshot = weatherSnapshot, now = now)
            }
        }
    }

    @Composable
    fun primaryRegion(fillWidth: Boolean = true, inset: Boolean = true) {
        val primary = regions.primary?.takeIf(::isVisible) ?: return
        val face = regions.face(primary)
        val primaryStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = rememberAppFontFamily(face.fontId), fontWeight = FontWeight(face.weight),
            fontSize = face.size.sp, lineHeight = (face.size * 1.15f).sp,
            letterSpacing = face.letterSpacing.sp, shadow = clockFontShadow(face.fontShadow),
        )
        val alignment = when (regions.primaryAlignment) {
            ClockAlignment.Left -> AbsoluteAlignment.CenterLeft
            ClockAlignment.Center -> Alignment.Center
            ClockAlignment.Right -> AbsoluteAlignment.CenterRight
        }
        Box(Modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .then(if (inset) Modifier.padding(horizontal = LauncherLayout.ContentInset) else Modifier)
            .testTag("clock_region:Primary"), contentAlignment = alignment) {
            if (regions.rotatePrimary && !regions.rotateAll) ClockQuarterTurn(maxRotatedHeightPx) {
                element(primary, true, primaryStyle, Modifier,
                    showSeconds = !regions.stackedClock && face.showSeconds, useLatinLetters = face.useLatinLetters)
            } else element(primary, true, primaryStyle, Modifier,
                showSeconds = !regions.stackedClock && face.showSeconds, useLatinLetters = face.useLatinLetters)
        }
    }

    @Composable
    fun rowRegion(row: ClockRegionRow, name: String, inset: Boolean = true, wrapWidth: Boolean = false) {
        val left = row.leftItems.filter(::isVisible)
        val right = row.rightItems.filter(::isVisible)
        if (left.isEmpty() && right.isEmpty()) return
        val typography = row.typography
        val rowStyle = MaterialTheme.typography.bodyLarge.copy(
            fontFamily = rememberAppFontFamily(typography.fontId),
            fontSize = typography.size.sp, lineHeight = (typography.size * 1.5f).sp,
            fontWeight = FontWeight(typography.weight), shadow = clockFontShadow(typography.fontShadow),
        )
        val interactions = remember { MutableInteractionSource() }
        Box(Modifier.then(if (wrapWidth) Modifier else Modifier.fillMaxWidth())
            .testTag("clock_region:$name")
            .semantics { contentDescription = dateDescription }
            .then(if (interactive) Modifier.combinedClickable(
                interactionSource = interactions, indication = null,
                role = Role.Button, onClick = onDateClick, onLongClick = onLongClick,
            ) else Modifier), propagateMinConstraints = true) {
            ClockRegionRowLayout(left.size, right.size,
                Modifier.then(if (inset) Modifier.padding(horizontal = LauncherLayout.ContentInset) else Modifier),
                wrapWidth = wrapWidth, centered = row.centered) {
                (left + right).forEach { item ->
                    key(item) {
                        if (interactive) element(item, false, rowStyle, Modifier,
                            showSeconds = row.showSeconds, useLatinLetters = row.useLatinLetters,
                            fixedClockWidth = row.fixedClockWidth)
                        else ClockRegionItemMotion { movement -> element(item, false, rowStyle, movement,
                            showSeconds = row.showSeconds, useLatinLetters = row.useLatinLetters,
                            fixedClockWidth = row.fixedClockWidth) }
                    }
                }
            }
            if (interactive) {
                Box(Modifier.matchParentSize().clip(LauncherLayout.RowShape)
                    .indication(interactions, ripple(color = appearance.text)))
            }
        }
    }

    @Composable
    fun agendaRegion(inset: Boolean = true, wrapWidth: Boolean = false) {
        if (agendaRows.isEmpty()) return
        val widthModifier = if (wrapWidth) Modifier else Modifier.fillMaxWidth()
        val interactions = remember { MutableInteractionSource() }
        Box(widthModifier.testTag("clock_region:Agenda"), propagateMinConstraints = wrapWidth) {
            Box(widthModifier.testTag("home_agenda")
                .semantics { contentDescription = dateDescription }
                .then(if (interactive) Modifier.combinedClickable(
                    interactionSource = interactions, indication = null,
                    role = Role.Button, onClick = onDateClick, onLongClick = onLongClick,
                ) else Modifier), propagateMinConstraints = wrapWidth) {
                Column {
                    agendaRows.forEach { (current, fields) ->
                        key(current.id, current.startsAt) {
                            ClockAgendaContent(current, now, fields, regions.agenda.typography,
                                Modifier.fillMaxWidth().then(if (inset) Modifier.padding(horizontal = LauncherLayout.ContentInset) else Modifier),
                                animatePositions = !interactive,
                                useLatinLetters = regions.agenda.useLatinLetters, wrapWidth = wrapWidth,
                                color = colors.getValue(ClockColorPart.Agenda), centered = regions.agenda.centered,
                                rightAligned = regions.agenda.rightAligned)
                        }
                    }
                }
                if (interactive) {
                    // The rounded touch feedback must not clip the first/last row or its shadow.
                    Box(Modifier.matchParentSize().clip(LauncherLayout.RowShape)
                        .indication(interactions, ripple(color = colors.getValue(ClockColorPart.Agenda))))
                }
            }
        }
    }

    @Composable
    fun taskRegion(inset: Boolean = true, wrapWidth: Boolean = false) {
        if (taskRows.isEmpty()) return
        val widthModifier = if (wrapWidth) Modifier else Modifier.fillMaxWidth()
        Column(widthModifier.testTag("clock_region:Task")) {
            taskRows.forEach { (task, fields) ->
                key(task.id) {
                    val interactions = remember { MutableInteractionSource() }
                    Box(widthModifier.testTag("home_task:${task.id}")
                        .then(if (interactive) Modifier.combinedClickable(interactionSource = interactions, indication = null,
                            role = Role.Button, onClick = { onTaskClick(task) }, onLongClick = onLongClick) else Modifier),
                        propagateMinConstraints = wrapWidth) {
                        ClockTaskContent(task, now, fields, regions.tasks, colors.getValue(ClockColorPart.Task),
                            Modifier.fillMaxWidth().then(if (inset) Modifier.padding(horizontal = LauncherLayout.ContentInset) else Modifier),
                            animatePositions = !interactive, wrapWidth = wrapWidth)
                        if (interactive) Box(Modifier.matchParentSize().clip(LauncherLayout.RowShape)
                            .indication(interactions, ripple(color = colors.getValue(ClockColorPart.Task))))
                    }
                }
            }
        }
    }

    val visibleRegions = regions.regionOrder.filter { region ->
        when (region) {
            ClockRegion.Primary -> primaryVisible
            ClockRegion.Secondary -> regions.secondary.selected.any(::isVisible)
            ClockRegion.Tertiary -> regions.tertiary.selected.any(::isVisible)
            ClockRegion.Agenda -> agendaRows.isNotEmpty()
            ClockRegion.Task -> taskRows.isNotEmpty()
        }
    }
    @Composable
    fun regionContent(order: List<ClockRegion> = visibleRegions, inset: Boolean = true, wrapWidth: Boolean = false) {
        if (wrapWidth) {
            ClockRotatedRegionColumn(order.indexOf(ClockRegion.Primary), regions.primaryAlignment, regions.regionSpacing) {
                order.forEach { region ->
                    key(region) {
                        when (region) {
                            ClockRegion.Primary -> primaryRegion(fillWidth = false, inset = inset)
                            ClockRegion.Secondary -> rowRegion(regions.secondary, "Secondary", inset, wrapWidth = true)
                            ClockRegion.Tertiary -> rowRegion(regions.tertiary, "Tertiary", inset, wrapWidth = true)
                            ClockRegion.Agenda -> agendaRegion(inset, wrapWidth = true)
                            ClockRegion.Task -> taskRegion(inset, wrapWidth = true)
                        }
                    }
                }
            }
            return
        }
        Column {
            order.forEachIndexed { index, region ->
                key(region) {
                    if (index > 0) Spacer(Modifier.height(regions.regionSpacing.dp))
                    when (region) {
                        ClockRegion.Primary -> {
                            primaryRegion(fillWidth = !wrapWidth, inset = inset)
                            if (!regions.rotateAll) Spacer(Modifier.height(0.dp)
                                .onGloballyPositioned { onAnchorOffsetChange(it.positionInParent().y.roundToInt()) })
                        }
                        ClockRegion.Secondary -> rowRegion(regions.secondary, "Secondary", inset, wrapWidth)
                        ClockRegion.Tertiary -> rowRegion(regions.tertiary, "Tertiary", inset, wrapWidth)
                        ClockRegion.Agenda -> agendaRegion(inset, wrapWidth)
                        ClockRegion.Task -> taskRegion(inset, wrapWidth)
                    }
                }
            }
        }
    }
    Column(modifier.testTag("clock_regions")) {
        if (visibleRegions.isNotEmpty()) {
            // Primary remains the divider even when its content is hidden or empty.
            val primaryIndex = regions.regionOrder.indexOf(ClockRegion.Primary)
            val above = regions.regionOrder.take(primaryIndex.coerceAtLeast(0)).filter { it in visibleRegions }
            val below = regions.regionOrder.drop(primaryIndex + 1).filter { it in visibleRegions }
            when {
                regions.rotateAll -> Box(Modifier.fillMaxWidth().padding(horizontal = LauncherLayout.ContentInset),
                    contentAlignment = AbsoluteAlignment.TopLeft) {
                    // The icon-alignment inset belongs to the screen axes, outside the rotation.
                    ClockQuarterTurn(maxRotatedHeightPx) { regionContent(inset = false, wrapWidth = true) }
                }
                regions.rotateAbovePrimary && above.isNotEmpty() -> {
                    val alignment = when (regions.primaryAlignment) {
                        ClockAlignment.Left -> AbsoluteAlignment.TopLeft
                        ClockAlignment.Center -> Alignment.TopCenter
                        ClockAlignment.Right -> AbsoluteAlignment.TopRight
                    }
                    Box(Modifier.fillMaxWidth().padding(horizontal = LauncherLayout.ContentInset), contentAlignment = alignment) {
                        if (primaryVisible) {
                            ClockAbovePrimaryLayout(regions.regionSpacing, maxRotatedHeightPx,
                                above = { regionContent(above, inset = false, wrapWidth = true) },
                                primary = { primaryRegion(fillWidth = false, inset = false) })
                        } else {
                            ClockQuarterTurn(maxRotatedHeightPx) { regionContent(above, inset = false, wrapWidth = true) }
                        }
                    }
                    if (primaryVisible) Spacer(Modifier.height(0.dp)
                        .onGloballyPositioned { onAnchorOffsetChange(it.positionInParent().y.roundToInt()) })
                    if (below.isNotEmpty()) {
                        Spacer(Modifier.height(regions.regionSpacing.dp))
                        regionContent(below)
                    }
                }
                else -> regionContent()
            }
            // Keep the widget's bottom inset in screen coordinates, outside every rotation.
            Spacer(Modifier.height(regions.bottomPadding.dp))
        }
    }
}

/** Share the longest natural row width while keeping Primary out of intrinsic measurement. */
@Composable
private fun ClockRotatedRegionColumn(
    primaryIndex: Int,
    primaryAlignment: ClockAlignment,
    spacing: Int,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        // ClockFace uses BoxWithConstraints, which cannot answer intrinsic queries.
        val primary = measurables.getOrNull(primaryIndex)?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val requestedWidth = measurables.indices.maxOfOrNull { index ->
            if (index == primaryIndex) requireNotNull(primary).width
            else measurables[index].maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(constraints.maxWidth)
        } ?: 0
        val width = constraints.constrainWidth(requestedWidth.coerceAtLeast(0))
        val placeables = measurables.mapIndexed { index, measurable ->
            if (index == primaryIndex) requireNotNull(primary)
            else measurable.measure(Constraints(minWidth = width, maxWidth = width))
        }
        val gap = spacing.dp.roundToPx()
        val height = constraints.constrainHeight(placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0))
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                val x = if (index == primaryIndex) when (primaryAlignment) {
                    ClockAlignment.Left -> 0
                    ClockAlignment.Center -> (width - placeable.width) / 2
                    ClockAlignment.Right -> width - placeable.width
                } else 0
                placeable.place(x, y)
                y += placeable.height + gap
            }
        }
    }
}

/** Keep the rotated upper group and Primary bottom-aligned in a bounded, content-sized block. */
@Composable
private fun ClockAbovePrimaryLayout(
    spacing: Int,
    maxRotatedHeightPx: Int,
    above: @Composable () -> Unit,
    primary: @Composable () -> Unit,
) {
    Layout(content = {
        Box { above() }
        Box { primary() }
    }) { measurables, constraints ->
        val primaryPlaceable = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        // Row width becomes height after rotation; long content can use up to 1.2
        // screen widths instead of being truncated to the adjacent Primary's height.
        val abovePlaceable = measurables[0].measure(Constraints(
            maxWidth = minOf(maxRotatedHeightPx, constraints.maxHeight)))
        val gap = spacing.dp.roundToPx()
        val naturalWidth = abovePlaceable.height + gap + primaryPlaceable.width
        val naturalHeight = maxOf(abovePlaceable.width, primaryPlaceable.height)
        val scale = minOf(1f, constraints.maxWidth.toFloat() / naturalWidth.coerceAtLeast(1),
            constraints.maxHeight.toFloat() / naturalHeight.coerceAtLeast(1))
        val width = constraints.constrainWidth(ceil(naturalWidth * scale).toInt())
        val height = constraints.constrainHeight(ceil(naturalHeight * scale).toInt())
        layout(width, height) {
            abovePlaceable.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                rotationZ = -90f
                scaleX = scale
                scaleY = scale
                translationY = height.toFloat()
            }
            primaryPlaceable.placeWithLayer(0, 0) {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale
                scaleY = scale
                translationX = (abovePlaceable.height + gap) * scale
                translationY = height - primaryPlaceable.height * scale
            }
        }
    }
}

/** A seconds clock invalidates only this leaf, leaving categories, weather and agenda on minute ticks. */
@Composable
private fun ClockRegionTime(
    now: Instant,
    primary: Boolean,
    showSeconds: Boolean,
    active: Boolean,
    style: ClockStyle,
    textStyle: TextStyle,
    color: Color,
    digitColors: List<Color>,
    modifier: Modifier,
    fixedClockWidth: Boolean = false,
) {
    val clockNow = if (showSeconds && active) {
        val ticking by rememberHomeTime(showSeconds = true)
        ticking
    } else now
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val text = formatHomeClock(clockNow, use24Hour, locale,
        leadingZero = style.face.showLeadingZero, showSeconds = showSeconds)
    val description = if (primary) stringResource(R.string.clock_action, text) else text
    val described = modifier.semantics { contentDescription = description }
    val clockModifier = if (!primary && fixedClockWidth) described.width(rememberFixedClockWidth(
        textStyle = textStyle,
        use24Hour = use24Hour,
        showLeadingZero = style.face.showLeadingZero,
        showColon = style.face.showColon,
        showSeconds = showSeconds,
        locale = locale,
    )) else described
    if (primary) ClockFace(text, style, color, described, digitColors = digitColors)
    else ClockTimeLabel(text, style, textStyle, color, clockModifier, digitColors = digitColors, useStandardSpacing = true)
}

/** Animate the preview ink while its slot keeps the row's final width and baseline. */
@Composable
internal fun ClockRegionItemMotion(content: @Composable (Modifier) -> Unit) {
    var target by remember { mutableStateOf<Offset?>(null) }
    // Start at the first measured position; new selections should not fly in from the origin.
    val position = target?.let {
        animateOffsetAsState(it, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "clockRegionItemPosition")
    }
    Box(Modifier.onPlaced { target = it.positionInParent() }) {
        content(Modifier.graphicsLayer {
            val currentTarget = target
            val offset = if (position != null && currentTarget != null) position.value - currentTarget else Offset.Zero
            translationX = offset.x
            translationY = offset.y
        })
    }
}

/** Swap measured dimensions as well as pixels, so layout, semantics and touch bounds rotate together. */
@Composable
private fun ClockQuarterTurn(maxRotatedHeightPx: Int, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val maxWidth = minOf(maxRotatedHeightPx, constraints.maxHeight)
        // Measure all regions before fitting; a tall primary must not consume the other rows' space.
        val child = measurables.single().measure(Constraints(maxWidth = maxWidth))
        val scale = minOf(1f, constraints.maxWidth.toFloat() / child.height.coerceAtLeast(1),
            constraints.maxHeight.toFloat() / child.width.coerceAtLeast(1))
        val width = ceil(child.height * scale).toInt().coerceAtMost(constraints.maxWidth)
        val height = ceil(child.width * scale).toInt().coerceAtMost(constraints.maxHeight)
        layout(width, height) {
            child.placeWithLayer(0, 0) {
                rotationZ = -90f
                scaleX = scale
                scaleY = scale
                translationX = (width - child.width) / 2f
                translationY = (height - child.height) / 2f
            }
        }
    }
}

/** Center the complete ordered row or preserve its two edge groups when text needs truncation. */
@Composable
private fun ClockRegionRowLayout(leftCount: Int, rightCount: Int, modifier: Modifier, wrapWidth: Boolean = false,
    centered: Boolean = false,
    content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val leftGaps = (leftCount - 1).coerceAtLeast(0) * gap
        val rightGaps = (rightCount - 1).coerceAtLeast(0) * gap
        val betweenGroups = if (leftCount > 0 && rightCount > 0) gap else 0
        val intrinsicWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtLeast(1) }
        val requested = intrinsicWidths.sumOf { it.toLong() }.coerceAtLeast(1L)
        val layoutWidth = if (wrapWidth) constraints.constrainWidth(
            (requested + leftGaps + rightGaps + betweenGroups).coerceAtMost(constraints.maxWidth.toLong()).toInt())
            else constraints.maxWidth
        val available = (layoutWidth - leftGaps - rightGaps - betweenGroups).coerceAtLeast(0)
        val placeables = measurables.mapIndexed { index, measurable ->
            val width = if (requested <= available) intrinsicWidths[index]
                else (intrinsicWidths[index].toLong() * available / requested).toInt()
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width))
        }
        val baselines = placeables.map { it[FirstBaseline].takeUnless { line -> line == AlignmentLine.Unspecified } ?: it.height }
        val above = baselines.maxOrNull() ?: 0
        val below = placeables.indices.maxOfOrNull { placeables[it].height - baselines[it] } ?: 0
        layout(layoutWidth, constraints.constrainHeight(above + below), mapOf(FirstBaseline to above)) {
            var centerX = (layoutWidth - placeables.sumOf { it.width } - leftGaps - rightGaps - betweenGroups)
                .coerceAtLeast(0) / 2
            var leftX = 0
            var rightX = layoutWidth - placeables.drop(leftCount).sumOf { it.width } - rightGaps
            placeables.forEachIndexed { index, placeable ->
                if (centered) {
                    placeable.place(centerX, above - baselines[index])
                    centerX += placeable.width + gap
                } else if (index < leftCount) {
                    placeable.place(leftX, above - baselines[index])
                    leftX += placeable.width + gap
                } else {
                    placeable.place(rightX, above - baselines[index])
                    rightX += placeable.width + gap
                }
            }
        }
    }
}
