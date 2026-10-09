package com.galaxyrio.gracelauncher.ui.weather

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.weather.WeatherCondition

/** Local Material Symbols Outlined artwork; day/night follows the forecast, tint follows the UI. */
@Composable
fun WeatherIcon(
    condition: WeatherCondition,
    isDaylight: Boolean,
    modifier: Modifier = Modifier,
    description: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val resource = weatherIconResource(condition, isDaylight)
    if (resource != null) {
        Icon(painterResource(resource), contentDescription = description, modifier = modifier.size(24.dp), tint = tint)
    } else {
        // An unavailable forecast must not be misrepresented as a known condition.
        Box(
            modifier.size(24.dp).clearAndSetSemantics {
                if (description != null) contentDescription = description
            },
            contentAlignment = Alignment.Center,
        ) {
            Text("—", fontSize = 18.sp, color = tint)
        }
    }
}

@DrawableRes
internal fun weatherIconResource(
    condition: WeatherCondition,
    isDaylight: Boolean,
): Int? = when (condition) {
    WeatherCondition.Clear -> if (isDaylight) R.drawable.ms_sunny else R.drawable.ms_clear_night
    WeatherCondition.PartlyCloudy -> if (isDaylight) R.drawable.ms_partly_cloudy_day else R.drawable.ms_partly_cloudy_night
    WeatherCondition.Cloudy -> R.drawable.ms_cloud
    WeatherCondition.Rain -> R.drawable.ms_rainy
    WeatherCondition.Snow -> R.drawable.ms_weather_snowy
    WeatherCondition.Thunderstorm -> R.drawable.ms_thunderstorm
    WeatherCondition.Fog -> R.drawable.ms_foggy
    WeatherCondition.Wind -> R.drawable.ms_air
    WeatherCondition.Hail -> R.drawable.ms_weather_hail
    WeatherCondition.Sleet -> R.drawable.ms_weather_mix
    WeatherCondition.Unknown -> null
}
