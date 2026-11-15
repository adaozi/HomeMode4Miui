package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

enum class ClockTaskField { Title, Countdown, DueDate, DueTime, StartDate, Priority, List, Notes, Color }

data class ClockTasks(
    val order: List<ClockTaskField> = ClockTaskField.entries,
    val selected: Set<ClockTaskField> = setOf(ClockTaskField.Title, ClockTaskField.Countdown),
    val typography: ClockRowTypography = ClockRowTypography(),
    val taskCount: Int = 1,
    val useLatinLetters: Boolean = false,
    val centered: Boolean = false,
    val rightAligned: Set<ClockTaskField> = emptySet(),
) {
    val visibleFields: List<ClockTaskField> get() = normalized().order.filter { it in selected }.let { fields ->
        fields.filterNot { it in rightAligned } + fields.filter { it in rightAligned }
    }

    fun normalized() = copy(order = (order + ClockTaskField.entries).distinct(), typography = typography.normalized(),
        taskCount = taskCount.coerceIn(0, 9))

    internal fun json(): JSONObject = normalized().let { value -> JSONObject().apply {
        put("order", JSONArray(value.order.map { it.name }))
        put("selected", JSONArray(value.order.filter { it in value.selected }.map { it.name }))
        put("typography", JSONObject().apply {
            put("size", value.typography.size)
            put("weight", value.typography.weight)
            put("fontShadow", value.typography.fontShadow)
            put("fontId", value.typography.fontId ?: JSONObject.NULL)
        })
        put("taskCount", value.taskCount)
        put("useLatinLetters", value.useLatinLetters)
        put("centered", value.centered)
        put("rightAligned", JSONArray(value.order.filter { it in value.rightAligned }.map { it.name }))
    } }

    companion object {
        internal fun decode(json: JSONObject?): ClockTasks {
            val defaults = ClockTasks()
            if (json == null) return defaults
            fun fields(key: String): List<ClockTaskField>? = json.optJSONArray(key)?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    ClockTaskField.entries.firstOrNull { it.name == array.optString(index) }
                }.distinct()
            }
            val typography = json.optJSONObject("typography")
            return ClockTasks(
                order = fields("order") ?: defaults.order,
                selected = fields("selected")?.toSet() ?: defaults.selected,
                typography = ClockRowTypography(
                    size = typography?.optInt("size", defaults.typography.size) ?: defaults.typography.size,
                    weight = typography?.optInt("weight", defaults.typography.weight) ?: defaults.typography.weight,
                    fontShadow = typography?.optInt("fontShadow", defaults.typography.fontShadow) ?: defaults.typography.fontShadow,
                    fontId = typography?.optString("fontId")?.takeIf { it.isNotBlank() && it != "null" },
                ),
                taskCount = json.optInt("taskCount", defaults.taskCount),
                useLatinLetters = json.optBoolean("useLatinLetters", defaults.useLatinLetters),
                centered = json.optBoolean("centered", json.optString("alignment") == ClockAlignment.Center.name),
                rightAligned = fields("rightAligned")?.toSet() ?: if (json.optString("alignment") == ClockAlignment.Right.name)
                    ClockTaskField.entries.toSet() else defaults.rightAligned,
            ).normalized()
        }
    }
}
