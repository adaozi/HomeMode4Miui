@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.AlphabetAppearance
import com.galaxyrio.gracelauncher.data.AlphabetRailLayout
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.PrivateSpaceDisplay
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AlphabetRail
import com.galaxyrio.gracelauncher.ui.drawer.AppListModel
import com.galaxyrio.gracelauncher.ui.drawer.GraceSection
import com.galaxyrio.gracelauncher.ui.theme.LocalFontLibrary
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.wallpaperTint
import kotlin.math.roundToInt

@Composable
internal fun AlphabetSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit, onManageFonts: () -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val saved = uiState.settings.listAppearance.alphabet
    val defaults = remember { AlphabetAppearance() }
    var draft by remember(saved) { mutableStateOf(saved.normalized()) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    val controls = rememberLazyListState()
    val enabled = LocalSettingsStorageState.current.canEdit
    val letters = rememberAlphabetPreviewLetters(uiState)
    val density = LocalDensity.current
    var viewportTop by remember { mutableFloatStateOf(0f) }
    // Measure before the scaffold takes out its app bar and navigation padding,
    // just like the desktop's full-height BoxWithConstraints.
    val viewportHeight = maxHeight.value.coerceAtLeast(1f)
    val railLayout = draft.layout(viewportHeight, letters.size + 1)
    val automaticTop = draft.copy(topPercent = null).layout(viewportHeight, letters.size + 1).topDp / viewportHeight * 100f
    val wallpaper = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, 100)
    val primary = MaterialTheme.colorScheme.primary
    val heroSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val heroBounds = remember(heroSpec) { BoundsTransform { _, _ -> heroSpec } }
    val heroShape = ListItemDefaults.segmentedShapes(0, 1).shape
    fun change(transform: (AlphabetAppearance) -> AlphabetAppearance) {
        if (!enabled) return
        draft = transform(draft).normalized()
        actions.updateSettings { current -> current.copy(listAppearance = current.listAppearance.copy(
            alphabet = transform(current.listAppearance.alphabet).normalized())) }
    }
    fun closeColor() {
        val color = draft.fontColor
        change { it.copy(fontColor = color) }
        dialog = null
    }
    BackHandler(dialog == "color") { closeColor() }
    if (dialog == "font") FontPickerDialog(uiState.settings.fontLibrary, draft.fontId,
        onSelect = { id -> change { it.copy(fontId = id) }; dialog = null },
        onManage = { dialog = null; onManageFonts() }, onDismiss = { dialog = null }, tag = "alphabet_font_picker")

    SettingsScaffold(stringResource(R.string.settings_alphabet), "alphabet_settings",
        { if (dialog == "color") closeColor() else onBack() },
        modifier = Modifier.onGloballyPositioned { viewportTop = with(density) { it.positionInWindow().y.toDp().value } },
        fixedCollapsed = true) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            // Leave a useful controls column on small phones, and enough preview width
            // for both the wave and its indicator. The right edge has no outer margin.
            val previewWidth = (maxWidth * .42f).coerceIn(160.dp, 180.dp)
            AlphabetPreview(uiState, draft, letters, railLayout, viewportTop, Modifier.align(AbsoluteAlignment.TopRight).width(previewWidth).fillMaxHeight()
                .padding(top = 8.dp, bottom = 8.dp + LocalSettingsBottomInset.current))
            SharedTransitionLayout(Modifier.fillMaxSize().absolutePadding(left = 12.dp, right = previewWidth + 8.dp, top = 8.dp).clipToBounds()) {
                AnimatedContent(dialog == "color", Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "alphabetColorHero") { colorOpen ->
                    val colorModifier = Modifier.sharedBounds(rememberSharedContentState("alphabet_color"), this,
                        boundsTransform = heroBounds, clipInOverlayDuringTransition = OverlayClip(heroShape))
                    if (colorOpen) {
                        IconDesignerColorPicker(stringResource(R.string.alphabet_letter_color), draft.fontColor,
                            primary.toArgb(), null,
                            onChange = { if (enabled && dialog == "color") draft = draft.copy(fontColor = it) },
                            onClose = { if (dialog == "color") closeColor() },
                            modifier = Modifier.padding(bottom = 8.dp).then(colorModifier), fallback = wallpaper.text.toArgb())
                    } else LazyColumn(Modifier.fillMaxSize().testTag("alphabet_controls"), state = controls,
                        contentPadding = PaddingValues(bottom = 24.dp + LocalSettingsBottomInset.current),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        item("font") {
                            SettingsActionItem(stringResource(R.string.settings_font), appFontLabel(draft.fontId),
                                0, 7, "alphabet_font", enabled = enabled, onClick = { dialog = "font" })
                        }
                        item("color") {
                            SettingsActionItem(stringResource(R.string.alphabet_letter_color), null,
                                1, 7, "alphabet_color", enabled = enabled, modifier = colorModifier, trailing = {
                                    Surface(Modifier.size(24.dp), shape = MaterialTheme.shapes.small,
                                        color = draft.fontColor?.resolve(primary.toArgb())?.let(::Color) ?: wallpaper.text) {}
                                }, onClick = { dialog = "color" })
                        }
                        item("font_size") {
                            DesignerSegment(2, 7, "alphabet_font_size_control") {
                                DesignerSlider(stringResource(R.string.clock_size), draft.fontSize.toFloat(), defaults.fontSize.toFloat(),
                                    10f..28f, enabled, "alphabet_font_size", suffix = "sp",
                                    onFinished = { value -> change { it.copy(fontSize = value.roundToInt()) } }) {
                                    draft = draft.copy(fontSize = it.roundToInt())
                                }
                            }
                        }
                        item("shape") {
                            DesignerSegment(3, 7, "alphabet_shape") {
                                Text(stringResource(R.string.alphabet_indicator_shape))
                                val design = IconDesign(shape = draft.indicatorShape, cookieSides = draft.cookieSides,
                                    pebbleRoundness = draft.pebbleRoundness, squareCornerRadius = draft.squareCornerRadius)
                                val update: (IconDesign) -> Unit = { value -> change { it.copy(indicatorShape = value.shape,
                                    cookieSides = value.cookieSides, pebbleRoundness = value.pebbleRoundness, squareCornerRadius = value.squareCornerRadius) } }
                                DesignerShapeChoices(design, enabled, "alphabet", update)
                                DesignerShapeAdjustment(design, IconDesign(), enabled, "alphabet", onFinished = update, includeAspectRatio = false) { value ->
                                    draft = draft.copy(indicatorShape = value.shape, cookieSides = value.cookieSides,
                                        pebbleRoundness = value.pebbleRoundness, squareCornerRadius = value.squareCornerRadius)
                                }
                            }
                        }
                        item("top") {
                            DesignerSegment(4, 7, "alphabet_top_control") {
                                if (draft.topPercent == null) Text(
                                    stringResource(R.string.alphabet_automatic_spacing, AlphabetAppearance.DefaultStepDp.roundToInt()),
                                    Modifier.testTag("alphabet_top_automatic"), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                DesignerSlider(stringResource(R.string.alphabet_top), draft.topPercent?.toFloat() ?: automaticTop, automaticTop,
                                    0f..99f, enabled, "alphabet_top", onFinished = { value ->
                                        val bottom = draft.bottomPercent
                                        change { it.copy(topPercent = value.roundToInt(), bottomPercent = bottom) }
                                    }, resetEnabled = draft.topPercent != null, onReset = { change { it.copy(topPercent = null) } }) {
                                    draft = draft.copy(topPercent = it.roundToInt()).normalized()
                                }
                            }
                        }
                        item("bottom") {
                            DesignerSegment(5, 7, "alphabet_bottom_control") {
                                DesignerSlider(stringResource(R.string.alphabet_bottom), draft.bottomPercent.toFloat(),
                                    maxOf(defaults.bottomPercent, (draft.topPercent ?: 0) + 1).toFloat(),
                                    ((draft.topPercent ?: 0) + 1f)..100f, enabled, "alphabet_bottom",
                                    onFinished = { value -> change { it.copy(bottomPercent = value.roundToInt()) } }) {
                                    draft = draft.copy(bottomPercent = it.roundToInt()).normalized()
                                }
                            }
                        }
                        item("free") {
                            SettingsToggleItem(stringResource(R.string.alphabet_free_movement), stringResource(R.string.alphabet_free_movement_summary),
                                draft.freeMovement, 6, 7, "alphabet_free_movement", enabled = enabled) { value -> change { it.copy(freeMovement = value) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlphabetPreview(uiState: LauncherUiState, draft: AlphabetAppearance, letters: List<String>, layout: AlphabetRailLayout,
    viewportTop: Float, modifier: Modifier) {
    val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, 100)
    val settings = uiState.settings
    val primary = MaterialTheme.colorScheme.primary
    val tint = remember(primary, appearance.darkText, settings.listAppearance.wallpaperDimColor) {
        appearance.wallpaperTint(primary, settings.listAppearance.wallpaperDimColor)
    }
    val dimAlpha = if (settings.dimWallpaper) settings.wallpaperDimAmount / 100f else 0f
    val shape = remember { AbsoluteRoundedCornerShape(topLeft = 24.dp, bottomLeft = 24.dp) }
    val density = LocalDensity.current
    var previewTop by remember { mutableFloatStateOf(0f) }
    var positioned by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    Box(modifier.testTag("alphabet_preview").onGloballyPositioned {
        previewTop = with(density) { it.positionInWindow().y.toDp().value }
        positioned = true
    }.drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        drawOutline(outline, Color.Transparent, blendMode = BlendMode.Clear)
        drawOutline(outline, tint.copy(alpha = dimAlpha))
        drawContent()
    }.clip(shape)) {
        CompositionLocalProvider(LocalLauncherAppearance provides appearance, LocalFontLibrary provides settings.fontLibrary) {
            // A window into the desktop, not another screen with a shorter coordinate
            // system. Preserve actual letter spacing, glyph sizes and indicator geometry.
            if (positioned) AlphabetRail(letters, selected, layout.heightDp.dp,
                onLetterSelected = { selected = it }, alphabet = draft,
                modifier = Modifier.align(AbsoluteAlignment.TopRight).offset(y = (viewportTop + layout.topDp - previewTop).dp)
                    .wrapContentHeight(Alignment.Top, unbounded = true))
        }
    }
}

@Composable
private fun rememberAlphabetPreviewLetters(uiState: LauncherUiState): List<String> {
    val settings = uiState.settings
    // 上游同步裁剪：模块 AppListModel 为 P2 裁剪版（无私密空间/双开 folder 载体，
    // 随 S8 Work Profile 批次补齐）—— 预览只含 A-Z 实际字母 + Grace 区。
    val apps = uiState.appListApps
    return remember(apps, uiState.folders) {
        val actual = AppListModel(apps, uiState.folders).letters
        // The real drawer always has Recently Installed in the Grace section.
        (actual + GraceSection).distinct()
    }
}
