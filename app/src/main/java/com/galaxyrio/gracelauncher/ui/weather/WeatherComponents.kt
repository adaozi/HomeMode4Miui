package com.galaxyrio.gracelauncher.ui.weather

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.galaxyrio.gracelauncher.data.weather.WeatherSnapshot
import com.galaxyrio.gracelauncher.data.weather.WeatherTemperature
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun HomeWeather(
    current: WeatherCurrent,
    color: Color,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
) {
    val temperature = current.temperature ?: return
    val description = weatherDescription(current.condition, current.description)
    val summary = stringResource(R.string.weather_current_description, description, temperature.format(showUnit = true))
    Box(
        modifier.testTag("home_weather").clearAndSetSemantics { contentDescription = summary },
    ) {
        // Align to the font's text metrics, not the line box (which includes
        // leading). An em-sized icon also follows the user's font-size setting.
        Text(
            text = buildAnnotatedString { appendInlineContent("weather"); append(" ${temperature.format()}") },
            modifier = Modifier.testTag("home_weather_temperature"),
            inlineContent = mapOf("weather" to InlineTextContent(
                Placeholder(1.15.em, 1.15.em, PlaceholderVerticalAlign.TextCenter),
            ) {
                WeatherIcon(current.condition, current.isDaylight,
                    Modifier.fillMaxSize().testTag("home_weather_icon"), tint = color)
            }),
            style = textStyle, color = color, maxLines = 1,
        )
    }
}

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

@Composable
fun WeatherSourceNote(snapshot: WeatherSnapshot, now: Instant, onOpenWeather: () -> Unit) {
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
    Column(
        Modifier.fillMaxWidth().testTag("weather_source")
            .padding(vertical = 8.dp),
    ) {
        Text(
            "${snapshot.location.name} · $updateLabel",
            modifier = Modifier.fillMaxWidth().testTag("weather_location")
                .clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onOpenWeather)
                .padding(vertical = 4.dp),
            fontSize = 11.sp, lineHeight = 16.sp,
            color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
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
private fun weatherDescription(condition: WeatherCondition, supplied: String?): String =
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
