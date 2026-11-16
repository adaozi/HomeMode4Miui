package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.CountdownUnit
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.eventCountdown
import com.galaxyrio.gracelauncher.data.tasks.TaskItem
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlinx.coroutines.delay

@Composable
fun eventRemainingText(event: ScheduleEvent, now: Instant): String {
    val countdown = eventCountdown(event, now)
    return when (countdown.unit) {
        CountdownUnit.LessThanMinute -> stringResource(R.string.event_soon)
        CountdownUnit.Minutes -> stringResource(R.string.event_in_minutes, countdown.value)
        CountdownUnit.Hours -> if (countdown.minutes == 0) {
            stringResource(R.string.event_in_hours, countdown.value)
        } else {
            stringResource(R.string.event_in_hours_minutes, countdown.value, countdown.minutes)
        }
        CountdownUnit.Days -> stringResource(R.string.event_in_days, countdown.value)
        CountdownUnit.Ongoing -> stringResource(R.string.event_ongoing)
        CountdownUnit.AllDay -> stringResource(R.string.all_day)
    }
}

@Composable
internal fun taskRemainingText(task: TaskItem, now: Instant): String {
    val due = task.dueAt ?: return ""
    if (task.dueAllDay) {
        val zone = ZoneId.systemDefault()
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), due.atZone(zone).toLocalDate())
        return when {
            days == 0L -> stringResource(R.string.tasks_due_today)
            days > 0L -> stringResource(R.string.event_in_days, days)
            else -> stringResource(R.string.tasks_overdue_days, -days)
        }
    }
    val seconds = Duration.between(now, due).seconds
    val distance = abs(seconds)
    val minutes = (distance / 60 + if (distance % 60 > 0) 1 else 0).coerceAtLeast(1)
    return when {
        distance < 60 -> stringResource(if (seconds < 0) R.string.tasks_overdue_now else R.string.tasks_due_now)
        minutes < 60 -> stringResource(if (seconds < 0) R.string.tasks_overdue_minutes else R.string.event_in_minutes, minutes)
        minutes < 1440 -> stringResource(if (seconds < 0) R.string.tasks_overdue_hours else R.string.event_in_hours, minutes / 60)
        else -> stringResource(if (seconds < 0) R.string.tasks_overdue_days else R.string.event_in_days, minutes / 1440)
    }
}

@Composable
internal fun notificationAge(postedAt: Long): String {
    val now by produceState(System.currentTimeMillis(), postedAt) {
        while (true) { value = System.currentTimeMillis(); delay(60_000) }
    }
    val minutes = ((now - postedAt).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> stringResource(R.string.notification_now)
        minutes < 60 -> stringResource(R.string.notification_minutes, minutes)
        minutes < 1440 -> stringResource(R.string.notification_hours, minutes / 60)
        else -> stringResource(R.string.notification_days, minutes / 1440)
    }
}
