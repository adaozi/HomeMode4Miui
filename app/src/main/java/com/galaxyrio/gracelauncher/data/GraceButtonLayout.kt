package com.galaxyrio.gracelauncher.data

import kotlin.math.abs

enum class GraceButtonAlignment { Left, Center, Right }

enum class GraceButtonVisibility {
    Favorites, AppList, Both;

    fun includes(appList: Boolean): Boolean = this == Both || this == if (appList) AppList else Favorites
}

/** Distances are dp from the safe viewport's sides/bottom, not from the icon's center. */
data class GraceButtonLayout(
    val alignment: GraceButtonAlignment = GraceButtonAlignment.Right,
    val sideMargin: Float = 56f,
    val bottomMargin: Float = 22f,
    val size: Float = 54f,
    val showOn: GraceButtonVisibility = GraceButtonVisibility.Favorites,
) {
    fun normalized() = copy(
        sideMargin = sideMargin.takeIf { it.isFinite() }?.coerceIn(0f, 2000f) ?: 56f,
        bottomMargin = bottomMargin.takeIf { it.isFinite() }?.coerceIn(0f, 2000f) ?: 22f,
        size = size.takeIf { it.isFinite() }?.coerceIn(32f, 120f) ?: 54f,
    )

    /** Keep the whole button in roughly the lower half, independently of the preview's mask. */
    fun maxBottomMargin(height: Float, topInset: Float = 0f, bottomInset: Float = 0f): Float =
        (height - bottomInset - maxOf(height / 2f, topInset) - normalized().size).coerceIn(0f, 2000f)

    /** Shared by the desktop and its 1:1 preview, including small/folded windows. */
    fun bounds(width: Float, height: Float, topInset: Float = 0f, bottomInset: Float = 0f): GraceButtonBounds {
        val value = normalized()
        val safeWidth = width.coerceAtLeast(0f)
        val safeTop = topInset.coerceIn(0f, height.coerceAtLeast(0f))
        val safeBottom = (height - bottomInset).coerceAtLeast(safeTop)
        val side = minOf(value.size, safeWidth, safeBottom - safeTop)
        val travelX = safeWidth - side
        val left = when (value.alignment) {
            GraceButtonAlignment.Left -> value.sideMargin
            GraceButtonAlignment.Center -> travelX / 2f
            GraceButtonAlignment.Right -> travelX - value.sideMargin
        }.coerceIn(0f, travelX)
        val bottomMargin = value.bottomMargin.coerceAtMost(value.maxBottomMargin(height, topInset, bottomInset))
        return GraceButtonBounds(left, (safeBottom - side - bottomMargin).coerceIn(safeTop, safeBottom - side), side)
    }

    /** Drag freely; snap only near center, otherwise anchor to the nearer side. */
    fun movedTo(left: Float, top: Float, width: Float, height: Float,
        topInset: Float = 0f, bottomInset: Float = 0f): GraceButtonLayout {
        val value = normalized()
        val bounds = value.bounds(width, height, topInset, bottomInset)
        val travelX = (width - bounds.size).coerceAtLeast(0f)
        val x = left.coerceIn(0f, travelX)
        val alignment = when {
            abs(x - travelX / 2f) <= 8f -> GraceButtonAlignment.Center
            x < travelX / 2f -> GraceButtonAlignment.Left
            else -> GraceButtonAlignment.Right
        }
        return value.copy(alignment = alignment,
            sideMargin = when (alignment) {
                GraceButtonAlignment.Left -> x
                GraceButtonAlignment.Right -> travelX - x
                GraceButtonAlignment.Center -> value.sideMargin
            },
            bottomMargin = (height - bottomInset - bounds.size - top)
                .coerceIn(0f, value.maxBottomMargin(height, topInset, bottomInset)),
        )
    }
}

data class GraceButtonBounds(val left: Float, val top: Float, val size: Float)
