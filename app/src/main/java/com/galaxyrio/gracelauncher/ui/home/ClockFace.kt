@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.galaxyrio.gracelauncher.ui.home

import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galaxyrio.gracelauncher.data.AppFont
import com.galaxyrio.gracelauncher.data.ClockPresetFont
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.ui.theme.LauncherFontFamily
import com.galaxyrio.gracelauncher.ui.theme.family
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Shared, ink-bounded rendering for the desktop, editor and style thumbnails. */
@Composable
internal fun ClockFace(
    time: String,
    style: ClockStyle,
    color: Color,
    modifier: Modifier = Modifier,
    fontScale: Float = 1f,
    textAlign: TextAlign = TextAlign.Start,
    digitColors: List<Color>? = null,
) {
    val face = style.face
    // Applying a preset must keep its exact font and weight synthesis in the editable layout.
    val presetFont = style.layout.presetFont ?: when (face.fontId) {
        AppFont.Sacramento.id -> ClockPresetFont.Sacramento
        AppFont.Bokor.id -> ClockPresetFont.Bokor
        AppFont.Plaster.id -> ClockPresetFont.Plaster
        AppFont.Monoton.id -> ClockPresetFont.Monoton
        AppFont.LuckiestGuy.id -> ClockPresetFont.LuckiestGuy
        else -> null
    }
    val fontContext = com.galaxyrio.gracelauncher.ui.theme.LocalFontAssetContext.current
    val preset = presetFont
    val family = if (preset != null && fontContext != null) preset.family(fontContext)
        else rememberAppFontFamily(face.fontId, context = fontContext)
    val scale = fontScale.coerceIn(0.1f, 1f)
    ClockGlyphContent(
        value = time, semanticsText = clockFaceText(time, style), family = family, weight = FontWeight(face.weight),
        fontSize = face.size * scale, digitSpacing = face.letterSpacing * scale, groupSpacing = face.hourMinuteSpacing * scale,
        stacked = style.layout.stacked, showColon = face.showColon, separate = face.separateDigits,
        color = color, shadow = clockFontShadow(face.fontShadow, scale), modifier = modifier, textAlign = textAlign,
        digitColors = digitColors, equalWidthDigits = face.equalWidthDigits, centerDigits = face.centerDigits,
    )
}

/** Compact times follow their row typography; legacy layouts retain their saved glyph spacing. */
@Composable
internal fun ClockTimeLabel(time: String, style: ClockStyle, textStyle: TextStyle, color: Color, modifier: Modifier = Modifier,
    digitColors: List<Color>? = null, useStandardSpacing: Boolean = false) {
    val face = style.face
    if (useStandardSpacing) {
        val displayTime = if (face.showColon) time else time.replace(':', ' ')
        val colon = time.indexOf(':')
        val hourDigits = time.take(colon.coerceAtLeast(0)).count(Char::isDigit)
        var hourSlot = (2 - hourDigits).coerceAtLeast(0)
        var followingSlot = 2
        val coloredTime = buildAnnotatedString {
            append(displayTime)
            time.forEachIndexed { index, character ->
                if (character.isDigit()) {
                    val slot = if (index < colon) hourSlot++ else followingSlot++
                    digitColors?.getOrNull(slot)?.let { addStyle(SpanStyle(color = it), index, index + 1) }
                }
            }
        }
        Text(coloredTime, modifier, color = color, style = textStyle,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    val size = textStyle.fontSize.value
    val scale = size / face.size
    ClockGlyphContent(
        value = time, semanticsText = clockFaceText(time, style), family = textStyle.fontFamily ?: LauncherFontFamily,
        weight = textStyle.fontWeight ?: FontWeight.Normal, fontSize = size,
        digitSpacing = face.letterSpacing * scale, groupSpacing = face.hourMinuteSpacing * scale,
        stacked = false, showColon = face.showColon, separate = false,
        color = color, shadow = clockFontShadow(face.fontShadow, scale), modifier = modifier,
        digitColors = digitColors,
    )
}

@Composable
internal fun clockFontShadow(strength: Int, scale: Float = 1f): Shadow {
    if (strength <= 0) return Shadow.None
    val amount = strength.coerceIn(0, 24)
    val density = LocalDensity.current
    return Shadow(
        color = Color.Black.copy(alpha = 0.12f + 0.38f * amount / 24f),
        offset = Offset(0f, with(density) { (amount * scale / 8f).dp.toPx() }),
        blurRadius = with(density) { (amount * scale).dp.toPx() },
    )
}

@Composable
private fun ClockGlyphContent(
    value: String,
    semanticsText: String,
    family: FontFamily,
    weight: FontWeight,
    fontSize: Float,
    digitSpacing: Float,
    groupSpacing: Float,
    stacked: Boolean,
    showColon: Boolean,
    separate: Boolean,
    color: Color,
    shadow: Shadow,
    modifier: Modifier,
    textAlign: TextAlign = TextAlign.Start,
    digitColors: List<Color>? = null,
    equalWidthDigits: Boolean = false,
    centerDigits: Boolean = false,
) {
    val typeface = LocalFontFamilyResolver.current.resolve(family, weight, FontStyle.Normal, FontSynthesis.All).value as Typeface
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.sp.toPx() }
    val digitGapPx = with(density) { digitSpacing.sp.toPx() }
    val groupGapPx = with(density) { groupSpacing.sp.toPx() }
    val centered = textAlign == TextAlign.Center
    BoxWithConstraints(
        modifier.semantics { text = AnnotatedString(semanticsText) },
        contentAlignment = when (textAlign) {
            TextAlign.Center -> Alignment.TopCenter
            TextAlign.End -> Alignment.TopEnd
            TextAlign.Right -> AbsoluteAlignment.TopRight
            TextAlign.Left -> AbsoluteAlignment.TopLeft
            else -> Alignment.TopStart
        },
    ) {
        val availableWidth = constraints.maxWidth.toFloat()
        val availableHeight = constraints.maxHeight.toFloat()
        val drawing = remember(value, typeface, fontPx, digitGapPx, groupGapPx, stacked, showColon, centered,
            availableWidth, availableHeight, equalWidthDigits, centerDigits) {
            measureClockGlyphs(value, typeface, fontPx, digitGapPx, groupGapPx, stacked, showColon, centered,
                availableWidth, availableHeight, equalWidthDigits, centerDigits)
        }
        Layout(content = {}, modifier = Modifier.drawBehind { drawClockGlyphs(drawing, color, shadow, separate, digitColors) }) { _, limits ->
            val width = limits.constrainWidth(ceil(drawing.bounds.width()).toInt().coerceAtLeast(0))
            val height = limits.constrainHeight(ceil(drawing.bounds.height()).toInt().coerceAtLeast(0))
            layout(width, height, mapOf(
                FirstBaseline to (-drawing.bounds.top).roundToInt(),
                LastBaseline to (drawing.lastBaseline - drawing.bounds.top).roundToInt(),
            )) {}
        }
    }
}

internal fun clockFaceText(time: String, style: ClockStyle): String = when {
    style.layout.stacked -> time.split(':').take(2).joinToString("\n")
    style.face.showColon -> time
    else -> time.replace(':', ' ')
}

/** Keep localized digits while applying the same leading-zero choice to 12- and 24-hour clocks. */
internal fun formatHomeClock(
    now: Instant,
    use24Hour: Boolean,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
    leadingZero: Boolean = true,
    showSeconds: Boolean = false,
): String = DateTimeFormatter.ofPattern((if (use24Hour) {
    if (leadingZero) "HH" else "H"
} else {
    if (leadingZero) "hh" else "h"
}) + (if (showSeconds) ":mm:ss" else ":mm"), locale)
    .withDecimalStyle(DecimalStyle.of(locale)).withZone(zone).format(now)
