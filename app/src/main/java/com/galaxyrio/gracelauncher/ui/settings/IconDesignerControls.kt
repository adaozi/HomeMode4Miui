@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.IconColor
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.IconShape
import com.galaxyrio.gracelauncher.data.icons.iconShapePath
import com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon
import kotlin.math.roundToInt

internal fun LazyListScope.iconDesignerControls(
    all: Boolean, enabled: Boolean, appLabel: String?, sourceLabel: String, design: IconDesign,
    layered: Boolean, dynamicColors: Pair<Int, Int>, themeColors: Pair<Int, Int>, defaults: IconDesign,
    onSwitch: () -> Unit, onSource: () -> Unit,
    onColor: (String) -> Unit, onChange: (IconDesign) -> Unit,
    showSuggestions: Boolean = false, suggestion: String? = null, onSuggestion: (String) -> Unit = {},
    colorModifier: @Composable (String) -> Modifier = { Modifier },
) {
    val cookie = design.shape == IconShape.Cookie
    val adjustableCorners = design.shape == IconShape.Pebble || design.shape == IconShape.Square
    val canAddTray = all || !layered
    val count = (if (all) 8 else 9) + (if (cookie || adjustableCorners) 1 else 0) +
        (if (canAddTray) 1 else 0) + (if (showSuggestions && !all) 1 else 0)
    var index = 0
    if (!all) {
        val position = index++
        item("switch") { SettingsActionItem(stringResource(R.string.icon_designer_switch_app), appLabel,
            position, count, "icon_designer_switch", onClick = onSwitch) }
        val sourceIndex = index++
        item("source") { SettingsActionItem(stringResource(R.string.icon_designer_source), sourceLabel,
            sourceIndex, count, "icon_designer_source", enabled = enabled, onClick = onSource) }
        if (showSuggestions) {
            val suggestionsIndex = index++
            item("suggestions") {
                SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(suggestionsIndex, count),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    content = { Text(stringResource(R.string.icon_designer_suggestions)) },
                    supportingContent = {
                        LazyRow(Modifier.fillMaxWidth().selectableGroup().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(GraceButtonIcon.Suggestion.entries, key = { it.id }) { icon ->
                                val selected = suggestion == icon.id
                                Box(Modifier.size(48.dp).clip(CircleShape)
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSuggestion(icon.id) })
                                    .testTag("icon_designer_suggestion:${icon.id}"), contentAlignment = Alignment.Center) {
                                    Icon(painterResource(icon.drawable), stringResource(icon.label),
                                        Modifier.size(if (icon == GraceButtonIcon.Suggestion.Default) 48.dp else 24.dp),
                                        tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    })
            }
        }
    } else {
        val sizeIndex = index++
        item("icon_size") {
            DesignerSegment(sizeIndex, count, "icon_designer_display_size") {
                Text(stringResource(R.string.icon_designer_icon_size_title))
                DesignerSlider(stringResource(R.string.icon_designer_icon_size), design.iconSize.toFloat(), 100f, 80f..150f,
                    enabled, "icon_designer_icon_size") { onChange(design.copy(iconSize = it.roundToInt())) }
            }
        }
    }
    val shapeIndex = index++
    item("shape") {
        DesignerSegment(shapeIndex, count, "icon_designer_shape") {
            Text(stringResource(R.string.icon_designer_shape))
            DesignerShapeChoices(design, enabled, "icon_designer", onChange)
        }
    }
    if (adjustableCorners) {
        val roundnessIndex = index++
        val pebble = design.shape == IconShape.Pebble
        val tag = if (pebble) "icon_designer_pebble_roundness" else "icon_designer_square_corner_radius"
        item("shape_roundness") {
            DesignerSegment(roundnessIndex, count, "${tag}_control") {
                DesignerShapeAdjustment(design, defaults, enabled, "icon_designer", onChange)
            }
        }
    }
    if (cookie) {
        val cookieIndex = index++
        item("cookie") {
            DesignerSegment(cookieIndex, count, "icon_designer_cookie") {
                DesignerShapeAdjustment(design, defaults, enabled, "icon_designer", onChange)
            }
        }
    }
    if (canAddTray) {
        val trayIndex = index++
        item("add_tray") {
            SettingsToggleItem(stringResource(R.string.icon_designer_add_tray), stringResource(R.string.icon_designer_add_tray_summary),
                design.addTray, trayIndex, count, "icon_designer_add_tray", enabled = enabled) { value ->
                if (enabled) onChange(design.copy(addTray = value))
            }
        }
    }
    val themeIndex = index++
    item("theme_icon") {
        SettingsToggleItem(stringResource(R.string.icon_designer_theme_icon), stringResource(R.string.settings_themed_icons_summary),
            design.themeIcons, themeIndex, count, "icon_designer_theme_icon", enabled = enabled) {
            onChange(design.copy(themeIcons = it))
        }
    }
    val unsupportedIndex = index++
    item("theme_unsupported") {
        SettingsToggleItem(stringResource(R.string.icon_designer_theme_unsupported), stringResource(R.string.icon_designer_theme_unsupported_summary),
            design.themeUnsupportedIcons, unsupportedIndex, count, "icon_designer_theme_unsupported", enabled = enabled && design.themeIcons) {
            onChange(design.copy(themeUnsupportedIcons = it))
        }
    }
    val colorIndex = index++
    item("colors") {
        DesignerSegment(colorIndex, count, "icon_designer_colors") {
            Text(stringResource(R.string.icon_designer_color))
            if (all || layered || design.addTray) DesignerColorRow(stringResource(R.string.icon_designer_tray_color), design.background,
                defaults.background, dynamicColors.first, themeColors.first, enabled, "icon_designer_background",
                colorModifier("background"), { onColor("background") }) { onChange(design.copy(background = defaults.background)) }
            DesignerColorRow(stringResource(R.string.icon_designer_symbol_color), design.foreground,
                defaults.foreground, dynamicColors.second, themeColors.second, enabled, "icon_designer_foreground",
                colorModifier("foreground"), { onColor("foreground") }) { onChange(design.copy(foreground = defaults.foreground)) }
        }
    }
    val invertIndex = index++
    item("invert_background") {
        SettingsToggleItem(stringResource(R.string.icon_designer_invert_background),
            stringResource(R.string.icon_designer_invert_background_summary),
            design.invertBackgroundDetection, invertIndex, count, "icon_designer_invert_background",
            enabled = enabled && (all || !layered) && design.themeIcons && design.themeUnsupportedIcons) {
            onChange(design.copy(invertBackgroundDetection = it))
        }
    }
    val positionIndex = index++
    item("position") {
        DesignerSegment(positionIndex, count, "icon_designer_position") {
            Text(stringResource(R.string.icon_designer_size_position))
            DesignerSlider(stringResource(R.string.icon_designer_x), design.x, defaults.x, -50f..50f,
                enabled, "icon_designer_x") { onChange(design.copy(x = it)) }
            DesignerSlider(stringResource(R.string.icon_designer_y), design.y, defaults.y, -50f..50f,
                enabled, "icon_designer_y") { onChange(design.copy(y = it)) }
            DesignerSlider(stringResource(R.string.icon_designer_size), design.size.toFloat(), defaults.size.toFloat(), 25f..200f,
                enabled, "icon_designer_size") { onChange(design.copy(size = it.roundToInt())) }
            DesignerSlider(stringResource(R.string.icon_designer_rotation), design.rotation, defaults.rotation, -180f..180f,
                enabled, "icon_designer_rotation", suffix = "°") { onChange(design.copy(rotation = it)) }
        }
    }
    item("effects") {
        SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            modifier = Modifier.testTag("icon_designer_effects"),
            content = { Text(stringResource(R.string.icon_designer_effects)) },
            supportingContent = { Text(stringResource(R.string.settings_coming_soon)) })
    }
}

@Composable
internal fun DesignerShapeChoices(design: IconDesign, enabled: Boolean, tag: String, onChange: (IconDesign) -> Unit) {
    LazyRow(Modifier.fillMaxWidth().selectableGroup().testTag("${tag}_shapes").padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(IconShape.entries, key = { it.name }) { shape ->
            val label = shape.label()
            val selected = shape == design.shape
            val color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.width(if (shape == IconShape.Cookie) 104.dp else 76.dp).clip(MaterialTheme.shapes.large)
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onChange(design.copy(shape = shape)) })
                .testTag("${tag}_shape:${shape.name}").semantics { contentDescription = label }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = CircleShape, color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                    Canvas(Modifier.size(48.dp).padding(6.dp)) {
                        val path = iconShapePath(shape, design.cookieSides, size.minDimension,
                            design.pebbleRoundness, design.squareCornerRadius).asComposePath()
                        if (shape == IconShape.None) drawPath(path, color,
                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        else drawPath(path, color)
                    }
                }
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun DesignerShapeAdjustment(design: IconDesign, defaults: IconDesign, enabled: Boolean, tag: String, onChange: (IconDesign) -> Unit) {
    val pebble = design.shape == IconShape.Pebble
    if (pebble || design.shape == IconShape.Square) {
        DesignerSlider(stringResource(if (pebble) R.string.icon_designer_pebble_roundness else R.string.icon_designer_square_corner_radius),
            (if (pebble) design.pebbleRoundness else design.squareCornerRadius).toFloat(),
            (if (pebble) defaults.pebbleRoundness else defaults.squareCornerRadius).toFloat(),
            0f..100f, enabled, if (pebble) "${tag}_pebble_roundness" else "${tag}_square_corner_radius") { value ->
            onChange(if (pebble) design.copy(pebbleRoundness = value.roundToInt())
                else design.copy(squareCornerRadius = value.roundToInt()))
        }
    } else if (design.shape == IconShape.Cookie) {
        Text(stringResource(R.string.icon_designer_cookie_sides, design.cookieSides))
        Slider(value = IconDesign.CookieSides.indexOf(design.cookieSides).coerceAtLeast(0).toFloat(),
            onValueChange = { onChange(design.copy(cookieSides = IconDesign.CookieSides[it.roundToInt().coerceIn(0, 4)])) },
            valueRange = 0f..4f, steps = 3, enabled = enabled, modifier = Modifier.testTag("${tag}_cookie_slider"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            IconDesign.CookieSides.forEach { Text(it.toString(), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
internal fun DesignerSegment(index: Int, count: Int, tag: String, content: @Composable ColumnScope.() -> Unit) {
    SegmentedListItem(shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.testTag(tag), content = { Column(Modifier.padding(vertical = 8.dp), content = content) })
}

@Composable
internal fun DesignerSlider(label: String, value: Float, default: Float, range: ClosedFloatingPointRange<Float>,
    enabled: Boolean, tag: String, suffix: String = "%", onFinished: ((Float) -> Unit)? = null,
    resetEnabled: Boolean = value != default, onReset: (() -> Unit)? = null, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$label · ${value.roundToInt()}$suffix", style = MaterialTheme.typography.labelLarge)
            Slider(value, { onChange(it.roundToInt().toFloat()) }, valueRange = range, enabled = enabled,
                onValueChangeFinished = { onFinished?.invoke(value) },
                modifier = Modifier.testTag(tag).semantics { contentDescription = label })
        }
        ResetIconButton(label, enabled && resetEnabled, "${tag}_reset") {
            if (onReset != null) onReset() else { onChange(default); onFinished?.invoke(default) }
        }
    }
}

@Composable
private fun DesignerColorRow(label: String, choice: IconColor?, default: IconColor?, dynamic: Int, theme: Int, enabled: Boolean, tag: String,
    modifier: Modifier, onPick: () -> Unit, onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).then(modifier).heightIn(min = 64.dp).clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, role = Role.Button, onClick = onPick).testTag(tag).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val swatch = choice?.let { Color(it.resolve(dynamic, theme)) } ?: MaterialTheme.colorScheme.surfaceContainerHighest
            val outline = MaterialTheme.colorScheme.outline
            Canvas(Modifier.size(28.dp)) {
                val radius = size.minDimension / 2 - 1.dp.toPx()
                drawCircle(swatch, radius)
                drawCircle(outline, radius, style = Stroke(1.dp.toPx()))
                if (choice == null) drawLine(outline, Offset(size.width * .20f, size.height * .80f),
                    Offset(size.width * .80f, size.height * .20f), strokeWidth = 1.5.dp.toPx())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label)
                Text(stringResource(if (choice == null) R.string.icon_designer_original_color else if (choice.theme)
                    R.string.icon_designer_theme_color else if (choice.dynamic)
                    R.string.settings_dynamic_colors else R.string.icon_designer_custom_color), style = MaterialTheme.typography.labelSmall)
            }
        }
        ResetIconButton(label, enabled && choice != default, "${tag}_reset", onReset)
    }
}

@Composable
private fun ResetIconButton(label: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(tag)) {
        Icon(painterResource(R.drawable.ms_restart_alt), stringResource(R.string.clock_reset_value, label))
    }
}

@Composable
internal fun IconShape.label(): String = stringResource(when (this) {
    IconShape.None -> R.string.icon_designer_shape_none; IconShape.Circle -> R.string.icon_designer_shape_circle
    IconShape.Pebble -> R.string.icon_designer_shape_pebble; IconShape.Square -> R.string.icon_designer_shape_square
    IconShape.Gem -> R.string.icon_designer_shape_gem; IconShape.Cookie -> R.string.icon_designer_shape_cookie
})
