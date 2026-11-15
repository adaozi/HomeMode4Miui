package com.galaxyrio.gracelauncher.data.weather

import android.content.res.Resources
import androidx.core.text.util.LocalePreferences
import org.json.JSONObject

enum class WeatherConnectionMode { LocationUpdateNotifier, Gadgetbridge }

enum class GadgetbridgeTemperatureUnit {
    System, Celsius, Fahrenheit;

    fun resolvedUnit(): String = when (this) {
        Celsius -> "c"
        Fahrenheit -> "f"
        System -> when (LocalePreferences.getTemperatureUnit(Resources.getSystem().configuration.locales[0])) {
            LocalePreferences.TemperatureUnit.FAHRENHEIT -> "f"
            LocalePreferences.TemperatureUnit.KELVIN -> "k"
            else -> "c"
        }
    }
}

data class WeatherConnectionPreferences(
    val mode: WeatherConnectionMode = WeatherConnectionMode.LocationUpdateNotifier,
    val gadgetbridgeLocationId: String? = null,
    val temperatureUnit: GadgetbridgeTemperatureUnit = GadgetbridgeTemperatureUnit.System,
    /** A profile-qualified launcher activity; null preserves the original Breezy Weather target. */
    val appKey: String? = null,
) {
    fun encode(): String = JSONObject()
        .put("mode", mode.name)
        .put("gadgetbridgeLocationId", gadgetbridgeLocationId)
        .put("temperatureUnit", temperatureUnit.name)
        .put("appKey", appKey)
        .toString()

    companion object {
        fun decode(value: String?): WeatherConnectionPreferences = runCatching {
            val json = JSONObject(value ?: return WeatherConnectionPreferences())
            WeatherConnectionPreferences(
                mode = WeatherConnectionMode.entries.firstOrNull { it.name == json.optString("mode") }
                    ?: WeatherConnectionMode.LocationUpdateNotifier,
                gadgetbridgeLocationId = (json.opt("gadgetbridgeLocationId") as? String)?.takeIf(String::isNotBlank),
                temperatureUnit = GadgetbridgeTemperatureUnit.entries.firstOrNull { it.name == json.optString("temperatureUnit") }
                    ?: GadgetbridgeTemperatureUnit.System,
                appKey = (json.opt("appKey") as? String)?.takeIf(String::isNotBlank),
            )
        }.getOrDefault(WeatherConnectionPreferences())
    }
}
