package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

enum class ClockElement { Clock, Weekday, Date, Battery, Weather }
enum class ClockRegion { Primary, Secondary, Tertiary, Agenda, Task }
enum class ClockAlignment { Left, Center, Right }

data class ClockRowTypography(
    val size: Int = 16,
    val weight: Int = 400,
    val fontShadow: Int = 0,
    val fontId: String? = null,
) {
    fun normalized() = copy(
        size = size.coerceIn(8, 72),
        weight = weight.coerceIn(100, 900),
        fontShadow = fontShadow.coerceIn(0, 24),
        fontId = fontId?.takeIf(String::isNotBlank),
    )
}

/** Null is the movable divider; every element keeps its place even while unselected. */
data class ClockRegionRow(
    val order: List<ClockElement?> = ClockElement.entries + listOf(null),
    val selected: Set<ClockElement> = emptySet(),
    val typography: ClockRowTypography = ClockRowTypography(),
    val showSeconds: Boolean = false,
    val useLatinLetters: Boolean = false,
    val fixedClockWidth: Boolean = true,
    val centered: Boolean = false,
) {
    val leftItems: List<ClockElement> get() = completeOrder().takeWhile { it != null }.filterNotNull().filter { it in selected }
    val rightItems: List<ClockElement> get() = completeOrder().dropWhile { it != null }.filterNotNull().filter { it in selected }

    private fun completeOrder(): List<ClockElement?> = (order + ClockElement.entries + listOf(null)).distinct()

    fun normalized() = copy(order = completeOrder(), typography = typography.normalized())

    internal fun json(): JSONObject = normalized().let { row ->
        JSONObject().apply {
            put("order", JSONArray().apply { row.order.forEach { put(it?.name ?: JSONObject.NULL) } })
            put("selected", JSONArray().apply { ClockElement.entries.filter { it in row.selected }.forEach { put(it.name) } })
            put("typography", JSONObject().apply {
                put("size", row.typography.size)
                put("weight", row.typography.weight)
                put("fontShadow", row.typography.fontShadow)
                put("fontId", row.typography.fontId ?: JSONObject.NULL)
            })
            put("showSeconds", row.showSeconds)
            put("useLatinLetters", row.useLatinLetters)
            put("fixedClockWidth", row.fixedClockWidth)
            put("centered", row.centered)
        }
    }

    companion object {
        internal fun decode(json: JSONObject?, defaults: ClockRegionRow): ClockRegionRow {
            if (json == null) return defaults
            val order = json.optJSONArray("order")?.let { array ->
                buildList<ClockElement?> {
                    for (index in 0 until array.length()) {
                        if (array.isNull(index)) add(null)
                        else ClockElement.entries.firstOrNull { it.name == array.optString(index) }?.let(::add)
                    }
                }
            } ?: defaults.order
            val selected = json.optJSONArray("selected")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    ClockElement.entries.firstOrNull { it.name == array.optString(index) }
                }.toSet()
            } ?: defaults.selected
            val typography = json.optJSONObject("typography")
            return ClockRegionRow(order, selected, ClockRowTypography(
                size = typography?.optInt("size", defaults.typography.size) ?: defaults.typography.size,
                weight = typography?.optInt("weight", defaults.typography.weight) ?: defaults.typography.weight,
                fontShadow = typography?.optInt("fontShadow", defaults.typography.fontShadow) ?: defaults.typography.fontShadow,
                fontId = if (typography?.has("fontId") == true)
                    typography.optString("fontId").takeIf { it.isNotBlank() && it != "null" } else defaults.typography.fontId,
            ),
                showSeconds = json.optBoolean("showSeconds", defaults.showSeconds),
                useLatinLetters = json.optBoolean("useLatinLetters", defaults.useLatinLetters),
                fixedClockWidth = json.optBoolean("fixedClockWidth", defaults.fixedClockWidth),
                centered = json.optBoolean("centered", defaults.centered),
            ).normalized()
        }
    }
}

data class ClockRegions(
    val primary: ClockElement? = ClockElement.Clock,
    val primaryAlignment: ClockAlignment = ClockAlignment.Left,
    val primaryFaces: Map<ClockElement, ClockFaceStyle> = emptyMap(),
    val stackedClock: Boolean = false,
    val secondary: ClockRegionRow = ClockRegionRow(selected = setOf(
        ClockElement.Weekday, ClockElement.Date, ClockElement.Battery, ClockElement.Weather,
    )),
    val tertiary: ClockRegionRow = ClockRegionRow(),
    val regionOrder: List<ClockRegion> = ClockRegion.entries,
    val rotateAbovePrimary: Boolean = false,
    val rotatePrimary: Boolean = false,
    val rotateAll: Boolean = false,
    val regionSpacing: Int = 2,
    val agenda: ClockAgenda = ClockAgenda(),
    val tasks: ClockTasks = ClockTasks(),
    val weather: ClockWeather = ClockWeather(),
    val battery: ClockBattery = ClockBattery(),
    val bottomPadding: Int = 4,
) {
    fun face(element: ClockElement): ClockFaceStyle = (primaryFaces[element] ?: defaultFace(element)).normalized()

    fun withPrimaryFace(face: ClockFaceStyle): ClockRegions = primary?.let {
        copy(primaryFaces = primaryFaces + (it to face.normalized()))
    } ?: this

    /** Invalid duplicate selections favor Primary, then Secondary, then Tertiary. */
    fun normalized(): ClockRegions {
        val primaryItems = setOfNotNull(primary)
        val secondary = secondary.normalized().let { it.copy(selected = it.selected - primaryItems) }
        val tertiary = tertiary.normalized().let { it.copy(selected = it.selected - primaryItems - secondary.selected) }
        val completedOrder = (regionOrder + ClockRegion.entries.filter { it != ClockRegion.Task }).distinct().toMutableList()
        // Existing saved orders gain Tasks immediately below Agenda, wherever Agenda was moved.
        if (ClockRegion.Task !in completedOrder) completedOrder.add(completedOrder.indexOf(ClockRegion.Agenda) + 1, ClockRegion.Task)
        return copy(
            primaryFaces = primaryFaces.mapValues { it.value.normalized() },
            secondary = secondary,
            tertiary = tertiary,
            regionOrder = completedOrder,
            regionSpacing = regionSpacing.coerceIn(0, 64),
            bottomPadding = bottomPadding.coerceIn(0, 64),
            agenda = agenda.normalized(),
            tasks = tasks.normalized(),
            weather = weather.normalized(),
            battery = battery.normalized(),
        )
    }

    /** Primary keeps its selection until cleared; the other two regions can exchange elements. */
    fun select(region: ClockRegion, element: ClockElement, selected: Boolean): ClockRegions {
        val current = normalized()
        if (region == ClockRegion.Agenda || region == ClockRegion.Task) return current
        if (!selected) return when (region) {
            ClockRegion.Primary -> if (current.primary == element) current.copy(primary = null) else current
            ClockRegion.Secondary -> current.copy(secondary = current.secondary.copy(selected = current.secondary.selected - element))
            ClockRegion.Tertiary -> current.copy(tertiary = current.tertiary.copy(selected = current.tertiary.selected - element))
            ClockRegion.Agenda, ClockRegion.Task -> current
        }
        if (region != ClockRegion.Primary && element == current.primary) return current
        return current.copy(
            primary = if (region == ClockRegion.Primary) element else current.primary.takeUnless { it == element },
            secondary = current.secondary.copy(selected = (current.secondary.selected - element).let {
                if (region == ClockRegion.Secondary) it + element else it
            }),
            tertiary = current.tertiary.copy(selected = (current.tertiary.selected - element).let {
                if (region == ClockRegion.Tertiary) it + element else it
            }),
        )
    }

    fun withoutFonts(ids: Set<String>): ClockRegions {
        fun ClockRowTypography.clean() = if (fontId in ids) copy(fontId = null) else this
        return copy(
            primaryFaces = primaryFaces.mapValues { (_, face) ->
                if (face.fontId in ids) face.copy(fontId = null) else face
            },
            secondary = secondary.copy(typography = secondary.typography.clean()),
            tertiary = tertiary.copy(typography = tertiary.typography.clean()),
            agenda = agenda.copy(typography = agenda.typography.clean()),
            tasks = tasks.copy(typography = tasks.typography.clean()),
        )
    }

    internal fun json(): JSONObject = normalized().let { regions ->
        JSONObject().apply {
            put("primary", regions.primary?.name ?: JSONObject.NULL)
            put("primaryAlignment", regions.primaryAlignment.name)
            put("stackedClock", regions.stackedClock)
            put("rotateAbovePrimary", regions.rotateAbovePrimary)
            put("rotatePrimary", regions.rotatePrimary)
            put("rotateAll", regions.rotateAll)
            put("regionSpacing", regions.regionSpacing)
            put("bottomPadding", regions.bottomPadding)
            put("agenda", regions.agenda.json())
            put("tasks", regions.tasks.json())
            put("weather", regions.weather.json())
            put("battery", regions.battery.json())
            put("regionOrder", JSONArray().apply { regions.regionOrder.forEach { put(it.name) } })
            put("primaryFaces", JSONObject().apply {
                regions.primaryFaces.forEach { (element, face) ->
                    put(element.name, JSONObject().apply {
                        put("fontId", face.fontId ?: JSONObject.NULL)
                        put("weight", face.weight)
                        put("size", face.size)
                        put("letterSpacing", face.letterSpacing)
                        put("hourMinuteSpacing", face.hourMinuteSpacing)
                        put("fontShadow", face.fontShadow)
                        put("showColon", face.showColon)
                        put("separateDigits", face.separateDigits)
                        put("showLeadingZero", face.showLeadingZero)
                        put("showSeconds", face.showSeconds)
                        put("equalWidthDigits", face.equalWidthDigits)
                        put("centerDigits", face.centerDigits)
                        put("useLatinLetters", face.useLatinLetters)
                    })
                }
            })
            put("secondary", regions.secondary.json())
            put("tertiary", regions.tertiary.json())
        }
    }

    companion object {
        fun defaultFace(element: ClockElement): ClockFaceStyle = when (element) {
            ClockElement.Clock -> ClockStyle.defaults(ClockLayout.SingleLine)
            ClockElement.Weekday -> ClockStyle.defaults(ClockLayout.Week)
            ClockElement.Date -> ClockFaceStyle(weight = 400, letterSpacing = 0, useLatinLetters = true)
            else -> ClockFaceStyle(weight = 400, letterSpacing = 0)
        }

        internal fun fromLegacy(style: ClockStyle, calendarAboveClock: Boolean = false): ClockRegions {
            val source = style.applyPresetToBase()
            val visibility = source.effectiveVisibility
            val visible = buildSet {
                if (visibility.showClock) add(ClockElement.Clock)
                if (visibility.showWeekday) add(ClockElement.Weekday)
                if (visibility.showDate) add(ClockElement.Date)
                if (visibility.showBattery) add(ClockElement.Battery)
                if (visibility.showWeather) add(ClockElement.Weather)
            }
            val largeElement = if (source.layout.week) ClockElement.Weekday else ClockElement.Clock
            val primary = largeElement.takeIf { it in visible }
            // Week's small clock used the active Week face's formatting, while its
            // future large-clock typography can retain the saved single-line slot.
            val clockFace = if (source.layout.week) source.singleLine.normalized().copy(
                showColon = source.face.showColon,
                showLeadingZero = source.face.showLeadingZero,
            ) else source.face
            val weekdayFace = if (source.layout.week) source.face else source.copy(layout = ClockLayout.Week).face
            return ClockRegions(
                primary = primary,
                primaryFaces = mapOf(ClockElement.Clock to clockFace, ClockElement.Weekday to weekdayFace),
                stackedClock = source.layout.stacked,
                secondary = ClockRegionRow(selected = visible - setOfNotNull(primary)),
                regionOrder = if (calendarAboveClock) listOf(ClockRegion.Secondary, ClockRegion.Primary, ClockRegion.Tertiary, ClockRegion.Agenda, ClockRegion.Task)
                    else ClockRegion.entries,
            )
        }

        /** Complete the existing designs without borrowing content choices from the editor draft. */
        internal fun fromPreset(style: ClockStyle): ClockRegions = fromLegacy(style).copy(
            regionOrder = listOf(ClockRegion.Primary, ClockRegion.Secondary, ClockRegion.Tertiary, ClockRegion.Agenda, ClockRegion.Task),
            regionSpacing = 2,
            bottomPadding = 4,
            tasks = ClockTasks(),
            agenda = ClockAgenda(
                order = listOf(ClockAgendaField.Title, ClockAgendaField.Countdown, ClockAgendaField.Time,
                    ClockAgendaField.Date, ClockAgendaField.Duration, ClockAgendaField.Location,
                    ClockAgendaField.Color, ClockAgendaField.Calendar),
                selected = setOf(ClockAgendaField.Title, ClockAgendaField.Countdown),
                typography = ClockRowTypography(size = 16, weight = 400, fontShadow = 0),
                eventCount = 1,
            ),
            weather = ClockWeather(
                order = listOf(ClockWeatherField.Icon, ClockWeatherField.Temperature, ClockWeatherField.Description,
                    ClockWeatherField.FeelsLike, ClockWeatherField.Humidity, ClockWeatherField.Wind,
                    ClockWeatherField.AirQuality, ClockWeatherField.UV, ClockWeatherField.Location,
                    ClockWeatherField.HighLow, ClockWeatherField.UpdatedAt),
                selected = setOf(ClockWeatherField.Icon, ClockWeatherField.Temperature),
            ),
        ).normalized()

        internal fun decode(json: JSONObject?): ClockRegions? {
            if (json == null) return null
            val defaults = ClockRegions()
            val primary = if (!json.has("primary")) defaults.primary else
                ClockElement.entries.firstOrNull { it.name == json.optString("primary") }
            val faces = json.optJSONObject("primaryFaces")
            val legacyRotation = json.optString("rotation")
            return ClockRegions(
                primary = primary,
                primaryAlignment = ClockAlignment.entries.firstOrNull { it.name == json.optString("primaryAlignment") }
                    ?: ClockAlignment.Left,
                stackedClock = json.optBoolean("stackedClock", false),
                rotateAbovePrimary = json.optBoolean("rotateAbovePrimary", false),
                rotatePrimary = if (json.has("rotatePrimary")) json.optBoolean("rotatePrimary", false)
                    else legacyRotation == "Primary",
                rotateAll = if (json.has("rotateAll")) json.optBoolean("rotateAll", false)
                    else legacyRotation == "All",
                regionSpacing = json.optInt("regionSpacing", defaults.regionSpacing),
                bottomPadding = json.optInt("bottomPadding", defaults.bottomPadding),
                agenda = ClockAgenda.decode(json.optJSONObject("agenda")),
                tasks = ClockTasks.decode(json.optJSONObject("tasks")),
                weather = ClockWeather.decode(json.optJSONObject("weather")),
                battery = ClockBattery.decode(json.optJSONObject("battery")),
                regionOrder = json.optJSONArray("regionOrder")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        ClockRegion.entries.firstOrNull { it.name == array.optString(index) }
                    }
                } ?: defaults.regionOrder,
                primaryFaces = ClockElement.entries.mapNotNull { element ->
                    val data = faces?.optJSONObject(element.name) ?: return@mapNotNull null
                    val face = defaultFace(element)
                    element to ClockFaceStyle(
                        fontId = data.optString("fontId").takeIf { it.isNotBlank() && it != "null" },
                        weight = data.optInt("weight", face.weight),
                        size = data.optInt("size", face.size),
                        letterSpacing = data.optInt("letterSpacing", face.letterSpacing),
                        hourMinuteSpacing = data.optInt("hourMinuteSpacing", face.hourMinuteSpacing),
                        fontShadow = data.optInt("fontShadow", face.fontShadow),
                        showColon = data.optBoolean("showColon", face.showColon),
                        separateDigits = data.optBoolean("separateDigits", face.separateDigits),
                        showLeadingZero = data.optBoolean("showLeadingZero", face.showLeadingZero),
                        showSeconds = data.optBoolean("showSeconds", face.showSeconds),
                        equalWidthDigits = data.optBoolean("equalWidthDigits", face.equalWidthDigits),
                        centerDigits = data.optBoolean("centerDigits", face.centerDigits),
                        useLatinLetters = data.optBoolean("useLatinLetters", face.useLatinLetters),
                    ).normalized()
                }.toMap(),
                secondary = ClockRegionRow.decode(json.optJSONObject("secondary"), defaults.secondary),
                tertiary = ClockRegionRow.decode(json.optJSONObject("tertiary"), defaults.tertiary),
            ).normalized()
        }
    }
}
