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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockAlignment
import com.galaxyrio.gracelauncher.data.ClockTaskField
import com.galaxyrio.gracelauncher.data.ClockTasks
import com.galaxyrio.gracelauncher.data.tasks.TaskItem
import com.galaxyrio.gracelauncher.data.tasks.TaskPriority
import com.galaxyrio.gracelauncher.ui.components.taskRemainingText
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle

internal fun clockTaskFields(task: TaskItem, requested: List<ClockTaskField>): List<ClockTaskField> = requested.filter {
    when (it) {
        ClockTaskField.Title -> task.title.isNotBlank()
        ClockTaskField.Countdown, ClockTaskField.DueDate, ClockTaskField.DueTime -> task.dueAt != null
        ClockTaskField.StartDate -> task.startsAt != null
        ClockTaskField.Priority -> true
        ClockTaskField.List -> task.listName.isNotBlank()
        ClockTaskField.Notes -> task.notes.isNotBlank()
        ClockTaskField.Color -> task.listColor != null
    }
}

@Composable
internal fun ClockTaskContent(
    task: TaskItem, now: Instant, fields: List<ClockTaskField>, settings: ClockTasks, color: Color,
    modifier: Modifier = Modifier, animatePositions: Boolean = false, wrapWidth: Boolean = false,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateLocale = remember(locale, settings.useLatinLetters) { clockCalendarLocale(locale, settings.useLatinLetters) }
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val zone = ZoneId.systemDefault()
    val dateFormat = remember(dateLocale, zone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(dateLocale, "MMMEd"), dateLocale)
            .withDecimalStyle(DecimalStyle.of(dateLocale)).withZone(zone)
    }
    val timeFormat = remember(locale, use24Hour, zone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm"), locale)
            .withDecimalStyle(DecimalStyle.of(locale)).withZone(zone)
    }
    val typography = settings.typography
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = rememberAppFontFamily(typography.fontId), fontSize = typography.size.sp,
        lineHeight = (typography.size * 1.5f).sp, fontWeight = FontWeight(typography.weight),
        shadow = clockFontShadow(typography.fontShadow),
    )
    val leftCount = fields.count { it !in settings.rightAligned }
    ClockMetadataLayout(fields.map { it == ClockTaskField.Title || it == ClockTaskField.List || it == ClockTaskField.Notes },
        modifier.then(if (wrapWidth) Modifier else Modifier.fillMaxWidth()).testTag("task_line:${task.id}"),
        wrapWidth, if (settings.centered) ClockAlignment.Center else ClockAlignment.Left,
        rightStart = if (settings.centered) null else leftCount) {
        fields.forEachIndexed { index, field ->
            key(field) {
                val draw: @Composable (Modifier) -> Unit = { movement ->
                    if (field == ClockTaskField.Color) {
                        Text(buildAnnotatedString { appendInlineContent("task_color") },
                            modifier = movement.clearAndSetSemantics {},
                            inlineContent = mapOf("task_color" to InlineTextContent(
                                Placeholder(0.6.em, 0.6.em, PlaceholderVerticalAlign.TextCenter),
                            ) {
                                Box(Modifier.fillMaxSize().background(Color(requireNotNull(task.listColor)).copy(alpha = 1f), CircleShape))
                            }), style = style, maxLines = 1)
                    } else {
                        val value = when (field) {
                            ClockTaskField.Title -> task.title
                            ClockTaskField.Countdown -> taskRemainingText(task, now)
                            ClockTaskField.DueDate -> task.dueAt?.let(dateFormat::format).orEmpty()
                            ClockTaskField.DueTime -> if (task.dueAllDay) stringResource(R.string.all_day)
                                else task.dueAt?.let(timeFormat::format).orEmpty()
                            ClockTaskField.StartDate -> task.startsAt?.let {
                                dateFormat.format(it) + if (task.startAllDay) "" else " " + timeFormat.format(it)
                            }.orEmpty()
                            ClockTaskField.Priority -> stringResource(when (task.priority) {
                                TaskPriority.High -> R.string.tasks_priority_high
                                TaskPriority.Medium -> R.string.tasks_priority_medium
                                TaskPriority.Low -> R.string.tasks_priority_low
                                TaskPriority.None -> R.string.tasks_priority_none
                            })
                            ClockTaskField.List -> task.listName
                            ClockTaskField.Notes -> task.notes
                            ClockTaskField.Color -> ""
                        }.replace('\n', ' ').replace('\r', ' ')
                        val startsGroup = index == 0 || (!settings.centered && index == leftCount)
                        val separator = if (!startsGroup && fields[index - 1] != ClockTaskField.Color) "· " else ""
                        Text(separator + value, modifier = movement.testTag("task_${field.name.lowercase()}:${task.id}"),
                            color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (animatePositions) ClockRegionItemMotion(draw) else draw(Modifier)
            }
        }
    }
}
