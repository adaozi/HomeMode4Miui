package com.galaxyrio.gracelauncher.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R // P2：模块 namespace 为 com.homemode4miui，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.ui.drawer.GraceSection
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
private class ScrubState {
    var active by mutableStateOf(false)
    var fingerY by mutableFloatStateOf(0f)
    var fingerX by mutableFloatStateOf(0f)
    var selectedIndex by mutableIntStateOf(0)
}

/**
 * One alphabet touch surface for the home/app list and icon picker.
 * Keep the pointer coroutine independent of selection callbacks so opening the
 * list or changing a section never interrupts a finger already on the screen.
 */
@Composable
fun AlphabetRail(
    letters: List<String>,
    selectedLetter: String?,
    height: Dp,
    onLetterSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onScrubFinished: () -> Unit = {},
    autoHide: Boolean = false,
    includeHome: Boolean = true,
    onWallpaper: Boolean = true,
) {
    if (letters.isEmpty()) return
    val appearance = LocalLauncherAppearance.current
    val textColor = if (onWallpaper) appearance.text else MaterialTheme.colorScheme.onSurface
    val entries = remember(letters, includeHome) { if (includeHome) listOf<String?>(null) + letters else letters }
    val state = remember { ScrubState() }
    val currentOnSelect by rememberUpdatedState(onLetterSelected)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val density = LocalDensity.current
    var railHeightPx by remember { mutableIntStateOf(1) }
    val cellHeight = height / entries.size
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val waveRadius = with(density) { 82.dp.toPx() }
    val basePull = with(density) { 50.dp.toPx() }
    val railWidth = with(density) { 48.dp.toPx() }
    val indicatorSize = with(density) { 46.dp.toPx() }
    val indicatorGap = with(density) { 14.dp.toPx() }
    // Leave room for the rail's widest glyph (or its 18dp home star),
    // independently of the larger indicator's radius and animation scale.
    val railGlyphHalfWidth = with(density) { maxOf(18.dp.toPx(), 14.sp.toPx()) / 2f }
    val wave by animateFloatAsState(
        targetValue = if (state.active) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 700f),
        label = "alphabetWave",
    )
    val visibility by animateFloatAsState(
        targetValue = if (!autoHide || state.active) 1f else 0f,
        label = "alphabetVisibility",
    )
    val railDescription = stringResource(R.string.alphabet_scroller)
    val homeDescription = stringResource(R.string.back_home)

    Box(
        modifier = modifier
            .width(48.dp)
            .height(height)
            .testTag("alphabet_rail")
            .onSizeChanged { railHeightPx = it.height.coerceAtLeast(1) }
            .semantics { contentDescription = railDescription }
            .pointerInput(entries) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pointerId = down.id
                    var change = down
                    var previousIndex = -1
                    state.active = true
                    try {
                        while (true) {
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            state.fingerY = change.position.y.coerceIn(0f, railHeightPx.toFloat())
                            state.fingerX = change.position.x
                            val index = alphabetIndexAt(
                                y = state.fingerY,
                                height = railHeightPx.toFloat(),
                                count = entries.size,
                            )
                            state.selectedIndex = index
                            if (index != previousIndex) {
                                previousIndex = index
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                currentOnSelect(entries[index])
                            }
                            change.consume()
                            change = awaitPointerEvent().changes.firstOrNull { it.id == pointerId } ?: break
                        }
                    } finally {
                        state.active = false
                        currentOnScrubFinished()
                    }
                }
            },
    ) {
        entries.forEachIndexed { index, letter ->
            val isSelected = if (state.active) state.selectedIndex == index else selectedLetter == letter
            val description = when (letter) {
                null -> homeDescription
                GraceSection -> stringResource(R.string.jump_to_section, stringResource(R.string.app_name))
                else -> stringResource(if (includeHome) R.string.jump_to_letter else R.string.jump_to_section, letter)
            }
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(cellHeight)
                    .offset { IntOffset(0, (index * cellHeightPx).roundToInt()) }
                    .testTag(if (letter == null) "alphabet_home" else "alphabet:$letter")
                    .graphicsLayer {
                        // Fade the glyphs only: keep the same touch surface alive
                        // while hidden and when scrubbing switches between pages.
                        alpha = visibility
                        // Pointer coordinates are read in the draw phase, without relaying
                        // every move through the launcher or the LazyColumn composition.
                        val distance = (index + 0.5f) * cellHeightPx - state.fingerY
                        val gaussian = exp(-(distance * distance) / (2f * waveRadius * waveRadius))
                        val extraPull = ((railWidth - state.fingerX) * 0.35f).coerceIn(0f, basePull)
                        translationX = -(basePull + extraPull) * gaussian * wave
                    }
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                        onClick {
                            currentOnSelect(letter)
                            currentOnScrubFinished()
                            true
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val color = textColor.copy(alpha = if (isSelected) 1f else 0.85f)
                if (letter == null) {
                    LauncherIcon(LauncherSymbol.Star, Modifier.size(18.dp), tint = color)
                } else if (letter == GraceSection) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                } else {
                    Text(
                        text = letter,
                        color = color,
                        style = TextStyle(
                            fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
                            fontSize = 14.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            shadow = if (onWallpaper) appearance.textShadow else null,
                        ),
                    )
                }
            }
        }

        // Niagara's transient thumb preview; section headings and rail letters
        // themselves are always plain text without a surface or pill.
        Box(
            modifier = Modifier
                .size(46.dp)
                .graphicsLayer {
                    val extraPull = ((railWidth - state.fingerX) * 0.35f).coerceIn(0f, basePull)
                    // Use an edge-to-edge gap instead of a radius multiplier.
                    // Only the shared wave pull animates, so the bubble never
                    // crowds the rail while it appears or follows the finger.
                    translationX = railWidth / 2f - indicatorSize - railGlyphHalfWidth - indicatorGap -
                        (basePull + extraPull) * wave
                    translationY = (state.fingerY - indicatorSize / 2f)
                        .coerceIn(0f, (railHeightPx - indicatorSize).coerceAtLeast(0f))
                    alpha = wave
                    scaleX = 0.85f + wave * 0.15f
                    scaleY = scaleX
                }
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize().testTag("alphabet_indicator"),
                contentAlignment = Alignment.Center,
            ) {
                val letter = entries.getOrNull(state.selectedIndex)
                if (letter == null) {
                    LauncherIcon(
                        LauncherSymbol.Star,
                        Modifier.size(28.dp).testTag("alphabet_indicator_star"),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                } else if (letter == GraceSection) {
                    Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onPrimary, CircleShape))
                } else {
                    CenteredIndicatorGlyph(letter, MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
    }
}

/** Center the visible glyph, not the font's asymmetric ascent/descent line box. */
@Composable
private fun CenteredIndicatorGlyph(letter: String, color: Color) {
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(
        fontFamily = MaterialTheme.typography.bodyLarge.fontFamily ?: FontFamily.Default,
        fontWeight = FontWeight.Medium,
    ).value as Typeface
    val fontSize = with(density) { 28.sp.toPx() }
    val inset = with(density) { 6.dp.toPx() }
    val paint = remember(typeface) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.typeface = typeface
        }
    }
    val glyphBounds = remember { Rect() }
    Canvas(Modifier.fillMaxSize()) {
        if (letter.isNotEmpty()) {
            paint.color = color.toArgb()
            paint.textSize = fontSize
            paint.getTextBounds(letter, 0, letter.length, glyphBounds)
            // Keep wide letters inside the circle at larger accessibility sizes.
            val fit = min(
                1f,
                min(
                    (size.width - inset * 2f).coerceAtLeast(1f) / glyphBounds.width().coerceAtLeast(1),
                    (size.height - inset * 2f).coerceAtLeast(1f) / glyphBounds.height().coerceAtLeast(1),
                ),
            )
            if (fit < 1f) {
                paint.textSize = fontSize * fit
                paint.getTextBounds(letter, 0, letter.length, glyphBounds)
            }
            drawContext.canvas.nativeCanvas.drawText(
                letter,
                center.x - glyphBounds.exactCenterX(),
                center.y - glyphBounds.exactCenterY(),
                paint,
            )
        }
    }
}

internal fun alphabetIndexAt(y: Float, height: Float, count: Int): Int {
    if (count <= 1 || height <= 0f) return 0
    return ((y.coerceIn(0f, height) / height) * count).toInt().coerceIn(0, count - 1)
}
