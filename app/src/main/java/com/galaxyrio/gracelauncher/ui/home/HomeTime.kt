package com.galaxyrio.gracelauncher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.delay

@Composable
internal fun rememberHomeTime(clock: Clock = Clock.systemUTC(), showSeconds: Boolean = false): State<Instant> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val interval = if (showSeconds) 1_000L else 60_000L
    return produceState(initialValue = clock.instant(), lifecycle, clock, interval) {
        // Coroutine delays use uptime, which stops during deep sleep. Restart on
        // resume so the clock, date and agenda don't wait for an old delay to end.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val now = clock.instant()
                value = now
                delay(interval - now.toEpochMilli() % interval)
            }
        }
    }
}
