@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockFaceStyle
import com.galaxyrio.gracelauncher.data.ClockFontFile
import com.galaxyrio.gracelauncher.data.ClockFontStore
import com.galaxyrio.gracelauncher.data.ClockLayout
import com.galaxyrio.gracelauncher.data.ClockStyle
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.home.ClockFace
import com.galaxyrio.gracelauncher.ui.home.HomeClockHeader
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherTypography
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import java.time.Instant
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

@Composable
internal fun ClockStyleSettings(uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // A scalar saved-state draft survives recreation without changing the desktop.
    var encoded by rememberSaveable { mutableStateOf(uiState.settings.clockStyle.encode()) }
    val draft = remember(encoded) { ClockStyle.decode(encoded) }
    val face = draft.face
    fun changeFace(transform: (ClockFaceStyle) -> ClockFaceStyle) { encoded = draft.withFace(transform(face)).encode() }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var fontRevision by remember { mutableIntStateOf(0) }
    val store = remember(context) { ClockFontStore(context) }
    val fonts by produceState(emptyList<ClockFontFile>(), store, fontRevision) { value = store.list() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val font = store.import(uri)
                // Read the current draft after IO; do not overwrite another style's edits.
                val current = ClockStyle.decode(encoded)
                encoded = current.withFace(current.face.copy(fontId = font.id)).encode()
                fontRevision++
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, R.string.clock_font_import_error, Toast.LENGTH_LONG).show()
            } finally { importing = false }
        }
    }
    if (dialog == "font" && draft.layout.allowsCustomFont) {
        ClockFontPickerDialog(fonts, face.fontId,
            onSelect = { id -> changeFace { it.copy(fontId = id) }; dialog = null },
            onImport = { dialog = null; picker.launch(arrayOf("*/*")) }, onDismiss = { dialog = null })
    }
    val fontLabel = when {
        importing -> stringResource(R.string.clock_font_importing)
        face.fontId == null -> stringResource(R.string.clock_font_default)
        else -> fonts.firstOrNull { it.id == face.fontId }?.name ?: stringResource(R.string.clock_font_unavailable)
    }
    val allowsFont = draft.layout.allowsCustomFont
    val showsColon = !draft.layout.stacked
    val weightIndex = if (allowsFont) 2 else 1
    val separationIndex = weightIndex + 5
    val count = separationIndex + 1 + if (showsColon) 1 else 0
    val defaults = ClockStyle.defaults(draft.layout)
    val heroSpec = MaterialTheme.motionScheme.slowSpatialSpec<Rect>()
    val heroBounds = remember(heroSpec) { BoundsTransform { _, _ -> heroSpec } }
    SettingsScaffold(stringResource(R.string.settings_clock_style), "settings_clock_style_editor", onBack,
        fixedCollapsed = true,
        actions = {
            Button(
                modifier = Modifier.padding(end = 8.dp).testTag("clock_style_apply"), enabled = !saving && !importing,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            if (actions.applyClockStyle(draft)) onBack()
                            else Toast.makeText(context, R.string.settings_storage_save_error, Toast.LENGTH_LONG).show()
                        } finally { saving = false }
                    }
                },
            ) { Text(stringResource(R.string.clock_style_apply)) }
        },
    ) { padding ->
        // Both siblings use the same lookahead targets and spring, so the preview's
        // bottom edge and the controls move together throughout the container transform.
        LookaheadScope {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                ClockStylePreview(draft, uiState, this@LookaheadScope, heroBounds,
                    Modifier.padding(top = 8.dp, bottom = 16.dp))
                LazyColumn(
                    Modifier.weight(1f).animateBounds(this@LookaheadScope, boundsTransform = heroBounds)
                        .testTag("clock_style_controls"),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    userScrollEnabled = !saving,
                ) {
                    clockSetting("layout") {
                        ClockLayoutSelector(draft, count, enabled = !saving && !importing) {
                            encoded = draft.copy(layout = it).encode()
                        }
                    }
                    if (allowsFont) clockSetting("font") {
                        SettingsActionItem(stringResource(R.string.settings_font), fontLabel, 1, count,
                            "clock_font_selector", enabled = !saving && !importing) { dialog = "font" }
                    }
                    clockSetting("weight") {
                        ClockSlider(stringResource(R.string.clock_weight), face.weight, defaults.weight,
                            100..face.maxWeight(draft.layout), step = 100,
                            index = weightIndex, count = count, tag = "clock_weight", enabled = !saving && !importing,
                            onChange = { value -> changeFace { it.copy(weight = value) } })
                    }
                    clockSetting("size") {
                        ClockSlider(stringResource(R.string.clock_size), face.size, defaults.size, 32..144, step = 1,
                            index = weightIndex + 1, count = count, tag = "clock_size", enabled = !saving && !importing,
                            onChange = { value -> changeFace { it.copy(size = value) } })
                    }
                    clockSetting("spacing") {
                        ClockSlider(stringResource(if (draft.layout.week) R.string.clock_letter_spacing else R.string.clock_digit_spacing),
                            face.letterSpacing, defaults.letterSpacing, -16..16, step = 1,
                            index = weightIndex + 2, count = count, tag = "clock_letter_spacing", enabled = !saving && !importing,
                            onChange = { value -> changeFace { it.copy(letterSpacing = value) } })
                    }
                    clockSetting("hour_minute_spacing") {
                        ClockSlider(stringResource(R.string.clock_hour_minute_spacing), face.hourMinuteSpacing, defaults.hourMinuteSpacing,
                            -16..64, step = 1, index = weightIndex + 3, count = count,
                            tag = "clock_hour_minute_spacing", enabled = !saving && !importing,
                            onChange = { value -> changeFace { it.copy(hourMinuteSpacing = value) } })
                    }
                    clockSetting("font_shadow") {
                        ClockSlider(stringResource(R.string.clock_font_shadow), face.fontShadow, defaults.fontShadow,
                            0..24, step = 1, index = weightIndex + 4, count = count,
                            tag = "clock_font_shadow", enabled = !saving && !importing,
                            onChange = { value -> changeFace { it.copy(fontShadow = value) } })
                    }
                    clockSetting("separation") {
                        SettingsToggleItem(stringResource(R.string.clock_separate_digits), stringResource(R.string.clock_separate_digits_summary),
                            face.separateDigits, separationIndex, count, "clock_separate_digits") { value ->
                            if (!saving && !importing) changeFace { it.copy(separateDigits = value) }
                        }
                    }
                    if (showsColon) clockSetting("colon") {
                        SettingsToggleItem(stringResource(R.string.clock_show_colon), stringResource(R.string.clock_show_colon_summary),
                            face.showColon, separationIndex + 1, count, "clock_show_colon") { value ->
                            if (!saving && !importing) changeFace { it.copy(showColon = value) }
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.clockSetting(key: String, content: @Composable () -> Unit) {
    item(key = key) {
        // Retain item identity and animate the rows when a preset adds/removes controls.
        Box(Modifier.animateItem(
            fadeInSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        )) { content() }
    }
}

@Composable
private fun ClockLayoutSelector(style: ClockStyle, count: Int, enabled: Boolean, onSelect: (ClockLayout) -> Unit) {
    // Restore a useful initial viewport, but never reorder or auto-scroll on selection.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (style.layout.ordinal - 1).coerceAtLeast(0))
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(0, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.testTag("clock_layout_selector"),
        content = {
            LazyRow(
                Modifier.fillMaxWidth().selectableGroup().testTag("clock_layout_options"),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
            ) {
                items(ClockLayout.entries, key = { it.name }) { layout ->
                    ClockLayoutOption(style.copy(layout = layout), selected = layout == style.layout,
                        enabled = enabled, onSelect = onSelect)
                }
            }
        },
    )
}

@Composable
private fun ClockLayoutOption(style: ClockStyle, selected: Boolean, enabled: Boolean, onSelect: (ClockLayout) -> Unit) {
    val layout = style.layout
    val label = layout.label()
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val interactions = remember { MutableInteractionSource() }
    val scale = remember { Animatable(1f) }
    val pressSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val releaseSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val container by animateColorAsState(
        if (selected) colors.primaryContainer else colors.primaryContainer.copy(alpha = 0f),
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "clockCardContainer",
    )
    val outline by animateColorAsState(
        colors.outline.copy(alpha = if (selected) 0f else 1f),
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "clockCardOutline",
    )
    val contentColor by animateColorAsState(
        if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        MaterialTheme.motionScheme.fastEffectsSpec(), label = "clockCardContent",
    )
    LaunchedEffect(interactions, pressSpec, releaseSpec) {
        interactions.interactions.filterIsInstance<PressInteraction>().collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> scale.animateTo(0.9f, pressSpec)
                is PressInteraction.Release -> {
                    // A quick tap must still visibly compress before springing back.
                    if (scale.value > 0.94f) scale.animateTo(0.9f, pressSpec)
                    scale.animateTo(1f, releaseSpec)
                }
                is PressInteraction.Cancel -> scale.animateTo(1f, pressSpec)
            }
        }
    }
    Box(
        Modifier.size(72.dp).testTag("clock_layout:${layout.name}")
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(shape)
            .background(container)
            .border(1.dp, outline, shape)
            .selectable(selected, enabled = enabled && LocalSettingsStorageState.current.canEdit,
                interactionSource = interactions, indication = ripple(),
                role = Role.RadioButton, onClick = { onSelect(layout) })
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        ClockFace(
            time = if (layout.week) "Fri" else "09:30",
            style = style,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).clearAndSetSemantics {},
            fontScale = 0.3f,
            textAlign = TextAlign.Center,
            color = contentColor,
        )
    }
}

@Composable
private fun ClockStylePreview(
    style: ClockStyle,
    uiState: LauncherUiState,
    lookaheadScope: LookaheadScope,
    boundsTransform: BoundsTransform,
    modifier: Modifier = Modifier,
) {
    val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons, uiState.settings.iconDesign?.design?.iconSize ?: 100)
    val now by produceState(Instant.now()) {
        while (true) { value = Instant.now(); delay(60_000 - System.currentTimeMillis() % 60_000) }
    }
    val shape = ListItemDefaults.segmentedShapes(index = 0, count = 1).shape
    Box(modifier.fillMaxWidth().testTag("clock_style_preview")
        .animateBounds(
            lookaheadScope = lookaheadScope,
            modifier = Modifier.heightIn(min = if (style.layout.stacked) 232.dp else 168.dp),
            boundsTransform = boundsTransform,
        )
        .drawWithContent {
            // Clear the window, not an isolated offscreen layer. FLAG_SHOW_WALLPAPER
            // supplies the real (including live) wallpaper without reading its bitmap.
            drawOutline(shape.createOutline(size, layoutDirection, this), Color.Transparent, blendMode = BlendMode.Clear)
            drawContent()
        }, contentAlignment = Alignment.CenterStart) {
        CompositionLocalProvider(LocalLauncherAppearance provides appearance) {
          MaterialTheme(typography = LocalLauncherTypography.current) {
            HomeClockHeader(
                now = now, event = null, onDateClick = {}, onClockClick = {},
                showBattery = uiState.settings.showBatteryPercentage, clockStyle = style,
                weather = uiState.weather.snapshot?.current.takeIf {
                    uiState.settings.weatherEnabled && !uiState.isLoadingSettings && !uiState.settingsLoadFailed
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                interactive = false, clockTag = "clock_style_preview_text", dateTag = "clock_style_preview_date",
            )
          }
        }
    }
}

@Composable
private fun ClockSlider(
    title: String, value: Int, default: Int, range: IntRange, step: Int,
    index: Int, count: Int, tag: String, enabled: Boolean, onChange: (Int) -> Unit,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.testTag("${tag}_item"),
        content = {
            Column {
                Text(title)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Slider(value = value.toFloat(), onValueChange = {
                        onChange((range.first + ((it - range.first) / step).roundToInt() * step).coerceIn(range))
                    }, valueRange = range.first.toFloat()..range.last.toFloat(),
                        steps = if (step > 1) (range.last - range.first) / step - 1 else 0,
                        enabled = enabled, modifier = Modifier.weight(1f).testTag(tag).semantics { contentDescription = title })
                    IconButton(onClick = { onChange(default) }, enabled = enabled && value != default,
                        modifier = Modifier.size(48.dp).clip(CircleShape).testTag("${tag}_reset")) {
                        Icon(painterResource(R.drawable.ms_restart_alt), stringResource(R.string.clock_reset_value, title))
                    }
                }
            }
        },
    )
}

@Composable
internal fun ClockLayout.label(): String = stringResource(when (this) {
    ClockLayout.SingleLine -> R.string.clock_single_line
    ClockLayout.TwoLines -> R.string.clock_two_lines
    ClockLayout.Week -> R.string.clock_week
    ClockLayout.Sacramento -> R.string.clock_sacramento
    ClockLayout.Bokor -> R.string.clock_bokor
    ClockLayout.Plaster -> R.string.clock_plaster
    ClockLayout.DoublePlaster -> R.string.clock_double_plaster
    ClockLayout.Monoton -> R.string.clock_monoton
    ClockLayout.Lucky -> R.string.clock_lucky
    ClockLayout.SacramentoWeek -> R.string.clock_sacramento_week
})
