package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

const val AgendaWidgetKey = "com.galaxyrio.gracelauncher/agenda-widget"
const val AgendaWidgetDraftKey = "com.galaxyrio.gracelauncher/agenda-widget-draft"

enum class AgendaTitle { Time, Quote, Text }
enum class AgendaCreateAction { Event, Task }
enum class AgendaHeaderField { Date, Countdown, Weather }
enum class AgendaDayWeatherField {
    Icon, Temperature, Description, FeelsLike, Humidity, Wind, AirQuality, UV, Location, HighLow, UpdatedAt;

    val supportsLabel: Boolean get() = this !in setOf(Icon, Temperature, Description)
}
enum class AgendaHourWeatherField {
    Icon, Chart, Temperature, FeelsLike, Description, PrecipitationProbability, Humidity, Wind, WindDirection,
    AirQuality, UV, Gusts, Precipitation, Visibility, CloudCover, Pressure, DewPoint,
    RainProbability, SnowProbability, IceProbability, ThunderstormProbability, Rain, Snow, Ice, Thunderstorm;

    val supportsChart: Boolean get() = this !in setOf(Icon, Chart, Description)
}

enum class HourWeatherChartType { Bar, Line }

data class HourWeatherFieldStyle(
    val chartType: HourWeatherChartType = HourWeatherChartType.Line,
    val wavy: Boolean = false,
    val barWidth: Int = 18,
    val alwaysShowText: Boolean = false,
    val alwaysShowChart: Boolean = false,
) {
    fun normalized() = copy(barWidth = barWidth.coerceIn(BarWidthRange))

    companion object {
        val BarWidthRange = 4..32

        fun defaults(field: AgendaHourWeatherField) = HourWeatherFieldStyle(
            chartType = if (field == AgendaHourWeatherField.AirQuality) HourWeatherChartType.Bar else HourWeatherChartType.Line,
            wavy = field == AgendaHourWeatherField.Humidity,
            alwaysShowText = field in setOf(AgendaHourWeatherField.Icon, AgendaHourWeatherField.Temperature),
        )
    }
}

data class AgendaAppearance(
    val title: AgendaTitle = AgendaTitle.Text,
    val titleText: String = "Your Agenda",
    val showDailyContent: Boolean = true,
    val headerOrder: List<AgendaHeaderField> = AgendaHeaderField.entries,
    val headerSelected: Set<AgendaHeaderField> = AgendaHeaderField.entries.toSet(),
    val headerRightAligned: Set<AgendaHeaderField> = setOf(AgendaHeaderField.Weather),
    val dayWeatherOrder: List<AgendaDayWeatherField> = AgendaDayWeatherField.entries,
    val dayWeatherSelected: Set<AgendaDayWeatherField> = setOf(AgendaDayWeatherField.Icon, AgendaDayWeatherField.HighLow),
    val dayWeatherLabeledFields: Set<AgendaDayWeatherField> = setOf(
        AgendaDayWeatherField.FeelsLike, AgendaDayWeatherField.AirQuality, AgendaDayWeatherField.UV, AgendaDayWeatherField.UpdatedAt,
    ),
    val calendarContent: ClockAgenda = defaultCalendarContent(),
    val calendarPrimaryFields: Set<ClockAgendaField> = setOf(ClockAgendaField.Title),
    val taskContent: ClockTasks = defaultTaskContent(),
    val taskPrimaryFields: Set<ClockTaskField> = setOf(ClockTaskField.Title),
    val tasksFirst: Boolean = true,
    val showHourlyWeather: Boolean = true,
    val hourWeatherOrder: List<AgendaHourWeatherField> = AgendaHourWeatherField.entries,
    val hourWeatherSelected: Set<AgendaHourWeatherField> = setOf(
        AgendaHourWeatherField.Icon, AgendaHourWeatherField.Temperature,
    ),
    val hourWeatherChartFields: Set<AgendaHourWeatherField> = emptySet(),
    val hourWeatherStyles: Map<AgendaHourWeatherField, HourWeatherFieldStyle> = emptyMap(),
    val hourWeatherChartsExpanded: Boolean = true,
    val hourWeatherChartHeight: Int = 120,
    val hourWeatherColumnWidth: Int = 80,
    val showUndatedTasks: Boolean = true,
    val lastCreateAction: AgendaCreateAction = AgendaCreateAction.Event,
) {
    fun hourWeatherStyle(field: AgendaHourWeatherField) = hourWeatherStyles[field] ?: HourWeatherFieldStyle.defaults(field)

    fun normalized() = copy(
        titleText = titleText.take(500),
        headerOrder = (headerOrder + AgendaHeaderField.entries).distinct(),
        dayWeatherOrder = (dayWeatherOrder + AgendaDayWeatherField.entries).distinct(),
        dayWeatherLabeledFields = dayWeatherLabeledFields.filter { it.supportsLabel }.toSet(),
        hourWeatherOrder = normalizedHourWeatherOrder(hourWeatherOrder),
        hourWeatherSelected = hourWeatherSelected - AgendaHourWeatherField.Chart,
        hourWeatherChartFields = hourWeatherChartFields.filter { it.supportsChart }.toSet(),
        hourWeatherStyles = hourWeatherStyles.filterKeys { it != AgendaHourWeatherField.Chart }
            .mapValues { it.value.normalized() }.filter { (field, style) -> style != HourWeatherFieldStyle.defaults(field) },
        hourWeatherChartHeight = hourWeatherChartHeight.coerceIn(60, 240),
        hourWeatherColumnWidth = hourWeatherColumnWidth.coerceIn(HourWeatherColumnWidthRange),
        calendarContent = calendarContent.normalized(),
        taskContent = taskContent.normalized(),
    )

    fun encode(): String = normalized().let { value -> JSONObject().apply {
        put("title", value.title.name)
        put("titleText", value.titleText)
        put("showDailyContent", value.showDailyContent)
        put("headerOrder", JSONArray(value.headerOrder.map { it.name }))
        put("headerSelected", JSONArray(value.headerSelected.map { it.name }))
        put("headerRightAligned", JSONArray(value.headerRightAligned.map { it.name }))
        put("dayWeatherOrder", JSONArray(value.dayWeatherOrder.map { it.name }))
        put("dayWeatherSelected", JSONArray(value.dayWeatherSelected.map { it.name }))
        put("dayWeatherLabeledFields", JSONArray(value.dayWeatherLabeledFields.map { it.name }))
        put("calendarContent", value.calendarContent.json())
        put("calendarPrimaryFields", JSONArray(value.calendarPrimaryFields.map { it.name }))
        put("taskContent", value.taskContent.json())
        put("taskPrimaryFields", JSONArray(value.taskPrimaryFields.map { it.name }))
        put("tasksFirst", value.tasksFirst)
        put("showHourlyWeather", value.showHourlyWeather)
        put("hourWeatherOrder", JSONArray(value.hourWeatherOrder.map { it.name }))
        put("hourWeatherSelected", JSONArray(value.hourWeatherSelected.map { it.name }))
        put("hourWeatherChartFields", JSONArray(value.hourWeatherChartFields.map { it.name }))
        put("hourWeatherStyles", JSONObject().apply {
            value.hourWeatherStyles.forEach { (field, style) -> put(field.name, JSONObject().apply {
                put("chartType", style.chartType.name)
                put("wavy", style.wavy)
                put("barWidth", style.barWidth)
                put("alwaysShowText", style.alwaysShowText)
                put("alwaysShowChart", style.alwaysShowChart)
            }) }
        })
        put("hourWeatherChartsExpanded", value.hourWeatherChartsExpanded)
        put("hourWeatherChartHeight", value.hourWeatherChartHeight)
        put("hourWeatherColumnWidth", value.hourWeatherColumnWidth)
        put("showUndatedTasks", value.showUndatedTasks)
        put("lastCreateAction", value.lastCreateAction.name)
    }.toString() }

    companion object {
        val HourWeatherColumnWidthRange = 40..160

        private fun normalizedHourWeatherOrder(saved: List<AgendaHourWeatherField>): List<AgendaHourWeatherField> {
            // Move unchanged legacy defaults to the useful-first order without overwriting custom ordering.
            val legacy = listOf("Icon", "Temperature", "Description", "FeelsLike", "Humidity", "Wind", "Gusts", "AirQuality", "UV",
                "PrecipitationProbability", "RainProbability", "SnowProbability", "IceProbability", "ThunderstormProbability",
                "Precipitation", "Rain", "Snow", "Ice", "Thunderstorm", "DewPoint", "Pressure", "CloudCover", "Visibility")
            if (saved.map { it.name } == legacy || saved.map { it.name } == legacy.take(3)) return AgendaHourWeatherField.entries
            val order = saved.distinct().toMutableList()
            if (AgendaHourWeatherField.Chart !in order) {
                val anchor = listOf(order.indexOf(AgendaHourWeatherField.Icon), order.indexOf(AgendaHourWeatherField.Temperature))
                    .filter { it >= 0 }.minOrNull() ?: -1
                order.add(anchor + 1, AgendaHourWeatherField.Chart)
            }
            if (AgendaHourWeatherField.WindDirection !in order && AgendaHourWeatherField.Wind in order) {
                order.add(order.indexOf(AgendaHourWeatherField.Wind) + 1, AgendaHourWeatherField.WindDirection)
            }
            return (order + AgendaHourWeatherField.entries).distinct()
        }

        fun defaultCalendarContent() = ClockAgenda(
            selected = setOf(ClockAgendaField.Title, ClockAgendaField.Time, ClockAgendaField.Countdown),
            order = listOf(ClockAgendaField.Title, ClockAgendaField.Time, ClockAgendaField.Countdown) +
                ClockAgendaField.entries.filterNot { it in setOf(ClockAgendaField.Title, ClockAgendaField.Time, ClockAgendaField.Countdown) },
            typography = ClockRowTypography(size = 14, fontShadow = 0),
        )

        fun defaultTaskContent() = ClockTasks(
            selected = setOf(ClockTaskField.Title, ClockTaskField.DueTime, ClockTaskField.Countdown, ClockTaskField.List),
            order = listOf(ClockTaskField.Title, ClockTaskField.DueTime, ClockTaskField.Countdown, ClockTaskField.List) +
                ClockTaskField.entries.filterNot { it in setOf(ClockTaskField.Title, ClockTaskField.DueTime, ClockTaskField.Countdown, ClockTaskField.List) },
            typography = ClockRowTypography(size = 14, fontShadow = 0),
        )

        fun decode(value: String?): AgendaAppearance = runCatching {
            val data = JSONObject(value ?: "{}")
            val defaults = AgendaAppearance()
            fun <T : Enum<T>> fields(key: String, values: List<T>): List<T>? = data.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { index -> values.firstOrNull { it.name == array.optString(index) } }.distinct()
            }
            AgendaAppearance(
                title = AgendaTitle.entries.firstOrNull { it.name == data.optString("title") } ?: defaults.title,
                titleText = data.optString("titleText", defaults.titleText),
                showDailyContent = data.optBoolean("showDailyContent", defaults.showDailyContent),
                headerOrder = fields("headerOrder", AgendaHeaderField.entries) ?: defaults.headerOrder,
                headerSelected = fields("headerSelected", AgendaHeaderField.entries)?.toSet() ?: defaults.headerSelected,
                headerRightAligned = fields("headerRightAligned", AgendaHeaderField.entries)?.toSet() ?: defaults.headerRightAligned,
                dayWeatherOrder = fields("dayWeatherOrder", AgendaDayWeatherField.entries) ?: defaults.dayWeatherOrder,
                dayWeatherSelected = fields("dayWeatherSelected", AgendaDayWeatherField.entries)?.toSet() ?: defaults.dayWeatherSelected,
                dayWeatherLabeledFields = fields("dayWeatherLabeledFields", AgendaDayWeatherField.entries)?.toSet()
                    ?: defaults.dayWeatherLabeledFields,
                calendarContent = data.optJSONObject("calendarContent")?.let { ClockAgenda.decode(it) } ?: defaults.calendarContent,
                calendarPrimaryFields = fields("calendarPrimaryFields", ClockAgendaField.entries)?.toSet() ?: defaults.calendarPrimaryFields,
                taskContent = data.optJSONObject("taskContent")?.let { ClockTasks.decode(it) } ?: defaults.taskContent,
                taskPrimaryFields = fields("taskPrimaryFields", ClockTaskField.entries)?.toSet() ?: defaults.taskPrimaryFields,
                tasksFirst = data.optBoolean("tasksFirst", defaults.tasksFirst),
                showHourlyWeather = data.optBoolean("showHourlyWeather", defaults.showHourlyWeather),
                hourWeatherOrder = fields("hourWeatherOrder", AgendaHourWeatherField.entries) ?: defaults.hourWeatherOrder,
                hourWeatherSelected = fields("hourWeatherSelected", AgendaHourWeatherField.entries)?.toSet() ?: defaults.hourWeatherSelected,
                hourWeatherChartFields = fields("hourWeatherChartFields", AgendaHourWeatherField.entries)?.toSet() ?: defaults.hourWeatherChartFields,
                hourWeatherStyles = AgendaHourWeatherField.entries.mapNotNull { field ->
                    data.optJSONObject("hourWeatherStyles")?.optJSONObject(field.name)?.let { style ->
                        val fallback = HourWeatherFieldStyle.defaults(field)
                        field to HourWeatherFieldStyle(
                            chartType = HourWeatherChartType.entries.firstOrNull { it.name == style.optString("chartType") }
                                ?: fallback.chartType,
                            wavy = style.optBoolean("wavy", fallback.wavy),
                            barWidth = style.optInt("barWidth", fallback.barWidth),
                            alwaysShowText = style.optBoolean("alwaysShowText", style.optBoolean("alwaysShow", fallback.alwaysShowText)),
                            alwaysShowChart = style.optBoolean("alwaysShowChart", false),
                        )
                    }
                }.toMap(),
                hourWeatherChartsExpanded = data.optBoolean("hourWeatherChartsExpanded", defaults.hourWeatherChartsExpanded),
                hourWeatherChartHeight = data.optInt("hourWeatherChartHeight", defaults.hourWeatherChartHeight),
                hourWeatherColumnWidth = data.optInt("hourWeatherColumnWidth", defaults.hourWeatherColumnWidth),
                showUndatedTasks = data.optBoolean("showUndatedTasks", defaults.showUndatedTasks),
                lastCreateAction = AgendaCreateAction.entries.firstOrNull { it.name == data.optString("lastCreateAction") }
                    ?: defaults.lastCreateAction,
            ).normalized()
        }.getOrDefault(AgendaAppearance())
    }
}
