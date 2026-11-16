package com.galaxyrio.gracelauncher.ui.home

/** Positions refer to visible ink edges, not font advance widths or line boxes. */
internal fun clockDigitPositions(widths: List<Float>, gap: Float): List<Float> {
    var right = 0f
    val positions = widths.mapIndexed { index, width ->
        val left = if (index == 0) 0f else right + gap
        right = left + width
        left
    }
    val origin = positions.minOrNull() ?: 0f
    return positions.map { it - origin }
}

internal data class ClockInkBox(val width: Float, val top: Float, val bottom: Float)

internal data class ClockGroupPositions(
    val hourX: Float,
    val minuteX: Float,
    val minuteBaseline: Float,
    val colonX: Float? = null,
)

internal fun clockGroupPositions(
    hours: ClockInkBox,
    minutes: ClockInkBox,
    gap: Float,
    stacked: Boolean,
    colonWidth: Float? = null,
    centerLines: Boolean = false,
): ClockGroupPositions {
    if (stacked) {
        val width = maxOf(hours.width, minutes.width)
        return ClockGroupPositions(
            hourX = if (centerLines) (width - hours.width) / 2 else 0f,
            minuteX = if (centerLines) (width - minutes.width) / 2 else 0f,
            minuteBaseline = hours.bottom + gap - minutes.top,
        )
    }
    // Adding a colon divides the SAME independent gap equally around its ink.
    return ClockGroupPositions(
        hourX = 0f,
        minuteX = hours.width + gap + (colonWidth ?: 0f),
        minuteBaseline = 0f,
        colonX = colonWidth?.let { hours.width + gap / 2 },
    )
}
