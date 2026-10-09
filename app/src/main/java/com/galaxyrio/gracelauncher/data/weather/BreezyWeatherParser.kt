package com.galaxyrio.gracelauncher.data.weather

import android.database.Cursor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject

/** Minimal independent decoder of the public 0.x contract, not Breezy's internal database model. */
internal object BreezyWeatherParser {
    private const val MAX_UNCOMPRESSED_BYTES = 8 * 1024 * 1024

    fun location(cursor: Cursor): WeatherLocation {
        val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
        require(!id.isNullOrBlank()) { "Missing Breezy location id" }
        val zone = cursor.string("timezone")?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
        return WeatherLocation(
            id = id,
            name = cursor.string("custom_name") ?: cursor.string("city") ?: cursor.string("district")
                ?: cursor.string("admin1") ?: cursor.string("country") ?: "Breezy Weather",
            timeZone = zone,
            isCurrentPosition = cursor.getColumnIndex("is_current_position").let { it >= 0 && cursor.getInt(it) > 0 },
        )
    }

    fun weather(blob: ByteArray, location: WeatherLocation, now: Instant = Instant.now()): WeatherSnapshot? {
        val json = JSONObject(decompress(blob))
        val hourly = json.optJSONArray("hourly").objects().mapNotNull { item ->
            val at = item.instant("date") ?: return@mapNotNull null
            WeatherHour(
                at = at,
                temperature = item.temperature(),
                condition = condition(item.string("weatherCode")),
                isDaylight = item.optBoolean("isDaylight", true),
                description = item.string("weatherText"),
            )
        }.distinctBy(WeatherHour::at).sortedBy(WeatherHour::at)
        val currentDaylight = hourly.minByOrNull { Duration.between(now, it.at).abs() }
            ?.takeIf { Duration.between(now, it.at).abs() <= Duration.ofHours(2) }?.isDaylight
            // Version 0.x does not expose current daylight or sunrise/sunset. Use nearby hourly data.
            ?: (now.atZone(location.timeZone).hour in 6..17)
        val current = json.optJSONObject("current")?.let { item ->
            val temperature = item.temperature()
            val condition = condition(item.string("weatherCode"))
            val description = item.string("weatherText")
            if (temperature == null && condition == WeatherCondition.Unknown && description == null) null
            else WeatherCurrent(temperature, condition, currentDaylight, description)
        }
        val daily = json.optJSONArray("daily").objects().mapNotNull { item ->
            val date = item.instant("date")?.atZone(location.timeZone)?.toLocalDate() ?: return@mapNotNull null
            val day = item.optJSONObject("day")
            val night = item.optJSONObject("night")
            WeatherDay(
                date = date,
                high = day?.temperature(),
                low = night?.temperature(),
                condition = condition(day?.string("weatherCode") ?: night?.string("weatherCode")),
                description = day?.string("weatherText") ?: night?.string("weatherText"),
            )
        }.distinctBy(WeatherDay::date).sortedBy(WeatherDay::date)
        if (current == null && hourly.isEmpty() && daily.isEmpty()) return null

        // Only credit the current/forecast features displayed by this integration.
        val sources = json.optJSONObject("sources")
        val credits = listOfNotNull(sources?.optJSONObject("current"), sources?.optJSONObject("forecast"))
        val attribution = credits.mapNotNull { it.string("text") }.distinct().joinToString(" · ")
        val links = buildMap {
            credits.forEach { source ->
                val sourceLinks = source.optJSONObject("links") ?: return@forEach
                sourceLinks.keys().forEach { label ->
                    sourceLinks.string(label)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                        ?.let { put(label, it) }
                }
            }
        }
        return WeatherSnapshot(
            location = location,
            current = current,
            hourly = hourly,
            daily = daily,
            updatedAt = json.instant("refreshTime"),
            attribution = listOf("Breezy Weather", attribution).filter(String::isNotBlank).joinToString(" · "),
            sourceLinks = links,
        )
    }

    internal fun condition(code: String?): WeatherCondition = when (code?.lowercase(Locale.ROOT)) {
        "clear" -> WeatherCondition.Clear
        "partly_cloudy" -> WeatherCondition.PartlyCloudy
        "cloudy" -> WeatherCondition.Cloudy
        "rain" -> WeatherCondition.Rain
        "snow" -> WeatherCondition.Snow
        "thunder", "thunderstorm" -> WeatherCondition.Thunderstorm
        "fog", "haze" -> WeatherCondition.Fog
        "wind" -> WeatherCondition.Wind
        "hail" -> WeatherCondition.Hail
        "sleet" -> WeatherCondition.Sleet
        else -> WeatherCondition.Unknown
    }

    private fun JSONObject.temperature(): WeatherTemperature? {
        val measure = optJSONObject("temperature")?.optJSONObject("temperature") ?: return null
        val value = (measure.opt("value") as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
        val unit = measure.string("unit")?.lowercase(Locale.ROOT) ?: return null
        // Never silently label an unknown/missing scale as Celsius.
        if (unit !in setOf("c", "f", "k")) return null
        return WeatherTemperature(value, unit)
    }

    private fun JSONObject.instant(key: String): Instant? = (opt(key) as? Number)?.toLong()?.let {
        runCatching { Instant.ofEpochMilli(it) }.getOrNull()
    }

    private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }
        ?.let(::getString)?.trim()?.takeIf(String::isNotEmpty)

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
        (0 until length()).mapNotNull(::optJSONObject)

    private fun decompress(blob: ByteArray): String = GZIPInputStream(ByteArrayInputStream(blob)).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_UNCOMPRESSED_BYTES) throw IOException("Breezy weather data exceeds size limit")
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
