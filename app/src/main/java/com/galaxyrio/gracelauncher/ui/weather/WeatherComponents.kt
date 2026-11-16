package com.galaxyrio.gracelauncher.ui.weather

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticOnClick
import androidx.compose.ui.semantics.onLongClick as semanticOnLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.weather.WeatherCondition
import com.galaxyrio.gracelauncher.data.weather.WeatherCurrent
import com.galaxyrio.gracelauncher.data.weather.WeatherDay
import com.galaxyrio.gracelauncher.data.weather.WeatherMeasure
import com.galaxyrio.gracelauncher.data.weather.WeatherSnapshot
import com.galaxyrio.gracelauncher.data.weather.WeatherTemperature
import com.galaxyrio.gracelauncher.data.weather.WeatherWind
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.galaxyrio.gracelauncher.data.ClockWeather
import com.galaxyrio.gracelauncher.data.ClockWeatherField
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items

/** The header uses the same availability rules as the rendered row, including metadata-only choices. */
fun hasHomeWeatherContent(
    current: WeatherCurrent?,
    config: ClockWeather,
    snapshot: WeatherSnapshot? = null,
    now: Instant = Instant.now(),
): Boolean = homeWeatherFields(current, config, snapshot, now).isNotEmpty()

private val WeatherSpeedUnits = setOf("mps", "kph", "kn", "mph", "ftps", "bf")

private fun WeatherTemperature?.isAvailable(): Boolean =
    this != null && value.isFinite() && unit in setOf("c", "f", "k")

private fun WeatherMeasure?.isAvailable(unit: String, maximum: Double = Double.POSITIVE_INFINITY): Boolean =
    this != null && this.unit == unit && value.isFinite() && value >= 0 && value <= maximum

private fun WeatherWind?.hasDirection(): Boolean = this?.degree?.let {
    it.isFinite() && (it == -1.0 || it in 0.0..360.0)
} == true

private fun WeatherWind?.hasSpeed(): Boolean = this?.speed?.let {
    it.value.isFinite() && it.value >= 0 && it.unit in WeatherSpeedUnits
} == true

private fun WeatherSnapshot?.currentDay(now: Instant): WeatherDay? {
    if (this == null) return null
    val today = now.atZone(location.timeZone).toLocalDate()
    return daily.firstOrNull { it.date == today }
}

private fun homeWeatherFields(current: WeatherCurrent?, config: ClockWeather, snapshot: WeatherSnapshot?, now: Instant): List<ClockWeatherField> {
    val day = snapshot.currentDay(now)
    return config.visibleFields.filter { field ->
        when (field) {
            ClockWeatherField.Icon -> current != null && current.condition != WeatherCondition.Unknown
            ClockWeatherField.Temperature -> current?.temperature.isAvailable()
            ClockWeatherField.Description -> current != null &&
                (!current.description.isNullOrBlank() || current.condition != WeatherCondition.Unknown)
            ClockWeatherField.FeelsLike -> current?.feelsLike.isAvailable()
            ClockWeatherField.Humidity -> current?.humidity.isAvailable("percent", 100.0)
            ClockWeatherField.Wind -> current?.wind.hasDirection() || current?.wind.hasSpeed()
            ClockWeatherField.AirQuality -> current?.airQuality.isAvailable("aqi")
            ClockWeatherField.UV -> current?.uv.isAvailable("uvi")
            ClockWeatherField.Location -> !snapshot?.location?.name.isNullOrBlank()
            ClockWeatherField.HighLow -> day?.high.isAvailable() || day?.low.isAvailable()
            ClockWeatherField.UpdatedAt -> snapshot?.updatedAt != null
        }
    }
}

private data class HomeWeatherPart(
    val field: ClockWeatherField,
    val text: String,
    val spoken: String = text,
    val labelIcon: Int? = null,
)

@Composable
fun HomeWeather(
    current: WeatherCurrent?,
    color: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    iconColor: Color = color,
    config: ClockWeather = ClockWeather(),
    snapshot: WeatherSnapshot? = null,
    now: Instant = Instant.now(),
) {
    val fields = homeWeatherFields(current, config, snapshot, now)
    if (fields.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val description = weatherDescription(current?.condition ?: WeatherCondition.Unknown, current?.description)
    val day = snapshot.currentDay(now)
    val parts = fields.map { field ->
        val labeled = field in config.labeledFields
        when (field) {
            ClockWeatherField.Icon -> HomeWeatherPart(field, "", description)
            ClockWeatherField.Temperature -> HomeWeatherPart(field, current!!.temperature!!.format(),
                current.temperature.format(showUnit = true))
            ClockWeatherField.Description -> HomeWeatherPart(field, description)
            ClockWeatherField.FeelsLike -> {
                val temperature = current!!.feelsLike!!
                HomeWeatherPart(field,
                    if (labeled) stringResource(R.string.weather_feels_like_value, temperature.format()) else temperature.format(),
                    stringResource(R.string.weather_feels_like_value, temperature.format(showUnit = true)))
            }
            ClockWeatherField.Humidity -> {
                val value = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 1 }
                    .format(current!!.humidity!!.value / 100)
                HomeWeatherPart(field, value, stringResource(R.string.weather_humidity_value, value),
                    labelIcon = R.drawable.ms_humidity_percentage.takeIf { labeled })
            }
            ClockWeatherField.Wind -> {
                val value = homeWindText(current!!.wind!!, locale)
                val spoken = stringResource(R.string.weather_wind_value, value)
                HomeWeatherPart(field, if (labeled) spoken else value, spoken)
            }
            ClockWeatherField.AirQuality -> {
                val measure = current!!.airQuality!!
                val value = weatherNumber(measure.value, locale)
                val spoken = stringResource(R.string.weather_air_quality_value, value)
                HomeWeatherPart(field, if (labeled) "AQI $value" else value,
                    listOfNotNull(spoken, measure.description?.takeIf(String::isNotBlank)).joinToString(", "))
            }
            ClockWeatherField.UV -> {
                val measure = current!!.uv!!
                val value = weatherNumber(measure.value, locale)
                val spoken = stringResource(R.string.weather_uv_value, value)
                HomeWeatherPart(field, if (labeled) "UV $value" else value,
                    listOfNotNull(spoken, measure.description?.takeIf(String::isNotBlank)).joinToString(", "))
            }
            ClockWeatherField.Location -> {
                val value = snapshot!!.location.name
                HomeWeatherPart(field, value, "${stringResource(R.string.clock_weather_field_location)} $value",
                    labelIcon = R.drawable.ms_location_on.takeIf { labeled })
            }
            ClockWeatherField.HighLow -> {
                val high = day?.high?.takeIf { it.isAvailable() }
                val low = day?.low?.takeIf { it.isAvailable() }
                val text = listOfNotNull(high?.format(), low?.format()).joinToString(" / ")
                val spoken = listOfNotNull(
                    high?.let { stringResource(R.string.weather_high_value, it.format(showUnit = true)) },
                    low?.let { stringResource(R.string.weather_low_value, it.format(showUnit = true)) },
                ).joinToString(" ")
                HomeWeatherPart(field, text, spoken, labelIcon = R.drawable.ms_mode_heat_cool.takeIf { labeled })
            }
            ClockWeatherField.UpdatedAt -> {
                val updated = snapshot!!.updatedAt!!
                val today = now.atZone(snapshot.location.timeZone).toLocalDate()
                val date = if (updated.atZone(snapshot.location.timeZone).toLocalDate() == today) "" else "MMMd"
                val pattern = DateFormat.getBestDateTimePattern(locale, date + if (use24Hour) "Hm" else "hm")
                val formatted = DateTimeFormatter.ofPattern(pattern, locale).withZone(snapshot.location.timeZone).format(updated)
                HomeWeatherPart(field, formatted, stringResource(R.string.weather_updated, formatted),
                    labelIcon = R.drawable.ms_update.takeIf { labeled })
            }
        }
    }
    val summary = if (fields == listOf(ClockWeatherField.Icon, ClockWeatherField.Temperature))
        stringResource(R.string.weather_current_description, description, current!!.temperature!!.format(showUnit = true))
    else parts.map { it.spoken }.distinct().joinToString(", ")
    val interactions = remember { MutableInteractionSource() }
    Box(
        modifier.testTag("home_weather")
            .then(if (onClick != null) Modifier.combinedClickable(
                interactionSource = interactions, indication = null,
                role = Role.Button, onClick = onClick, onLongClick = onLongClick,
            ) else Modifier)
            .clearAndSetSemantics {
                contentDescription = summary
                if (onClick != null) {
                    role = Role.Button
                    semanticOnClick { onClick(); true }
                    if (onLongClick != null) semanticOnLongClick { onLongClick(); true }
                }
            },
    ) {
        // Align to the font's text metrics, not the line box (which includes
        // leading). An em-sized icon also follows the user's font-size setting.
        Text(
            text = buildAnnotatedString {
                parts.forEachIndexed { index, part ->
                    if (index > 0) append(" ")
                    if (part.field == ClockWeatherField.Icon) appendInlineContent("weather", part.spoken)
                    else {
                        if (part.labelIcon != null) {
                            appendInlineContent("weather_label_${part.field.name}")
                            append(" ")
                        }
                        append(part.text)
                    }
                }
            },
            modifier = Modifier.testTag("home_weather_temperature"),
            inlineContent = buildMap {
                val placeholder = Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter)
                put("weather", InlineTextContent(placeholder) {
                    WeatherIcon(current?.condition ?: WeatherCondition.Unknown, current?.isDaylight != false,
                        Modifier.fillMaxSize().testTag("home_weather_icon"), tint = iconColor)
                })
                parts.forEach { part ->
                    part.labelIcon?.let { icon ->
                        put("weather_label_${part.field.name}", InlineTextContent(placeholder) {
                            Icon(painterResource(icon), contentDescription = null,
                                modifier = Modifier.fillMaxSize().testTag("home_weather_label:${part.field.name}"), tint = iconColor)
                        })
                    }
                }
            },
            style = textStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (onClick != null) {
            // Round only the feedback layer: clipping the text cuts into raised glyphs such as °.
            Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.small)
                .indication(interactions, ripple(color = color)))
        }
    }
}

internal fun weatherNumber(value: Double, locale: Locale): String = NumberFormat.getNumberInstance(locale).apply {
    maximumFractionDigits = 1
    isGroupingUsed = false
}.format(value)

@Composable
internal fun homeWindText(wind: WeatherWind, locale: Locale): String {
    val direction = if (!wind.hasDirection()) null else stringResource(
        if (wind.degree == -1.0) R.string.weather_wind_variable else {
            val directions = listOf(R.string.weather_wind_north, R.string.weather_wind_northeast,
                R.string.weather_wind_east, R.string.weather_wind_southeast, R.string.weather_wind_south,
                R.string.weather_wind_southwest, R.string.weather_wind_west, R.string.weather_wind_northwest)
            directions[((wind.degree!! + 22.5) / 45).toInt() % directions.size]
        },
    )
    val speed = if (!wind.hasSpeed()) null else {
        val measure = wind.speed!!
        val number = weatherNumber(measure.value, locale)
        if (measure.unit == "bf") stringResource(R.string.weather_wind_beaufort, number)
        else "$number ${when (measure.unit) {
            "mps" -> "m/s"
            "kph" -> "km/h"
            "kn" -> "kn"
            "mph" -> "mph"
            "ftps" -> "ft/s"
            else -> measure.unit
        }}"
    }
    return listOfNotNull(direction, speed).joinToString(" ")
}

@Composable
fun WeatherSourceNote(snapshot: WeatherSnapshot, now: Instant, onOpenWeatherApp: () -> Unit) =
    WeatherSourceNote(snapshot, now, onOpenWeatherApp, trailing = {})

@Composable
fun WeatherSourceNote(snapshot: WeatherSnapshot, now: Instant, onOpenWeatherApp: (() -> Unit)?,
    trailing: @Composable () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val timeFormat = remember(locale, use24Hour, snapshot.location.timeZone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24Hour) "MMMdHm" else "MMMdhm"), locale)
            .withZone(snapshot.location.timeZone)
    }
    val updated = snapshot.updatedAt
    val stale = updated == null || Duration.between(updated, now).toHours() >= 3
    val updateLabel = if (updated == null) stringResource(R.string.weather_update_unknown) else {
        stringResource(if (stale) R.string.weather_outdated else R.string.weather_updated, timeFormat.format(updated))
    }
    Row(
        Modifier.fillMaxWidth().testTag("weather_source")
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${snapshot.location.name} · $updateLabel",
            modifier = Modifier.weight(1f).testTag("weather_location")
                .clip(RoundedCornerShape(8.dp)).then(if (onOpenWeatherApp != null)
                    Modifier.clickable(role = Role.Button, onClick = onOpenWeatherApp) else Modifier)
                .padding(vertical = 4.dp),
            fontSize = 11.sp, lineHeight = 16.sp,
            color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        trailing()
    }
}

/** Source and license details live in weather settings, not the compact agenda. */
@Composable
fun WeatherAttribution(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) {
    val sourceLabel = snapshot.attribution.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.weather_via_source)
    val linkColor = MaterialTheme.colorScheme.primary
    val credits = remember(sourceLabel, snapshot.sourceLinks, linkColor) {
        weatherCredits(sourceLabel, snapshot.sourceLinks, linkColor)
    }
    Text(credits, modifier = modifier.fillMaxWidth().testTag("weather_credits"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Preserve the complete credit text and attach source/license links supplied by the weather source. */
private fun weatherCredits(text: String, links: Map<String, String>, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val matchedLabels = mutableSetOf<String>()
    val linkedRanges = mutableListOf<IntRange>()
    val validLinks = links.filter { (label, url) -> label.isNotBlank() && (url.startsWith("https://") || url.startsWith("http://")) }
    // Link longer names first so nested labels never create overlapping clickable ranges.
    validLinks.entries.sortedByDescending { it.key.length }.forEach { (label, url) ->
        var start = text.indexOf(label)
        while (start >= 0) {
            val end = start + label.length
            if (linkedRanges.none { it.first < end && start <= it.last }) {
                addLink(LinkAnnotation.Url(url, styles), start, end)
                linkedRanges += start until end
                matchedLabels += label
            }
            start = text.indexOf(label, end)
        }
    }
    validLinks.filterKeys { it !in matchedLabels }.forEach { (label, url) ->
        if (length > 0) append(" · ")
        withLink(LinkAnnotation.Url(url, styles)) { append(label) }
    }
}

@Composable
private fun WeatherTemperature?.readableTemperature(): String =
    this?.format(showUnit = true) ?: stringResource(R.string.weather_temperature_unknown)

@Composable
internal fun weatherDescription(condition: WeatherCondition, supplied: String?): String =
    supplied?.takeIf { it.isNotBlank() } ?: stringResource(when (condition) {
        WeatherCondition.Clear -> R.string.weather_clear
        WeatherCondition.PartlyCloudy -> R.string.weather_partly_cloudy
        WeatherCondition.Cloudy -> R.string.weather_cloudy
        WeatherCondition.Rain -> R.string.weather_rain
        WeatherCondition.Snow -> R.string.weather_snow
        WeatherCondition.Thunderstorm -> R.string.weather_thunderstorm
        WeatherCondition.Fog -> R.string.weather_fog
        WeatherCondition.Wind -> R.string.weather_wind
        WeatherCondition.Hail -> R.string.weather_hail
        WeatherCondition.Sleet -> R.string.weather_sleet
        WeatherCondition.Unknown -> R.string.weather_unknown
    })

// ---- 上游 1.2.0 兼容渲染（1.2.4 删除，AgendaSheet 仍消费；P5 切 AgendaPanel 后移除）----
@Composable
fun DailyWeather(
    day: WeatherDay?,
    current: WeatherCurrent?,
    modifier: Modifier = Modifier,
) {
    if (day == null && current == null) return
    val condition = day?.condition ?: current!!.condition
    val description = weatherDescription(condition, day?.description ?: current?.description)
    val high = day?.high
    val low = day?.low
    val summary = if (high != null || low != null) {
        stringResource(R.string.weather_daily_description, description, high.readableTemperature(), low.readableTemperature())
    } else {
        stringResource(R.string.weather_current_description, description, current?.temperature.readableTemperature())
    }
    Row(
        modifier.clearAndSetSemantics { contentDescription = summary },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        WeatherIcon(condition, isDaylight = day != null || current?.isDaylight != false, modifier = Modifier.size(21.dp))
        if (high != null || low != null) {
            Text(high?.format() ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("/ ${low?.format() ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
        } else {
            Text(current?.temperature?.format() ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
fun HourlyWeather(snapshot: WeatherSnapshot, now: Instant, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val timeFormat = remember(locale, use24Hour, snapshot.location.timeZone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24Hour) "HH" else "ha"), locale)
            .withZone(snapshot.location.timeZone)
    }
    val spokenTimeFormat = remember(locale, use24Hour, snapshot.location.timeZone) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if (use24Hour) "EHm" else "Ehm"), locale)
            .withZone(snapshot.location.timeZone)
    }
    val hours = remember(snapshot.hourly, now) {
        snapshot.hourly.filter { it.at.isAfter(now) }.take(24)
    }
    val current = snapshot.current?.takeIf { it.temperature != null }
    if (current == null && hours.isEmpty()) return
    LazyRow(
        modifier.fillMaxWidth().testTag("weather_hourly")
            .clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceBright),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
    ) {
        if (current != null) item(key = "now") {
            HourlyWeatherCell(
                time = stringResource(R.string.weather_now),
                spokenTime = stringResource(R.string.weather_now),
                condition = current.condition,
                isDaylight = current.isDaylight,
                description = current.description,
                temperature = current.temperature,
                tag = "weather_hour:now",
            )
        }
        items(hours, key = { it.at }) { hour ->
            HourlyWeatherCell(
                time = timeFormat.format(hour.at),
                spokenTime = spokenTimeFormat.format(hour.at),
                condition = hour.condition,
                isDaylight = hour.isDaylight,
                description = hour.description,
                temperature = hour.temperature,
                tag = "weather_hour:${hour.at}",
            )
        }
    }
}

@Composable
private fun HourlyWeatherCell(
    time: String,
    spokenTime: String,
    condition: WeatherCondition,
    isDaylight: Boolean,
    description: String?,
    temperature: WeatherTemperature?,
    tag: String,
) {
    val descriptionText = weatherDescription(condition, description)
    val summary = stringResource(R.string.weather_hour_description, spokenTime, descriptionText, temperature.readableTemperature())
    Column(
        Modifier.width(72.dp).testTag(tag).clearAndSetSemantics { contentDescription = summary },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(time, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Spacer(Modifier.height(12.dp))
        WeatherIcon(condition, isDaylight, Modifier.size(27.dp))
        Spacer(Modifier.height(12.dp))
        Text(temperature?.format() ?: "—", fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
