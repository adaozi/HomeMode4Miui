package com.galaxyrio.gracelauncher.data.media

import android.media.session.PlaybackState
import androidx.compose.ui.graphics.ImageBitmap

enum class MediaCommand { TogglePlayback, Previous, Next, OpenPlayer }

const val IdleMediaSessionId = "launcher:idle"

data class NowPlaying(
    val sessionId: String,
    val playerName: String,
    val title: String?,
    val artist: String?,
    val artwork: ImageBitmap? = null,
    val playing: Boolean,
    val canToggle: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val revision: Long = 0,
)

data class MediaSnapshot(val hasAccess: Boolean = false, val nowPlaying: NowPlaying? = null, val failed: Boolean = false)

/** Only ranks notified players. Notification presence, not playback state, controls visibility. */
internal fun playbackPriority(state: Int): Int = when (state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_FAST_FORWARDING, PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> 2
    PlaybackState.STATE_PAUSED -> 1
    else -> 0
}
