package com.galaxyrio.gracelauncher.ui.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.AlphabetAppearance
import com.galaxyrio.gracelauncher.data.IconShape
import com.galaxyrio.gracelauncher.data.icons.iconShapePath
import com.galaxyrio.gracelauncher.ui.drawer.GraceSection
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
private class ScrubState {
    var active by mutableStateOf(false)
    var fingerY by mutableFloatStateOf(0f)
    var fingerX by mutableFloatStateOf(0f)
    var selectedIndex by mutableIntStateOf(0)
    var drift by mutableFloatStateOf(0f)
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
    leftTouchEnabled: Boolean = false,
    alphabet: AlphabetAppearance? = null,
    topOffset: Dp = 0.dp,
    touchHeight: Dp = height,
    enabled: Boolean = true,
    onDoubleTap: ((Offset) -> Unit)? = null,
    doubleTapIntervalMs: Int? = null,
) {
    if (letters.isEmpty()) return
    val appearance = LocalLauncherAppearance.current
    val defaultTextColor = if (onWallpaper) appearance.text else MaterialTheme.colorScheme.onSurface
    val textColor = alphabet?.fontColor?.resolve(MaterialTheme.colorScheme.primary.toArgb())
        ?.let(::Color) ?: defaultTextColor
    val fontSize = (alphabet?.fontSize ?: 14).sp
    val fontFamily = if (alphabet != null) rememberAppFontFamily(alphabet.fontId) else MaterialTheme.typography.bodyLarge.fontFamily
    val freeMovement by rememberUpdatedState(alphabet?.freeMovement == true)
    val indicatorShape = remember(alphabet?.indicatorShape, alphabet?.cookieSides, alphabet?.pebbleRoundness, alphabet?.squareCornerRadius) {
        if (alphabet == null) CircleShape else GenericShape { size, _ ->
            addPath(iconShapePath(alphabet.indicatorShape, alphabet.cookieSides, size.minDimension,
                alphabet.pebbleRoundness, alphabet.squareCornerRadius).asComposePath())
        }
    }
    val entries = remember(letters, includeHome) { if (includeHome) listOf<String?>(null) + letters else letters }
    val state = remember { ScrubState() }
    val currentOnSelect by rememberUpdatedState(onLetterSelected)
    val inputEnabled by rememberUpdatedState(enabled)
    val currentOnScrubFinished by rememberUpdatedState(onScrubFinished)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    var leftTouchOrigin by remember { mutableStateOf(Offset.Zero) }
    var rightTouchOrigin by remember { mutableStateOf(Offset.Zero) }
    val haptics by rememberUpdatedState(LocalHapticFeedback.current)
    val density = LocalDensity.current
    val doubleTapSlop = with(density) { 100.dp.toPx() }
    // The desktop's edge touch strips span the viewport, independently of the
    // alphabet's configured position/spacing. Pickers keep the compact defaults.
    val railHeightPx by rememberUpdatedState(with(density) { height.roundToPx().coerceAtLeast(1) })
    val railTopPx by rememberUpdatedState(with(density) { topOffset.toPx() })
    val cellHeight = height / entries.size
    val cellHeightPx = with(density) { cellHeight.toPx() }
    val waveRadius = with(density) { 82.dp.toPx() }
    val basePull = with(density) { 50.dp.toPx() }
    val railWidth = with(density) { 48.dp.toPx() }
    val indicatorSize = with(density) { 46.dp.toPx() }
    val indicatorGap = with(density) { 14.dp.toPx() }
    // Leave room for the rail's widest glyph (or its 18dp home star),
    // independently of the larger indicator's radius and animation scale.
    val railGlyphHalfWidth = with(density) { maxOf(18.dp.toPx(), fontSize.toPx()) / 2f }
    // Only visuals move. The pointer surface stays put, avoiding coordinate feedback
    // when the rail floats beyond its configured range.
    val drift by animateFloatAsState(
        targetValue = if (state.active) state.drift else 0f,
        animationSpec = if (state.active) snap() else spring(dampingRatio = .8f, stiffness = 500f),
        label = "alphabetDrift",
    )
    val currentDrift by rememberUpdatedState(drift)
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

    // Ignoring events inside a disabled pointer handler still blocks siblings below
    // this overlay. Detach it so widget resize handles can receive the initial down.
    fun touchSurface(fromLeft: Boolean) = if (!enabled) Modifier else Modifier.pointerInput(
        entries, fromLeft, onDoubleTap != null, doubleTapIntervalMs, doubleTapSlop,
    ) {
        val doubleTapTimeout = doubleTapIntervalMs?.coerceIn(150, 700)?.toLong()
            ?: viewConfiguration.doubleTapTimeoutMillis
        var firstTapUpTime: Long? = null
        var firstTapPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown()
            if (!inputEnabled) return@awaitEachGesture
            val previousUpTime = firstTapUpTime
            firstTapUpTime = null
            val secondTap = previousUpTime != null &&
                down.uptimeMillis - previousUpTime in viewConfiguration.doubleTapMinTimeMillis..doubleTapTimeout &&
                (down.position - firstTapPosition).getDistance() <= doubleTapSlop
            var isTap = currentOnDoubleTap != null
            var doubleTapPosition: Offset? = null
            val pointerId = down.id
            var change = down
            var previousIndex = -1
            state.drift = currentDrift
            state.active = true
            try {
                while (true) {
                    if (!inputEnabled) break
                    // Recognize taps in the existing scrub stream. Do not delay selection
                    // or add a competing detector that could consume a drag's first down.
                    isTap = isTap && !change.isConsumed && currentEvent.changes.size == 1 &&
                        (change.position - down.position).getDistance() <= viewConfiguration.touchSlop &&
                        change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis &&
                        change.position.x in 0f..size.width.toFloat() && change.position.y in 0f..size.height.toFloat()
                    if (!change.pressed) {
                        if (isTap && change.changedToUpIgnoreConsumed()) {
                            if (secondTap) doubleTapPosition = change.position else {
                                firstTapUpTime = change.uptimeMillis
                                firstTapPosition = down.position
                            }
                        }
                        change.consume()
                        break
                    }
                    val railY = change.position.y - railTopPx
                    state.drift = alphabetDriftAt(railY, railHeightPx.toFloat(), state.drift, freeMovement)
                    state.fingerY = (railY - state.drift).coerceIn(0f, railHeightPx.toFloat())
                    state.fingerX = if (fromLeft) railWidth - change.position.x else change.position.x
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
            // Finish the scrub before an action changes pages or starts its transition.
            doubleTapPosition?.let { position ->
                val origin = if (fromLeft) leftTouchOrigin else rightTouchOrigin
                currentOnDoubleTap?.invoke(origin + position)
            }
        }
    }

    // Two touch regions, one visual rail and one scrub state. No second alphabet is drawn.
    Box(modifier.then(if (leftTouchEnabled) Modifier.fillMaxWidth() else Modifier.width(48.dp)).height(touchHeight)) {
        if (leftTouchEnabled) Box(Modifier.align(Alignment.TopStart).width(28.dp).height(touchHeight)
            .onGloballyPositioned { leftTouchOrigin = it.positionInRoot() }
            .testTag("alphabet_left_touch").then(touchSurface(true)).clearAndSetSemantics {})
        Box(
            Modifier.align(Alignment.TopEnd).width(48.dp).height(touchHeight)
                .onGloballyPositioned { rightTouchOrigin = it.positionInRoot() }
                .testTag("alphabet_rail")
                .semantics { contentDescription = railDescription }
                .then(touchSurface(false)),
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
                        .offset { IntOffset(0, (railTopPx + index * cellHeightPx).roundToInt()) }
                        .testTag(if (letter == null) "alphabet_home" else "alphabet:$letter")
                        .graphicsLayer {
                            // Fade the glyphs only: keep the same touch surface alive
                            // while hidden and when scrubbing switches between pages.
                            alpha = visibility
                            translationY = drift
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
                                if (inputEnabled) {
                                    currentOnSelect(letter)
                                    currentOnScrubFinished()
                                }
                                inputEnabled
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
                            modifier = Modifier.wrapContentSize(unbounded = true),
                            maxLines = 1,
                            color = color,
                            style = TextStyle(
                                fontFamily = fontFamily,
                                fontSize = fontSize,
                                lineHeight = fontSize * (16f / 14f),
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
                        translationY = railTopPx + drift + (state.fingerY - indicatorSize / 2f)
                            .coerceIn(0f, (railHeightPx - indicatorSize).coerceAtLeast(0f))
                        alpha = wave
                        scaleX = 0.85f + wave * 0.15f
                        scaleY = scaleX
                    }
                    .background(if (alphabet?.indicatorShape == IconShape.None) Color.Transparent else MaterialTheme.colorScheme.primary, indicatorShape)
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
                        CenteredIndicatorGlyph(letter, MaterialTheme.colorScheme.onPrimary, fontFamily)
                    }
                }
            }
        }
    }
}

/** Center the visible glyph, not the font's asymmetric ascent/descent line box. */
@Composable
private fun CenteredIndicatorGlyph(letter: String, color: Color, fontFamily: FontFamily?) {
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(
        fontFamily = fontFamily ?: FontFamily.Default,
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

/** Floating joystick: move only enough to keep the finger at an end, retaining
 * that new origin on reversal so there is no dead travel to undo. */
internal fun alphabetDriftAt(y: Float, height: Float, previous: Float, enabled: Boolean): Float =
    if (!enabled || height <= 0f) 0f else previous.coerceIn(y - height, y)
