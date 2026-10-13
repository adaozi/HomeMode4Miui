package com.galaxyrio.gracelauncher.data.weather

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.core.net.toUri
import java.io.IOException
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---- cursor 读取辅助（文件级：WeatherRow 工厂也要用） ----

private fun Cursor.optStringMw(column: String): String? {
    val idx = getColumnIndex(column)
    if (idx < 0 || isNull(idx)) return null
    return getString(idx)
}

private fun Cursor.optIntMw(column: String): Int? {
    val idx = getColumnIndex(column)
    if (idx < 0 || isNull(idx)) return null
    return runCatching { getInt(idx) }.getOrNull()
}

/** weather 表一行（最小 day 行 = 实况，其余 = 预报）。 */
private data class WeatherRow(
    val day: Int,
    val publishTime: String?,
    val cityName: String?,
    val description: String?,
    val temperature: Int?,
    val temperatureRange: String?,
    val weatherType: Int,
    val begins: String?,
    val humidity: String?,
    val wind: String?,
    val sunrise: String?,
    val sunset: String?,
    val forecastType: String?,
)

private fun weatherRow(cursor: Cursor): WeatherRow = WeatherRow(
    day = cursor.optIntMw("day") ?: 0,
    publishTime = cursor.optStringMw("publish_time"),
    cityName = cursor.optStringMw("city_name"),
    description = cursor.optStringMw("description"),
    temperature = cursor.optIntMw("temperature"),
    temperatureRange = cursor.optStringMw("temperature_range"),
    weatherType = cursor.optIntMw("weather_type") ?: 99,
    begins = cursor.optStringMw("begins"),
    humidity = cursor.optStringMw("humidity"),
    wind = cursor.optStringMw("wind"),
    sunrise = cursor.optStringMw("sunrise"),
    sunset = cursor.optStringMw("sunset"),
    forecastType = cursor.optStringMw("forecast_type"),
)

/**
 * Read-only client for 小米天气（com.miui.weather2）的 WeatherProvider。
 *
 * 该 provider（authorities "weather;weatherinfo"，exported，读无权限门槛）就是
 * MIUI 主题小部件取天气的同一入口。核心 URI（apk 16.0.7.17 逆向）：
 *   - content://weather/selected_city     城市列表（selectedcity 表，position 升序）
 *   - content://weather                   weather 表通用查询（day<=0 实况 / day>0 预报）
 *   - content://weather/hourlyData/<id>   逐小时（city_id,publish_time,date_time,temperature,weather_type,...）
 *
 * 本类刻意无网络客户端、无定位逻辑：数据新鲜度完全由小米天气自身刷新，
 * 消费侧用 ContentObserver 监听 content://weather 变更失效快照。
 */
class MiuiWeatherRepository(context: Context) {
    private val appContext = context.applicationContext

    fun installedPackage(): String? = try {
        @Suppress("DEPRECATION")
        if (appContext.packageManager.getApplicationInfo(PACKAGE_NAME, 0).enabled) PACKAGE_NAME else null
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** 数据变化失效回调（模块进程桥引擎注册；provider 侧无更新广播，ContentObserver 是唯一可靠信号）。 */
    fun observeChanges(onChange: () -> Unit) {
        val resolver = appContext.contentResolver
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val observer = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        resolver.registerContentObserver("content://$AUTHORITY".toUri(), true, observer)
    }

    suspend fun load(preferredLocationId: String? = null): WeatherState = withContext(Dispatchers.IO) {
        var locations = emptyList<WeatherLocation>()
        var selectedId: String? = null
        try {
            if (installedPackage() == null) return@withContext WeatherState(WeatherStatus.NotInstalled)
            // 天气 App 装了但 provider 不在（被精简/停用）同样按未安装降级
            @Suppress("DEPRECATION")
            val provider = appContext.packageManager.resolveContentProvider(AUTHORITY, 0)
            if (provider == null || !provider.enabled) {
                return@withContext WeatherState(WeatherStatus.NotInstalled)
            }

            locations = query(uri("selected_city"), sortOrder = "position ASC") { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(location(cursor))
                }
            } ?: throw IOException("Miui weather locations are unavailable")
            if (locations.isEmpty()) return@withContext WeatherState(WeatherStatus.NoLocations)

            // weatherLocationId（设置页首选城市）= posID；被删后回退列表首位（原版语义）
            val selected = locations.firstOrNull { it.id == preferredLocationId } ?: locations.first()
            selectedId = selected.id

            val now = ZonedDateTime.now(ZoneId.systemDefault())
            val rows: List<WeatherRow> = query(
                uri("weather"),
                projection = WEATHER_COLUMNS,
                selection = "city_id = ?",
                selectionArgs = arrayOf(selected.id),
                sortOrder = "day ASC",
            ) { cursor ->
                val out = ArrayList<WeatherRow>()
                while (cursor.moveToNext()) out.add(weatherRow(cursor))
                out
            } ?: throw IOException("Miui weather rows are unavailable")
            if (rows.isEmpty()) {
                return@withContext WeatherState(
                    status = WeatherStatus.NoWeather,
                    locations = locations,
                    selectedLocationId = selectedId,
                )
            }
            // 实况行定位：经典约定 day<=0；16.x 实测（2026-10-13 装机）weather 表
            // 预报行从 day=2 起（系统部件日志 day:2..7），实况行 = day=1。取最小
            // day 行为实况、其余为预报（day 语义变化时此规则仍然成立）。
            val sorted = rows.sortedBy { it.day }
            val live = sorted.firstOrNull()
            val forecast = sorted.drop(1).take(5)
            android.util.Log.d("HomeMode4Miui", "MiuiWeather rows: " + sorted.joinToString("; ") {
                "day=${it.day} t=${it.temperature} range=${it.temperatureRange} type=${it.weatherType} " +
                    "ft=${it.forecastType} desc=${it.description} pub=${it.publishTime}"
            })
            val currentWeather = live?.let { current(it, now) }
            val hourly = loadHourly(selected.id, live?.publishTime ?: rows.firstOrNull()?.publishTime, now)
            WeatherState(
                status = if (currentWeather == null && forecast.isEmpty()) WeatherStatus.NoWeather else WeatherStatus.Ready,
                snapshot = WeatherSnapshot(
                    location = selected,
                    current = currentWeather,
                    hourly = hourly,
                    daily = forecast.map { day(it, now) },
                    updatedAt = MiuiWeatherParser.parseTime(live?.publishTime ?: rows.firstOrNull()?.publishTime),
                    attribution = ATTRIBUTION,
                ),
                locations = locations,
                selectedLocationId = selectedId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            WeatherState(WeatherStatus.PermissionRequired)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            WeatherState(WeatherStatus.Error, locations = locations, selectedLocationId = selectedId)
        }
    }

    private fun location(cursor: Cursor): WeatherLocation {
        val name = cursor.optStringMw("name")?.takeIf { it.isNotBlank() }
            ?: cursor.optStringMw("belongings")?.takeIf { it.isNotBlank() }
            ?: "—"
        return WeatherLocation(
            id = cursor.optStringMw("posID").orEmpty(),
            name = name,
            // selectedcity 表无时区列；provider 城市即本机时区语义
            timeZone = ZoneId.systemDefault(),
            // flag：小米天气对"当前定位城市"的标记位
            isCurrentPosition = cursor.optIntMw("flag") == 1,
        )
    }

    private fun current(row: WeatherRow, now: ZonedDateTime): WeatherCurrent? {
        val temperature = row.temperature?.let { WeatherTemperature(it.toDouble(), UNIT) }
        val condition = MiuiWeatherParser.condition(row.weatherType)
        // 实况行温度/类型/描述全空视为脏数据
        if (temperature == null && condition == WeatherCondition.Unknown && row.description.isNullOrBlank()) return null
        return WeatherCurrent(
            temperature = temperature,
            condition = condition,
            isDaylight = MiuiWeatherParser.isDaylight(
                System.currentTimeMillis(),
                MiuiWeatherParser.parseClock(row.sunrise, now),
                MiuiWeatherParser.parseClock(row.sunset, now),
            ),
            description = row.description?.takeIf { it.isNotBlank() },
        )
    }

    private fun day(row: WeatherRow, now: ZonedDateTime): WeatherDay {
        val range = MiuiWeatherParser.parseRange(row.temperatureRange)
        val date = MiuiWeatherParser.parseTime(row.begins, now.zone)?.atZone(now.zone)?.toLocalDate()
            ?: now.toLocalDate().plusDays((row.day - 1).coerceAtLeast(0).toLong())
        return WeatherDay(
            date = date,
            high = range?.second?.let { WeatherTemperature(it, UNIT) },
            low = range?.first?.let { WeatherTemperature(it, UNIT) },
            condition = MiuiWeatherParser.condition(row.weatherType),
            description = row.description?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 逐小时（2026-10-13 装机实测结构）：25 行 = 未来 25 个整点（从下一个整点起），
     * date_time/sunrise/sunset 都是【发布日零点起的毫秒偏移】（非文本时间）：
     *   date_time=86400000 → 次日 00:00；sunrise=111840000 mod 24h = 7:04（实测吻合）。
     * 绝对时刻 = publish_time 所在日的本地零点 + date_time。minute_rain JSON 不消费。
     */
    private suspend fun loadHourly(cityId: String, publishTime: String?, now: ZonedDateTime): List<WeatherHour> =
        try {
            val base = MiuiWeatherParser.parseTime(publishTime, now.zone)
                ?.atZone(now.zone)?.toLocalDate()?.atStartOfDay(now.zone)?.toInstant()
                ?: return emptyList()
            query(uri("hourlyData").buildUpon().appendPath(cityId).build()) { cursor ->
                android.util.Log.d("HomeMode4Miui", "MiuiWeather hourly cols=" +
                    cursor.columnNames.joinToString(",") + " count=${cursor.count}")
                val out = ArrayList<WeatherHour>()
                while (cursor.moveToNext()) {
                    val offsetMs = cursor.optStringMw("date_time")?.toLongOrNull() ?: continue
                    val at = base.plusMillis(offsetMs)
                    // 当日日出/日落（零点偏移 mod 24h → 当日时刻）；缺省视为全天白昼
                    val sunUp = cursor.optStringMw("sunrise")?.toLongOrNull()
                        ?.let { Math.floorMod(it, 86_400_000L) }
                    val sunDown = cursor.optStringMw("sunset")?.toLongOrNull()
                        ?.let { Math.floorMod(it, 86_400_000L) }
                    val timeOfDayMs = at.atZone(now.zone).toLocalTime().toSecondOfDay() * 1000L
                    val daylight = if (sunUp == null || sunDown == null) true
                    else timeOfDayMs in sunUp until sunDown
                    out.add(
                        WeatherHour(
                            at = at,
                            temperature = cursor.optIntMw("temperature")
                                ?.let { WeatherTemperature(it.toDouble(), UNIT) },
                            condition = MiuiWeatherParser.condition(cursor.optIntMw("weather_type") ?: 99),
                            isDaylight = daylight,
                            description = null,
                        )
                    )
                }
                android.util.Log.d("HomeMode4Miui", "MiuiWeather hourly parsed=${out.size}")
                out
            } ?: emptyList<WeatherHour>().also {
                android.util.Log.d("HomeMode4Miui", "MiuiWeather hourly query returned null cursor")
            }
        } catch (error: Exception) {
            android.util.Log.d("HomeMode4Miui", "MiuiWeather hourly load failed: $error")
            emptyList()
        }

    /** IO 上执行且可随协程取消；每条 cursor 都关闭。 */
    private suspend fun <T> query(
        uri: Uri,
        projection: Array<String>? = null,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: String? = null,
        read: (Cursor) -> T,
    ): T? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        continuation.invokeOnCancellation { cancellation.cancel() }
        try {
            val value = appContext.contentResolver
                .query(uri, projection, selection, selectionArgs, sortOrder, cancellation)?.use(read)
            continuation.resume(value)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }

    private fun uri(path: String): Uri = "content://$AUTHORITY/$path".toUri()

    companion object {
        const val PACKAGE_NAME = "com.miui.weather2"
        const val AUTHORITY = "weather"

        /** MIUI provider 温度恒为摄氏整数（weatherappconfig.unit 仅为展示单位）。 */
        const val UNIT = "c"
        const val ATTRIBUTION = "小米天气"
    }
}

/** weather 表投影（apk 16.0.7.17 WeatherProvider <init> 投影数组 h 的子集）。 */
private val WEATHER_COLUMNS = arrayOf(
    "day", "publish_time", "city_id", "city_name", "description", "temperature",
    "temperature_range", "weather_type", "begins", "ends", "humidity",
    "feel_temperature", "wind", "wind_direction", "wind_power",
    "sunrise", "sunset", "aqilevel", "forecast_type",
)
