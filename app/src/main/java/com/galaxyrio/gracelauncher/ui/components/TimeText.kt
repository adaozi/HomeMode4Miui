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
// perf3（功耗专项）：通知时长 60s 循环 lifecycle-aware 化（桌面退后台挂起）
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by produceState(System.currentTimeMillis(), postedAt) {
        // perf3（功耗专项）：每条带通知的收藏行各有一个 60s 循环，桌面退后台后
        // 仍在唤醒。repeatOnLifecycle(STARTED) 包裹 —— ≤ CREATED 挂起，回前台重启；
        // 宿主生命周期未同步（hook 未命中）时恒为 RESUMED，行为同旧版。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = System.currentTimeMillis(); delay(60_000) }
        }
    }
    val minutes = ((now - postedAt).coerceAtLeast(0) / 60_000)
    return when {
        minutes < 1 -> stringResource(R.string.notification_now)
        minutes < 60 -> stringResource(R.string.notification_minutes, minutes)
        minutes < 1440 -> stringResource(R.string.notification_hours, minutes / 60)
        else -> stringResource(R.string.notification_days, minutes / 1440)
    }
}
