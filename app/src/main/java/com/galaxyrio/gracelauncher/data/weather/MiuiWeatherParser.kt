package com.galaxyrio.gracelauncher.data.weather

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 小米天气（com.miui.weather2）WeatherProvider 的 weather_type 解码。
 *
 * provider 返回的 weather_type 是 V7 编码；小米天气内部经
 * WeatherType.convertV7WeatherTypeToV6WetherType 折算回 V6 家族（0-26 + 99），
 * 该映射表自 apk 16.0.7.17 的 SparseIntArray clinit 逐条还原（tools 逆向记录）。
 */
internal object MiuiWeatherParser {

    /** V7 → V6（源：WeatherType$<clinit> V7V6_MAP，缺省项按 99=NULLDATA 处理）。 */
    private val V7_TO_V6 = mapOf(
        0 to 0, 1 to 1, 2 to 2, 3 to 8, 4 to 7, 5 to 22, 6 to 12, 7 to 11,
        8 to 10, 9 to 9, 10 to 6, 11 to 23, 12 to 54, 13 to 14, 14 to 17,
        15 to 16, 16 to 9, 18 to 3, 19 to 25, 20 to 19, 21 to 10, 22 to 9,
        23 to 6, 24 to 23, 25 to 54, 26 to 16, 27 to 15, 28 to 13, 29 to 23,
        30 to 26, 31 to 25, 32 to 3, 33 to 17, 49 to 3, 53 to 24, 54 to 24,
        55 to 24, 56 to 24, 57 to 3, 58 to 3, 301 to 11, 99 to 99,
    )

    /**
     * V6 家族语义 → 桌面条件枚举（V6 0-26 对齐小米天气经典编码：
     * 0 晴 / 1 多云 / 2 阴 / 3 阵雨 / 4 雷阵雨 / 5 雷阵雨伴冰雹 / 6 雨夹雪 /
     * 7-12 雨 / 13-16 雪 / 17 雾 / 18 冻雨 / 19 沙尘暴 / 20-22 雨 /
     * 23-24 浮尘扬沙 / 25 霾 / 26 雾霾 / 99 无数据）。
     */
    fun condition(weatherType: Int): WeatherCondition {
        // 实测校准（2026-10-13 装机）：夜间逐小时行 weather_type=11 → 晴夜
        // （V7V6_MAP 11→23 会错落到浮尘档；夜间图标由 isDaylight 驱动）
        if (weatherType == 11) return WeatherCondition.Clear
        val v6 = V7_TO_V6[weatherType] ?: 99
        return when (v6) {
            0 -> WeatherCondition.Clear
            1 -> WeatherCondition.PartlyCloudy
            2 -> WeatherCondition.Cloudy
            4, 5 -> WeatherCondition.Thunderstorm
            6, 18 -> WeatherCondition.Sleet
            7, 8, 9, 10, 11, 12, 20, 21, 22, 3 -> WeatherCondition.Rain
            13, 14, 15, 16 -> WeatherCondition.Snow
            17, 25, 26 -> WeatherCondition.Fog
            19, 23, 24 -> WeatherCondition.Wind
            else -> WeatherCondition.Unknown
        }
    }

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm", "yyyy-MM-dd",
    )

    /** publish_time / date_time 防御性解析：epoch（s 或 ms）→ 常见文本格式 → null。 */
    fun parseTime(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { epoch ->
            // 秒级 (~1.7e9) 与毫秒级 (~1.7e12) 都认；太小视为无效
            return when {
                epoch >= 1_000_000_000_000 -> Instant.ofEpochMilli(epoch)
                epoch >= 1_000_000_000 -> Instant.ofEpochSecond(epoch)
                else -> null
            }
        }
        for (pattern in TIME_PATTERNS) {
            runCatching {
                return java.time.LocalDateTime.parse(text, java.time.format.DateTimeFormatter.ofPattern(pattern))
                    .atZone(zone).toInstant()
            }
        }
        return null
    }

    /** sunrise/sunset 形如 "06:12"（个别带日期）；返回今天的时刻毫秒，失败 null。 */
    fun parseClock(raw: String?, now: ZonedDateTime): Long? {
        val text = raw?.trim().orEmpty()
        val hhmm = text.takeLast(5)
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return now.toLocalDate().atTime(hour, minute).atZone(now.zone).toInstant().toEpochMilli()
    }

    /**
     * temperature_range 实测格式 "17℃~28℃"（2026-10-13 装机；旧协议为 "17/28"）。
     * 提取前两个数字，低/高归一；不足两个数视为脏数据返回 null。
     */
    fun parseRange(raw: String?): Pair<Double, Double>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val nums = Regex("-?\\d+(?:\\.\\d+)?").findAll(text)
            .mapNotNull { it.value.toDoubleOrNull() }
            .toList()
        if (nums.size < 2) return null
        return Pair(minOf(nums[0], nums[1]), maxOf(nums[0], nums[1]))
    }

    fun isDaylight(nowMillis: Long, sunrise: Long?, sunset: Long?): Boolean {
        if (sunrise == null || sunset == null) return true
        return nowMillis in sunrise until sunset
    }
}
