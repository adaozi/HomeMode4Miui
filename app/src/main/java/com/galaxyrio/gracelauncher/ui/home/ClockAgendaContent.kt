package com.galaxyrio.gracelauncher.ui.home

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockAgendaField
import com.galaxyrio.gracelauncher.data.ClockAlignment
import com.galaxyrio.gracelauncher.data.ClockRowTypography
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.ui.components.eventRemainingText
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.time.temporal.ChronoUnit

/** Missing optional data contributes neither a field nor a separator or an empty region. */
internal fun clockAgendaFields(event: ScheduleEvent, requested: List<ClockAgendaField>): List<ClockAgendaField> =
    requested.filter { field ->
        when (field) {
            ClockAgendaField.Title -> event.title.isNotBlank()
            ClockAgendaField.Location -> !event.location.isNullOrBlank()
            ClockAgendaField.Calendar -> !event.calendarName.isNullOrBlank()
            ClockAgendaField.Color -> event.displayColor != null || event.calendarColor != null
            else -> true
        }
    }

@Composable
internal fun ClockAgendaContent(
    event: ScheduleEvent,
    now: Instant,
    fields: List<ClockAgendaField>,
    typography: ClockRowTypography,
    modifier: Modifier = Modifier,
    animatePositions: Boolean = false,
    useLatinLetters: Boolean = false,
    wrapWidth: Boolean = false,
    color: Color = LocalLauncherAppearance.current.text,
    centered: Boolean = false,
    rightAligned: Set<ClockAgendaField> = emptySet(),
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateLocale = remember(locale, useLatinLetters) { clockCalendarLocale(locale, useLatinLetters) }
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = rememberAppFontFamily(typography.fontId),
        fontSize = typography.size.sp, lineHeight = (typography.size * 1.5f).sp,
        fontWeight = FontWeight(typography.weight), shadow = clockFontShadow(typography.fontShadow),
    )
    val timeText = if (ClockAgendaField.Time in fields) {
        if (event.isAllDay) stringResource(R.string.all_day) else {
            val formatter = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale,
                if (use24Hour) "Hm" else "hm"), locale)
                .withDecimalStyle(DecimalStyle.of(locale)).withZone(ZoneId.systemDefault())
            "${formatter.format(event.startsAt)}–${formatter.format(event.endsAt)}"
        }
    } else ""
    val dateText = if (ClockAgendaField.Date in fields) {
        // All-day timestamps encode UTC calendar dates, not a local time to shift.
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(dateLocale, "MMMEd"), dateLocale)
            .withDecimalStyle(DecimalStyle.of(dateLocale))
            .withZone(if (event.isAllDay) ZoneOffset.UTC else ZoneId.systemDefault()).format(event.startsAt)
    } else ""
    val durationText = if (ClockAgendaField.Duration in fields) agendaDuration(event) else ""
    val countdown = if (ClockAgendaField.Countdown in fields) eventRemainingText(event, now) else ""

    val leftCount = fields.count { it !in rightAligned }
    ClockMetadataLayout(fields.map { it == ClockAgendaField.Title || it == ClockAgendaField.Location || it == ClockAgendaField.Calendar },
        modifier.then(if (wrapWidth) Modifier else Modifier.fillMaxWidth())
        .testTag("schedule_line"), wrapWidth, if (centered) ClockAlignment.Center else ClockAlignment.Left,
        rightStart = if (centered) null else leftCount) {
        fields.forEachIndexed { index, field ->
            key(field) {
                val draw: @Composable (Modifier) -> Unit = { movement ->
                    val tag = when (field) {
                        ClockAgendaField.Title -> "schedule_title"
                        ClockAgendaField.Countdown -> "schedule_remaining"
                        else -> "schedule_${field.name.lowercase()}"
                    }
                    if (field == ClockAgendaField.Color) {
                        val eventColor = requireNotNull(event.displayColor ?: event.calendarColor)
                        Text(buildAnnotatedString { appendInlineContent("event_color") },
                            modifier = movement.testTag(tag).clearAndSetSemantics {},
                            inlineContent = mapOf("event_color" to InlineTextContent(
                                Placeholder(0.6.em, 0.6.em, PlaceholderVerticalAlign.TextCenter),
                            ) {
                                Box(Modifier.fillMaxSize().background(Color(eventColor).copy(alpha = 1f), CircleShape))
                            }), style = style, maxLines = 1)
                    } else {
                        val value = when (field) {
                            ClockAgendaField.Title -> event.title
                            ClockAgendaField.Countdown -> countdown
                            ClockAgendaField.Time -> timeText
                            ClockAgendaField.Date -> dateText
                            ClockAgendaField.Duration -> durationText
                            ClockAgendaField.Location -> event.location.orEmpty()
                            ClockAgendaField.Calendar -> event.calendarName.orEmpty()
                            ClockAgendaField.Color -> ""
                        }.replace('\n', ' ').replace('\r', ' ')
                        val startsGroup = index == 0 || (!centered && index == leftCount)
                        val separator = if (!startsGroup && fields[index - 1] != ClockAgendaField.Color) "· " else ""
                        Text(separator + value, modifier = movement.testTag(tag),
                            color = color,
                            style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (animatePositions) ClockRegionItemMotion(draw) else draw(Modifier)
            }
        }
    }
}

@Composable
private fun agendaDuration(event: ScheduleEvent): String {
    if (event.isAllDay) {
        val days = ChronoUnit.DAYS.between(event.startsAt.atZone(ZoneOffset.UTC).toLocalDate(),
            event.endsAt.atZone(ZoneOffset.UTC).toLocalDate()).coerceAtLeast(0)
        return stringResource(R.string.clock_agenda_duration_days, days)
    }
    val seconds = Duration.between(event.startsAt, event.endsAt).seconds.coerceAtLeast(0)
    val minutes = seconds / 60 + if (seconds % 60 > 0) 1 else 0
    return if (minutes >= 60) stringResource(R.string.clock_agenda_duration_hours_minutes, minutes / 60, minutes % 60)
        else stringResource(R.string.clock_agenda_duration_minutes, minutes)
}

/** Keep short metadata readable first; long titles, places and calendar names take remaining space. */
@Composable
internal fun ClockMetadataLayout(
    flexible: List<Boolean>,
    modifier: Modifier,
    wrapWidth: Boolean,
    alignment: ClockAlignment,
    rightStart: Int? = null,
    remainingWidthIndex: Int? = null,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 5.dp.roundToPx()
        val gaps = gap.toLong() * (flexible.size - 1).coerceAtLeast(0)
        val widths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtLeast(1) }
        val naturalWidth = widths.sumOf { it.toLong() }
        val layoutWidth = if (wrapWidth) constraints.constrainWidth(
            (naturalWidth + gaps).coerceAtMost(constraints.maxWidth.toLong()).toInt(),
        ) else constraints.maxWidth
        val available = (layoutWidth.toLong() - gaps).coerceAtLeast(0)
        val total = naturalWidth.coerceAtLeast(1)
        val fixed = widths.indices.filterNot { flexible[it] }.sumOf { widths[it].toLong() }
        val flexibleTotal = (total - fixed).coerceAtLeast(1)
        val allocatedWidths = widths.mapIndexed { index, width ->
            when {
                total <= available -> width
                fixed < available && flexible.any { it } -> if (flexible[index])
                    (widths[index].toLong() * (available - fixed) / flexibleTotal).toInt() else widths[index]
                else -> (widths[index].toLong() * available / total).toInt()
            }
        }.toMutableList()
        remainingWidthIndex?.takeIf { it in allocatedWidths.indices }?.let { index ->
            // Flow content needs the available space, not an exact intrinsic estimate:
            // fractional-density spacing can round differently during its real measurement.
            allocatedWidths[index] += (available - allocatedWidths.sumOf { it.toLong() }).coerceAtLeast(0).toInt()
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = allocatedWidths[index]))
        }
        val baselines = placeables.map { it[FirstBaseline].takeUnless { line -> line == AlignmentLine.Unspecified } ?: it.height }
        val above = baselines.maxOrNull() ?: 0
        val below = placeables.indices.maxOfOrNull { placeables[it].height - baselines[it] } ?: 0
        layout(layoutWidth, constraints.constrainHeight(above + below), mapOf(FirstBaseline to above)) {
            val remaining = (layoutWidth.toLong() - placeables.sumOf { it.width.toLong() } - gaps)
                .coerceAtLeast(0).toInt()
            var x = when (alignment) {
                ClockAlignment.Left -> 0
                ClockAlignment.Center -> remaining / 2
                ClockAlignment.Right -> remaining
            }
            placeables.forEachIndexed { index, placeable ->
                if (index == rightStart) {
                    x = (layoutWidth - placeables.drop(index).sumOf { it.width } -
                        gap * (placeables.size - index - 1).coerceAtLeast(0)).coerceAtLeast(0)
                }
                placeable.place(x, above - baselines[index])
                x += placeable.width + gap
            }
        }
    }
}
