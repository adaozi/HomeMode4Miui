package com.galaxyrio.gracelauncher.ui.home

import androidx.annotation.DrawableRes
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ClockBatteryIconStyle

/** Select only official Material Symbols; the percentage remains the exact reading. */
@DrawableRes
internal fun clockBatteryIcon(style: ClockBatteryIconStyle, percent: Int, charging: Boolean): Int {
    val level = percent.coerceIn(0, 100)
    if (charging) return when (style) {
        // Google supplies a single charging symbol for each horizontal series.
        ClockBatteryIconStyle.Android -> R.drawable.ms_battery_android_bolt
        ClockBatteryIconStyle.AndroidFrame -> R.drawable.ms_battery_android_frame_bolt
        // Choose the nearest available nominal charge level, rounding ties up:
        // 0..24 -> 20, 25..39 -> 30, 40..54 -> 50, 55..69 -> 60,
        // 70..84 -> 80, 85..94 -> 90, 95..100 -> full.
        ClockBatteryIconStyle.Bar -> when {
            level < 25 -> R.drawable.ms_battery_charging_20
            level < 40 -> R.drawable.ms_battery_charging_30
            level < 55 -> R.drawable.ms_battery_charging_50
            level < 70 -> R.drawable.ms_battery_charging_60
            level < 85 -> R.drawable.ms_battery_charging_80
            level < 95 -> R.drawable.ms_battery_charging_90
            else -> R.drawable.ms_battery_charging_full
        }
    }
    // Seven fill steps, with the full symbol reserved for 100%:
    // 0..14 -> 0, 15..28 -> 1, 29..42 -> 2, 43..57 -> 3,
    // 58..71 -> 4, 72..85 -> 5, 86..99 -> 6, 100 -> full.
    val index = level * 7 / 100
    return when (style) {
        ClockBatteryIconStyle.Android -> androidBatteryIcons[index]
        ClockBatteryIconStyle.AndroidFrame -> frameBatteryIcons[index]
        ClockBatteryIconStyle.Bar -> barBatteryIcons[index]
    }
}

private val androidBatteryIcons = intArrayOf(
    R.drawable.ms_battery_android_0,
    R.drawable.ms_battery_android_1,
    R.drawable.ms_battery_android_2,
    R.drawable.ms_battery_android_3,
    R.drawable.ms_battery_android_4,
    R.drawable.ms_battery_android_5,
    R.drawable.ms_battery_android_6,
    R.drawable.ms_battery_android_full,
)

private val frameBatteryIcons = intArrayOf(
    // Frame has no zero symbol. Keep its distinct outline and use the lowest
    // official fill level for both the zero and first buckets (0..28%).
    R.drawable.ms_battery_android_frame_1,
    R.drawable.ms_battery_android_frame_1,
    R.drawable.ms_battery_android_frame_2,
    R.drawable.ms_battery_android_frame_3,
    R.drawable.ms_battery_android_frame_4,
    R.drawable.ms_battery_android_frame_5,
    R.drawable.ms_battery_android_frame_6,
    R.drawable.ms_battery_android_frame_full,
)

private val barBatteryIcons = intArrayOf(
    R.drawable.ms_battery_0_bar,
    R.drawable.ms_battery_1_bar,
    R.drawable.ms_battery_2_bar,
    R.drawable.ms_battery_3_bar,
    R.drawable.ms_battery_4_bar,
    R.drawable.ms_battery_5_bar,
    R.drawable.ms_battery_6_bar,
    R.drawable.ms_battery_full,
)
