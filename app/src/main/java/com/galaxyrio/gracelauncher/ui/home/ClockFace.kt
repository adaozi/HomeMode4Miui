/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/home/ClockFace.kt
 * P1 移植：逐字拷贝，未改动。（依赖 theme 包的 LauncherFontFamily / family() 扩展，
 *   已随 P1 LauncherFonts 完整版就位。）
 */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.galaxyrio.gracelauncher.ui.home

import android.graphics.Typeface
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galaxyrio.gracelauncher.data.ClockFontStore
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.ui.theme.LauncherFontFamily
import com.galaxyrio.gracelauncher.ui.theme.family
import com.homemode4miui.grace.LocalHostContext
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
) {
    val face = style.face
    val context = LocalContext.current // 模块 Context（GraceHomeRoot 覆盖）：preset 字体走模块 assets
    // ClockFontStore 的 filesDir 必须留在宿主数据目录（宿主进程可写；模块目录属另一 UID）
    val hostContext = LocalHostContext.current
    val fonts = remember(hostContext) { ClockFontStore(hostContext) }
    val customFamily by produceState<FontFamily?>(null, fonts, face.fontId, face.weight) {
        value = face.fontId?.let { fonts.typeface(it, face.weight) }?.let { FontFamily(it) }
    }
    // 模块定制（v2.9.68）：自定义字体在所有布局（含 preset）优先生效；fontId 为空时
    // preset 布局回落各自 preset 字体，非 preset 布局回落 Josefin（上游默认链）。
    val family = customFamily ?: style.layout.presetFont?.family(context) ?: LauncherFontFamily
    val scale = fontScale.coerceIn(0.1f, 1f)
    ClockGlyphContent(
        value = time, semanticsText = clockFaceText(time, style), family = family, weight = FontWeight(face.weight),
        fontSize = face.size * scale, digitSpacing = face.letterSpacing * scale, groupSpacing = face.hourMinuteSpacing * scale,
        stacked = style.layout.stacked, showColon = face.showColon, separate = face.separateDigits,
        color = color, shadow = clockFontShadow(face.fontShadow, scale), modifier = modifier, textAlign = textAlign,
    )
}

/** Week's small time uses the date typography, with the same independent spacing model. */
@Composable
internal fun ClockTimeLabel(time: String, style: ClockStyle, textStyle: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val face = style.face
    val size = textStyle.fontSize.value
    val scale = size / face.size
    ClockGlyphContent(
        value = time, semanticsText = clockFaceText(time, style), family = textStyle.fontFamily ?: LauncherFontFamily,
        weight = textStyle.fontWeight ?: FontWeight.Normal, fontSize = size,
        digitSpacing = face.letterSpacing * scale, groupSpacing = face.hourMinuteSpacing * scale,
        stacked = false, showColon = face.showColon, separate = false,
        color = color, shadow = clockFontShadow(face.fontShadow, scale), modifier = modifier,
    )
}

@Composable
private fun clockFontShadow(strength: Int, scale: Float): Shadow {
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
) {
    val typeface = LocalFontFamilyResolver.current.resolve(family, weight, FontStyle.Normal, FontSynthesis.All).value as Typeface
    val density = LocalDensity.current
    val fontPx = with(density) { fontSize.sp.toPx() }
    val digitGapPx = with(density) { digitSpacing.sp.toPx() }
    val groupGapPx = with(density) { groupSpacing.sp.toPx() }
    val centered = textAlign == TextAlign.Center
    BoxWithConstraints(
        modifier.semantics { text = AnnotatedString(semanticsText) },
        contentAlignment = if (centered) Alignment.TopCenter else Alignment.TopStart,
    ) {
        val availableWidth = constraints.maxWidth.toFloat()
        val availableHeight = constraints.maxHeight.toFloat()
        val drawing = remember(value, typeface, fontPx, digitGapPx, groupGapPx, stacked, showColon, centered, availableWidth, availableHeight) {
            measureClockGlyphs(value, typeface, fontPx, digitGapPx, groupGapPx, stacked, showColon, centered, availableWidth, availableHeight)
        }
        Layout(content = {}, modifier = Modifier.drawBehind { drawClockGlyphs(drawing, color, shadow, separate) }) { _, limits ->
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
    style.layout.stacked -> time.replace(':', '\n')
    style.face.showColon -> time
    else -> time.replace(':', ' ')
}

/** Explicit HH keeps the leading zero even in locales whose default hour pattern is H. */
internal fun formatHomeClock(
    now: Instant,
    use24Hour: Boolean,
    locale: Locale,
    zone: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm", locale)
    .withDecimalStyle(DecimalStyle.of(locale)).withZone(zone).format(now)
