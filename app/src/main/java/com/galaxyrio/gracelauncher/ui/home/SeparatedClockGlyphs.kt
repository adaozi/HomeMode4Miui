/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/home/SeparatedClockGlyphs.kt
 * P1 移植：逐字拷贝，未改动。
 */
package com.galaxyrio.gracelauncher.ui.home

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.text.BreakIterator
import kotlin.math.abs

// Full stroke width relative to font size. Increase this to widen the transparent outline.
// Half of the stroke lies outside the glyph; 0.08f replaces the previous 0.044f.
internal const val CLOCK_CUTOUT_STROKE_EM = 0.08f

internal data class PositionedClockGlyph(
    val text: String,
    val bounds: RectF,
    val x: Float,
    val y: Float = 0f,
    val letterSpacingEm: Float = 0f,
)

internal data class ClockGlyphDrawing(
    val glyphs: List<PositionedClockGlyph>,
    val bounds: RectF,
    val typeface: Typeface,
    val fontSizePx: Float,
    val lastBaseline: Float,
)

private data class ClockGlyphRun(val glyphs: List<PositionedClockGlyph>, val ink: ClockInkBox)

/** Immutable ink-bound layout for native text; no Text-layout callbacks or state writes. */
internal fun measureClockGlyphs(
    text: String,
    typeface: Typeface,
    fontSizePx: Float,
    digitGapPx: Float,
    groupGapPx: Float,
    stacked: Boolean,
    showColon: Boolean,
    centerLines: Boolean,
    maxWidth: Float,
    maxHeight: Float,
): ClockGlyphDrawing {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        fontFeatureSettings = "tnum"
    }
    fun inkBounds(value: String): RectF {
        // Outlines are used only to measure fractional ink bounds, never to render text.
        // getTextBounds rounds to integers and would introduce jumps while resizing.
        val path = Path()
        paint.getTextPath(value, 0, value.length, 0f, 0f, path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        return bounds
    }
    fun run(value: String): ClockGlyphRun {
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(value) }
        val clusters = buildList {
            var start = boundaries.first()
            var end = boundaries.next()
            while (end != BreakIterator.DONE) {
                val cluster = value.substring(start, end)
                add(cluster to inkBounds(cluster))
                start = end
                end = boundaries.next()
            }
        }
        val positions = clockDigitPositions(clusters.map { it.second.width() }, digitGapPx)
        val glyphs = clusters.mapIndexed { index, (cluster, bounds) ->
            PositionedClockGlyph(cluster, bounds, positions[index] - bounds.left)
        }
        return ClockGlyphRun(glyphs, ClockInkBox(
            width = glyphs.maxOfOrNull { it.x + it.bounds.right } ?: 0f,
            top = clusters.minOfOrNull { it.second.top } ?: 0f,
            bottom = clusters.maxOfOrNull { it.second.bottom } ?: 0f,
        ))
    }
    fun layout(size: Float): ClockGlyphDrawing {
        paint.textSize = size
        val separator = text.indexOf(':')
        var lastBaseline = 0f
        val glyphs = if (separator >= 0) {
            val hours = run(text.substring(0, separator))
            val minutes = run(text.substring(separator + 1))
            val colon = if (showColon && !stacked) run(":") else null
            val positions = clockGroupPositions(hours.ink, minutes.ink, groupGapPx, stacked, colon?.ink?.width, centerLines)
            lastBaseline = positions.minuteBaseline
            buildList {
                addAll(hours.glyphs.map { it.copy(x = it.x + positions.hourX) })
                if (colon != null) addAll(colon.glyphs.map { it.copy(x = it.x + requireNotNull(positions.colonX)) })
                addAll(minutes.glyphs.map { it.copy(x = it.x + positions.minuteX, y = positions.minuteBaseline) })
            }
        } else {
            // Keep contextual shaping for scripts with joining letters. Latin abbreviations
            // remain individual glyphs, including Sacramento's transparent separation.
            val needsJoining = text.any { it.isLetter() && Character.UnicodeScript.of(it.code) in joiningScripts }
            if (needsJoining) {
                paint.letterSpacing = digitGapPx / size
                val bounds = inkBounds(text)
                paint.letterSpacing = 0f
                listOf(PositionedClockGlyph(text, bounds, -bounds.left, letterSpacingEm = digitGapPx / size))
            } else run(text).glyphs
        }
        val bounds = RectF(
            glyphs.minOfOrNull { it.x + it.bounds.left } ?: 0f,
            glyphs.minOfOrNull { it.y + it.bounds.top } ?: 0f,
            glyphs.maxOfOrNull { it.x + it.bounds.right } ?: 0f,
            glyphs.maxOfOrNull { it.y + it.bounds.bottom } ?: 0f,
        )
        return ClockGlyphDrawing(glyphs, bounds, typeface, size, lastBaseline)
    }

    fun ClockGlyphDrawing.fits() = bounds.width() <= maxWidth && bounds.height() <= maxHeight
    val requested = fontSizePx.coerceAtLeast(1f)
    val full = layout(requested)
    if (full.fits()) return full
    // Only resize glyphs when space is constrained. Never scale either spacing setting:
    // both edge-to-edge gaps remain fixed even when fitting the clock to a narrow preview.
    var low = 1f
    var high = requested
    var best = layout(low)
    repeat(10) {
        val candidate = layout((low + high) / 2)
        if (candidate.fits()) {
            best = candidate
            low = candidate.fontSizePx
        } else high = candidate.fontSizePx
    }
    return best
}

internal fun DrawScope.drawClockGlyphs(drawing: ClockGlyphDrawing, color: Color, shadow: Shadow, separate: Boolean) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = drawing.typeface
        textSize = drawing.fontSizePx
        fontFeatureSettings = "tnum"
        this.color = color.toArgb()
        if (shadow != Shadow.None) {
            setShadowLayer(shadow.blurRadius, shadow.offset.x, shadow.offset.y, shadow.color.toArgb())
        }
    }
    val cutout = Paint(fill).apply {
        clearShadowLayer()
        // Enlarge the same native glyph instead of switching to a heavier font face:
        // a different weight can change its shape and no longer surround the fill evenly.
        style = Paint.Style.FILL_AND_STROKE
        this.color = android.graphics.Color.BLACK
        strokeWidth = drawing.fontSizePx * CLOCK_CUTOUT_STROKE_EM
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    val overflow = cutout.strokeWidth + shadow.blurRadius + maxOf(abs(shadow.offset.x), abs(shadow.offset.y)) + 2f
    val canvas = drawContext.canvas.nativeCanvas
    // Only separated text needs an isolated layer, so CLEAR cannot erase the wallpaper.
    val layer = if (separate) {
        canvas.saveLayer(-overflow, -overflow, size.width + overflow, size.height + overflow, null)
    } else canvas.save()
    try {
        drawing.glyphs.forEach { glyph ->
            val x = glyph.x - drawing.bounds.left
            val y = glyph.y - drawing.bounds.top
            fill.letterSpacing = glyph.letterSpacingEm
            if (separate) {
                cutout.letterSpacing = glyph.letterSpacingEm
                canvas.drawText(glyph.text, x, y, cutout)
            }
            canvas.drawText(glyph.text, x, y, fill)
        }
    } finally {
        canvas.restoreToCount(layer)
    }
}

private val joiningScripts = setOf(
    Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC,
    Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI,
    Character.UnicodeScript.TAMIL, Character.UnicodeScript.TELUGU,
    Character.UnicodeScript.MALAYALAM, Character.UnicodeScript.KANNADA,
    Character.UnicodeScript.THAI, Character.UnicodeScript.MYANMAR,
)
