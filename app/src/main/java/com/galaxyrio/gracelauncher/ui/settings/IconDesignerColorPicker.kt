@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.IconColor
import com.galaxyrio.gracelauncher.data.IconColorSource
import java.util.Locale

/** Confined to the controls pane: never covers the live icon preview. */
@Composable
internal fun IconDesignerColorPicker(title: String, choice: IconColor?, theme: Int, default: IconColor?,
    onChange: (IconColor?) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier, fallback: Int = theme,
    allowFromApp: Boolean = false, appColor: Int? = null) {
    val initial = choice?.resolve(theme, appColor) ?: fallback
    val source = if (!allowFromApp && choice?.fromApp == true) IconColorSource.Theme
        else choice?.source ?: IconColorSource.Fixed
    val fixed = source == IconColorSource.Fixed
    val automatic = (choice ?: IconColor.Theme).copy(theme = source == IconColorSource.Theme,
        fromApp = source == IconColorSource.FromApp).normalized()
    val initialHsv = remember { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by rememberSaveable { mutableFloatStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(initialHsv[2]) }
    var hex by rememberSaveable { mutableStateOf(colorHex(initial)) }
    fun showColor(color: Int) {
        val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
        hue = hsv[0]; saturation = hsv[1]; brightness = hsv[2]; hex = colorHex(color)
    }
    fun setColor(color: Int) { showColor(color); onChange(IconColor(color)) }
    // Automatic sliders edit the source offsets directly. Fixed HSV stays local;
    // round-tripping each drag through RGB would discard precision and cause jitter.
    fun changeHsv(h: Float = hue, s: Float = saturation, v: Float = brightness) {
        hue = h; saturation = s; brightness = v
        val color = AndroidColor.HSVToColor(floatArrayOf(h, s, v))
        hex = colorHex(color); onChange(IconColor(color))
    }
    val move by rememberUpdatedState<(Offset, androidx.compose.ui.unit.IntSize) -> Unit>({ offset, size ->
        if (fixed && size.width > 0 && size.height > 0)
            changeHsv(s = (offset.x / size.width).coerceIn(0f, 1f), v = (1 - offset.y / size.height).coerceIn(0f, 1f))
    })
    BoxWithConstraints(modifier.fillMaxSize().testTag("icon_designer_color_picker")) {
        val planeHeight = ((maxHeight - LocalSettingsBottomInset.current) * .40f).coerceIn(96.dp, 160.dp)
        Column(Modifier.fillMaxSize().clip(ListItemDefaults.segmentedShapes(0, 1).shape)
            .background(MaterialTheme.colorScheme.surfaceBright).verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = LocalSettingsBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClose, Modifier.testTag("icon_designer_color_close")) {
                    Icon(painterResource(R.drawable.ms_close), stringResource(R.string.icon_designer_color_done))
                }
            }
            DesignerConnectedChoices(IconColorSource.entries.filter { allowFromApp || it != IconColorSource.FromApp },
                source, true, "icon_designer_color_source", { it.label() }) { selected ->
                if (selected == source) return@DesignerConnectedChoices
                when (selected) {
                    IconColorSource.Theme -> { showColor(theme); onChange(IconColor.Theme) }
                    IconColorSource.FromApp -> { showColor(appColor ?: theme); onChange(IconColor.FromApp) }
                    IconColorSource.Fixed -> setColor(initial)
                }
            }
            if (source == IconColorSource.FromApp) Text(stringResource(R.string.icon_color_from_app_summary),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fixed) {
                val planeDescription = stringResource(R.string.icon_designer_saturation_brightness)
                Canvas(Modifier.fillMaxWidth().height(planeHeight).clip(MaterialTheme.shapes.large).testTag("icon_designer_color_plane")
                    .semantics { contentDescription = planeDescription }
                    .pointerInput(Unit) { detectTapGestures { move(it, size) } }
                    .pointerInput(Unit) { detectDragGestures(onDragStart = { move(it, size) }) { change, _ ->
                        change.consume(); move(change.position, size)
                    } }) {
                    drawRect(Brush.horizontalGradient(listOf(Color.White, Color(AndroidColor.HSVToColor(floatArrayOf(hue, 1f, 1f))))))
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    val point = Offset(saturation * size.width, (1 - brightness) * size.height)
                    drawCircle(Color.Black.copy(alpha = .4f), 9.dp.toPx(), point, style = Stroke(4.dp.toPx()))
                    drawCircle(Color.White, 9.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                }
                val hueDescription = stringResource(R.string.icon_designer_hue)
                Slider(hue, { changeHsv(h = it) }, valueRange = 0f..360f,
                    modifier = Modifier.testTag("icon_designer_hue").semantics { contentDescription = hueDescription },
                    track = {
                        Canvas(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape)) {
                            drawRect(Brush.horizontalGradient((0..6).map { Color(AndroidColor.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
                        }
                    })
                OutlinedTextField(value = hex, onValueChange = { raw ->
                    hex = raw.removePrefix("#").take(6).uppercase(Locale.ROOT)
                    if (hex.length == 6) hex.toLongOrNull(16)?.let { setColor((it or 0xFF000000).toInt()) }
                }, label = { Text(stringResource(R.string.icon_designer_hex)) }, prefix = { Text("#") }, singleLine = true,
                    isError = hex.length == 6 && hex.toLongOrNull(16) == null,
                    leadingIcon = {
                        Surface(Modifier.size(24.dp), shape = CircleShape,
                            color = Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))) { }
                    }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth().testTag("icon_designer_hex"))
            } else {
                Text(stringResource(R.string.icon_color_relative_summary), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                DesignerSlider(stringResource(R.string.icon_designer_hue), automatic.hueShift.toFloat(), 0f,
                    -180f..180f, true, "icon_designer_hue_shift", suffix = "°", centered = true) {
                    onChange(automatic.copy(hueShift = it.toInt()))
                }
                DesignerSlider(stringResource(R.string.icon_designer_saturation), automatic.saturationShift.toFloat(), 0f,
                    -100f..100f, true, "icon_designer_saturation_shift", centered = true) {
                    onChange(automatic.copy(saturationShift = it.toInt()))
                }
                DesignerSlider(stringResource(R.string.icon_designer_brightness), automatic.brightnessShift.toFloat(), 0f,
                    -100f..100f, true, "icon_designer_brightness_shift", centered = true) {
                    onChange(automatic.copy(brightnessShift = it.toInt()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onChange(default); onClose() }, Modifier.testTag("icon_designer_color_reset")) {
                    Text(stringResource(R.string.icon_designer_reset))
                }
                FilledTonalButton(onClose, Modifier.testTag("icon_designer_color_done")) { Text(stringResource(R.string.icon_designer_color_done)) }
            }
        }
    }
}

private fun colorHex(color: Int) = String.format(Locale.ROOT, "%06X", color and 0xFFFFFF)
