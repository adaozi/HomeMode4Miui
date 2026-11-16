package com.galaxyrio.gracelauncher.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import java.time.format.DecimalStyle
import java.util.Locale
import kotlin.math.ceil

/** Reserve a row clock's natural width without changing its proportional glyphs. */
@Composable
internal fun rememberFixedClockWidth(
    textStyle: TextStyle,
    use24Hour: Boolean,
    showLeadingZero: Boolean,
    showColon: Boolean,
    showSeconds: Boolean,
    locale: Locale,
): Dp {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val measurementStyle = textStyle.copy(
        color = Color.Black,
        background = Color.Unspecified,
        shadow = null,
        textDecoration = null,
        drawStyle = null,
    )
    // Imported fonts load asynchronously. A family ID alone would retain the
    // fallback font's reservation after the selected typeface becomes available.
    val resolvedTypeface = resolver.resolve(
        measurementStyle.fontFamily,
        measurementStyle.fontWeight ?: FontWeight.Normal,
        measurementStyle.fontStyle ?: FontStyle.Normal,
        measurementStyle.fontSynthesis ?: FontSynthesis.All,
    ).value
    return remember(measurer, measurementStyle, resolvedTypeface, density.density, density.fontScale,
        layoutDirection, locale, use24Hour, showLeadingZero, showColon, showSeconds) {
        val zero = DecimalStyle.of(locale).zeroDigit
        fun digits(value: Int, leadingZero: Boolean): String {
            val ascii = value.toString().let { if (leadingZero) it.padStart(2, '0') else it }
            return ascii.map { (zero.code + (it - '0')).toChar() }.joinToString("")
        }

        // Reuse numeric samples within this calculation, without filling the
        // text measurer's LRU with one-time combinations or measuring every second.
        val widths = mutableMapOf<String, Float>()
        fun width(value: String): Float = widths.getOrPut(value) {
            measurer.measure(
                text = value,
                style = measurementStyle,
                softWrap = false,
                maxLines = 1,
                density = density,
                layoutDirection = layoutDirection,
                fontFamilyResolver = resolver,
            ).multiParagraph.maxIntrinsicWidth
        }

        val hours = if (use24Hour) 0..23 else 1..12
        val hourWidth = hours.maxOf { width(digits(it, showLeadingZero)) }
        val pairWidth = (0..59).maxOf { width(digits(it, true)) }
        val separator = if (showColon) ":" else " "
        val separatorWidth = width(separator)
        val singleDigits = (0..9).map { digits(it, false) }
        val singleWidths = singleDigits.map(::width)
        // Account for positive kerning across each separator. Negative kerning
        // may save room, but must not reduce the reservation for other times.
        val boundaryExpansion = (0..9).maxOf { left ->
            (0..5).maxOf { right ->
                width(singleDigits[left] + separator + singleDigits[right]) -
                    singleWidths[left] - separatorWidth - singleWidths[right]
            }
        }.coerceAtLeast(0f)
        val separators = if (showSeconds) 2 else 1
        val widthPx = hourWidth + pairWidth * separators +
            (separatorWidth + boundaryExpansion) * separators
        with(density) { ceil(widthPx).toInt().coerceAtLeast(0).toDp() }
    }
}
