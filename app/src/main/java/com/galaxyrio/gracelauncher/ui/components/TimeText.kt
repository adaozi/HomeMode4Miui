package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.CountdownUnit
import com.galaxyrio.gracelauncher.data.ScheduleEvent
import com.galaxyrio.gracelauncher.data.eventCountdown
import java.time.Instant
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
