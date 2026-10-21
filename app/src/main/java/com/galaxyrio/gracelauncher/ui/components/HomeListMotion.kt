package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** x is opacity; y is vertical travel in units of the page's lift distance. */
@Stable
internal class HomeListMotion(initialDrawerOpen: Boolean) {
    val home = Animatable(if (initialDrawerOpen) Offset(0f, -1f) else Offset(1f, 0f), Offset.VectorConverter)
    val drawer = Animatable(if (initialDrawerOpen) Offset(1f, 0f) else Offset(0f, 1f), Offset.VectorConverter)
    val isRunning: Boolean get() = home.isRunning || drawer.isRunning

    suspend fun snapTo(drawerOpen: Boolean) {
        home.snapTo(if (drawerOpen) Offset(0f, -1f) else Offset(1f, 0f))
        drawer.snapTo(if (drawerOpen) Offset(1f, 0f) else Offset(0f, -1f))
    }

    suspend fun animateTo(drawerOpen: Boolean) = coroutineScope {
        val incoming = if (drawerOpen) drawer else home
        val outgoing = if (drawerOpen) home else drawer
        if (incoming.value == Offset(1f, 0f) && outgoing.value.x == 0f) {
            outgoing.snapTo(Offset(0f, -1f))
            return@coroutineScope
        }
        // Re-enter from below only once fully hidden. Interrupted transitions
        // continue from their current pose rather than jumping across the screen.
        if (incoming.value.x == 0f) incoming.snapTo(Offset(0f, 1f))
        launch { incoming.animateTo(Offset(1f, 0f), tween(260, easing = FastOutSlowInEasing)) }
        launch { outgoing.animateTo(Offset(0f, -1f), tween(260, easing = FastOutSlowInEasing)) }
    }
}
