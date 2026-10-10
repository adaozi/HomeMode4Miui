/*
 * 移植自 GraceLauncher v1.1.8（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 上游同步 2026-10-11（PORTING_GraceLauncher.md §9）：按上游 v1.1.8 逐字更新。
 */
package com.galaxyrio.gracelauncher.ui.components

/** Direction changes control the destination, never the animation's progress. */
internal class PopupSwipeIntent(private val reversalSlop: Float, private val firstItemSlop: Float = Float.POSITIVE_INFINITY) {
    var revealed = false
        private set
    var expanded = false
        private set
    private var reverseTravel = 0f
    private var openingFirst = false
    private var firstTravel = 0f
    val opensFirst: Boolean get() = openingFirst && firstTravel >= firstItemSlop
    /** Live leftward travel, including before the action's commit threshold. */
    val firstItemPullDistance: Float get() = if (openingFirst) firstTravel else 0f

    // The row recognizer has already crossed Android's horizontal touch slop.
    fun drag(amount: Float): Boolean? {
        // Only a gesture that starts leftwards can launch an item. Reversing an
        // already revealed pop-up always keeps its existing close/reopen behavior.
        if (!revealed && amount < 0f && firstItemSlop.isFinite()) openingFirst = true
        if (openingFirst) {
            firstTravel = (firstTravel - amount).coerceAtLeast(0f)
            return null
        }
        if (!revealed) {
            if (amount <= 0f) return null
            revealed = true
            expanded = true
            return true
        }
        val reversal = if (expanded) -amount else amount
        reverseTravel = (reverseTravel + reversal).coerceAtLeast(0f)
        if (reverseTravel < reversalSlop) return null
        reverseTravel = 0f
        expanded = !expanded
        return expanded
    }

    fun reset() {
        revealed = false
        expanded = false
        reverseTravel = 0f
        openingFirst = false
        firstTravel = 0f
    }
}

/** Same rubber-band curve as Grace button: it follows the finger with increasing resistance. */
internal fun resistedPopupPull(distance: Float, resistanceDistance: Float): Float {
    val pull = distance.coerceAtLeast(0f)
    return -pull * (0.42f / (1f + pull / resistanceDistance.coerceAtLeast(1f)))
}
