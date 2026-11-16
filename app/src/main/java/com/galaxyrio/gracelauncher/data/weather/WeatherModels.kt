package com.galaxyrio.gracelauncher.data.weather

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

enum class WeatherStatus {
    Disabled, Loading, NotInstalled, PermissionRequired, UnsupportedVersion,
    NoLocations, NoWeather, Ready, Error,
}

data class WeatherState(
    val status: WeatherStatus = WeatherStatus.Disabled,
    val snapshot: WeatherSnapshot? = null,
    val locations: List<WeatherLocation> = emptyList(),
    val selectedLocationId: String? = null,
)

data class WeatherLocation(
    val id: String,
    val name: String,
    val timeZone: ZoneId,
    val isCurrentPosition: Boolean = false,
)

data class WeatherSnapshot(
    val location: WeatherLocation,
    val current: WeatherCurrent?,
    val hourly: List<WeatherHour>,
    val daily: List<WeatherDay>,
    val updatedAt: Instant?,
    val attribution: String,
    val sourceLinks: Map<String, String> = emptyMap(),
)

/** Provider units are preserved; Gadgetbridge's kelvin values are converted to the selected scale. */
data class WeatherTemperature(val value: Double, val unit: String) {
    fun format(showUnit: Boolean = false): String = when (unit) {
        "c" -> "${value.roundToInt()}°${if (showUnit) "C" else ""}"
        "f" -> "${value.roundToInt()}°${if (showUnit) "F" else ""}"
        "k" -> "${value.roundToInt()} K"
        else -> "${value.roundToInt()} $unit"
    }
}

enum class WeatherCondition {
    Clear, PartlyCloudy, Cloudy, Rain, Snow, Thunderstorm, Fog, Wind, Hail, Sleet, Unknown,
}

data class WeatherCurrent(
    val temperature: WeatherTemperature?,
    val condition: WeatherCondition,
    val isDaylight: Boolean,
    val description: String?,
    val feelsLike: WeatherTemperature? = null,
    val humidity: WeatherMeasure? = null,
    val wind: WeatherWind? = null,
    val airQuality: WeatherMeasure? = null,
    val uv: WeatherMeasure? = null,
    val precipitationProbability: WeatherPrecipitation? = null,
    val dewPoint: WeatherTemperature? = null,
    val pressure: WeatherMeasure? = null,
    val cloudCover: WeatherMeasure? = null,
    val visibility: WeatherMeasure? = null,
)

/** Keep the provider's unit and description rather than assuming a regional scale. */
data class WeatherMeasure(val value: Double, val unit: String, val description: String? = null)

data class WeatherWind(
    val degree: Double? = null,
    val speed: WeatherMeasure? = null,
    val gusts: WeatherMeasure? = null,
)

/** Amounts and probabilities retain independently reported totals and precipitation types. */
data class WeatherPrecipitation(
    val total: WeatherMeasure? = null,
    val rain: WeatherMeasure? = null,
    val snow: WeatherMeasure? = null,
    val ice: WeatherMeasure? = null,
    val thunderstorm: WeatherMeasure? = null,
)

data class WeatherHour(
    val at: Instant,
    val temperature: WeatherTemperature?,
    val condition: WeatherCondition,
    val isDaylight: Boolean,
    val description: String?,
    val feelsLike: WeatherTemperature? = null,
    val humidity: WeatherMeasure? = null,
    val wind: WeatherWind? = null,
    val airQuality: WeatherMeasure? = null,
    val uv: WeatherMeasure? = null,
    val precipitationProbability: WeatherPrecipitation? = null,
    val precipitation: WeatherPrecipitation? = null,
    val dewPoint: WeatherTemperature? = null,
    val pressure: WeatherMeasure? = null,
    val cloudCover: WeatherMeasure? = null,
    val visibility: WeatherMeasure? = null,
)

data class WeatherDay(
    val date: LocalDate,
    val high: WeatherTemperature?,
    val low: WeatherTemperature?,
    val condition: WeatherCondition,
    val description: String?,
    val feelsLike: WeatherTemperature? = null,
    val humidity: WeatherMeasure? = null,
    val wind: WeatherWind? = null,
    val airQuality: WeatherMeasure? = null,
    val uv: WeatherMeasure? = null,
)
