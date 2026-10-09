package com.galaxyrio.gracelauncher.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.galaxyrio.gracelauncher.data.weather.BreezyWeatherRepository
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** The official notifier discovers manifest receivers in Breezy's External modules settings. */
class BreezyWeatherUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BreezyWeatherRepository.UPDATE_ACTION) BreezyWeatherUpdates.invalidate()
    }
}

internal object BreezyWeatherUpdates {
    private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events = updates.asSharedFlow()

    // Broadcast payloads are never accepted as weather data. They only invalidate
    // the trusted provider snapshot. The consumer coalesces bursts with a trailing refresh.
    fun invalidate() {
        updates.tryEmit(Unit)
    }
}
