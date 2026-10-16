package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/** A drag edits a local draft; only a successful drop writes the favorite order. */
@Stable
internal class FavoritesReorderState(
    private val list: LazyListState,
    initialKeys: List<String>,
    private val commit: (List<String>) -> Unit,
    private val canMove: (String, String) -> Boolean = { _, _ -> true },
    private val feedback: () -> Unit,
) {
    var keys by mutableStateOf(initialKeys)
        private set
    var draggingKey by mutableStateOf<String?>(null)
        private set
    var drop by mutableStateOf<ReorderDrop?>(null)
        private set
    private var originalKeys = initialKeys
    private var top by mutableFloatStateOf(0f)
    // Rebuild only when items actually change order, not on each pointer move.
    private val indices by derivedStateOf { keys.mapIndexed { index, value -> itemKey(value) to index }.toMap() }

    private val draggedItem
        get() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey(draggingKey) }

    val translation: Float
        get() = draggedItem?.let { top - it.offset } ?: 0f

    fun synchronize(updated: List<String>) {
        if (updated != originalKeys) {
            draggingKey = null
            drop = null
            keys = updated
            originalKeys = updated
        }
    }

    fun start(key: String) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey(key) } ?: return
        originalKeys = keys
        drop = null
        draggingKey = key
        top = item.offset.toFloat()
        feedback()
    }

    fun drag(delta: Float) {
        if (draggingKey == null) return
        top += delta
        moveUnderFinger()
    }

    fun finish(cancelled: Boolean) {
        val key = draggingKey ?: return
        drop = ReorderDrop(key, translation)
        draggingKey = null
        if (cancelled) keys = originalKeys
        else if (keys != originalKeys) {
            originalKeys = keys
            commit(keys)
        }
    }

    fun settled(value: ReorderDrop) { if (drop === value) drop = null }

    fun moveBy(key: String, delta: Int): Boolean {
        if (draggingKey != null) return false
        val from = keys.indexOf(key)
        val to = from + delta
        if (from < 0 || to !in keys.indices || !canMove(key, keys[to])) return false
        move(from, to)
        originalKeys = keys
        commit(keys)
        return true
    }

    /** Frame-paced edge scrolling also works while the finger is stationary. */
    suspend fun autoScroll(edge: Float, maxStep: Float) {
        val item = draggedItem ?: return
        val layout = list.layoutInfo
        val end = layout.viewportEndOffset - layout.afterContentPadding
        val start = layout.viewportStartOffset + layout.beforeContentPadding
        val last = layout.visibleItemsInfo.firstOrNull { it.key == itemKey(keys.lastOrNull()) }
        val first = layout.visibleItemsInfo.firstOrNull { it.key == itemKey(keys.firstOrNull()) }
        val step = when {
            top < start + edge && (first == null || first.offset < start) ->
                -maxStep * ((start + edge - top) / edge).coerceIn(0f, 1f)
            top + item.size > end - edge && (last == null || last.offset + last.size > end) ->
                maxStep * ((top + item.size - end + edge) / edge).coerceIn(0f, 1f)
            else -> 0f
        }
        if (step != 0f) {
            list.scrollBy(step)
            moveUnderFinger()
        }
    }

    private fun moveUnderFinger() {
        val key = draggingKey ?: return
        val item = draggedItem ?: return
        val center = top + item.size / 2f
        val from = keys.indexOf(key)
        val indices = indices
        val target = list.layoutInfo.visibleItemsInfo.asSequence()
            .filter { it.key in indices && canMove(key, keys[indices.getValue(it.key as String)]) }
            .minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        val to = indices[target.key as? String ?: return] ?: return
        val targetCenter = target.offset + target.size / 2f
        if ((to > from && center >= targetCenter) || (to < from && center <= targetCenter)) {
            move(from, to)
            feedback()
        }
    }

    private fun move(from: Int, to: Int) {
        // Retain the scroll position by index during reordering, rather than
        // letting LazyColumn follow a first-visible item's changing key.
        list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
        keys = keys.toMutableList().apply { add(to, removeAt(from)) }
    }

    companion object {
        fun itemKey(key: String?) = "selected:$key"
    }
}

internal class ReorderDrop(val key: String, val translation: Float)

/** Read the live position in graphicsLayer. Only a drop starts a spring; do not
 * recompose the row and send a new snap-animation target for every pointer move. */
@Composable
internal fun rememberReorderTranslation(reorder: FavoritesReorderState, key: String): State<Float> {
    val drop = reorder.drop?.takeIf { it.key == key }
    val settling = remember(drop) { Animatable(drop?.translation ?: 0f) }
    LaunchedEffect(drop) {
        if (drop != null) {
            settling.animateTo(0f)
            reorder.settled(drop)
        }
    }
    return remember(reorder, key, settling) {
        derivedStateOf { if (reorder.draggingKey == key) reorder.translation else settling.value }
    }
}
