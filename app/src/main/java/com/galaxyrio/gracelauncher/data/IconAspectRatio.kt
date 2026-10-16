package com.galaxyrio.gracelauncher.data

import kotlin.math.ln
import kotlin.math.pow

internal fun normalizeSquareAspectRatio(ratio: Float): Float =
    ratio.takeIf { it.isFinite() }?.coerceIn(.5f, 2f) ?: 1f

/** Reciprocal endpoints give portrait and landscape the same amount of travel. */
internal fun squareAspectRatioFromSlider(position: Float): Float =
    2f.pow((position.takeIf { it.isFinite() }?.coerceIn(-100f, 100f) ?: 0f) / 100f)

internal fun squareAspectRatioToSlider(ratio: Float): Float =
    (ln(normalizeSquareAspectRatio(ratio)) / ln(2f) * 100f).coerceIn(-100f, 100f)

/** Width and height in a unit square. The longer edge keeps the configured icon size. */
internal fun squareIconDimensions(ratio: Float): Pair<Float, Float> =
    normalizeSquareAspectRatio(ratio).let { if (it >= 1f) 1f to 1f / it else it to 1f }
