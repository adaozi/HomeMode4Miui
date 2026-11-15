package com.galaxyrio.gracelauncher.data.tasks

import org.json.JSONArray
import org.json.JSONObject

enum class TaskSortMode { DueDate, Priority, Name }

data class TasksPreferences(
    val enabled: Boolean = false,
    /** null includes every list, including newly added lists; an empty set includes none. */
    val selectedListIds: Set<Long>? = null,
    val sortMode: TaskSortMode = TaskSortMode.DueDate,
    val undatedFirst: Boolean = true,
) {
    fun normalized() = copy(selectedListIds = selectedListIds?.filterTo(linkedSetOf()) { it > 0 })

    fun json(): JSONObject = normalized().let { value -> JSONObject()
        .put("enabled", value.enabled)
        .put("selectedListIds", value.selectedListIds?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
        .put("sortMode", value.sortMode.name)
        .put("undatedFirst", value.undatedFirst)
    }

    companion object {
        fun decode(json: JSONObject?): TasksPreferences {
            if (json == null) return TasksPreferences()
            return TasksPreferences(
                enabled = json.optBoolean("enabled", false),
                selectedListIds = json.optJSONArray("selectedListIds")?.let { ids -> buildSet {
                    for (index in 0 until ids.length()) {
                        val id = ids.optLong(index, 0L)
                        if (id > 0L) add(id)
                    }
                } },
                sortMode = TaskSortMode.entries.firstOrNull { it.name == json.optString("sortMode") } ?: TaskSortMode.DueDate,
                undatedFirst = json.optBoolean("undatedFirst", true),
            )
        }
    }
}
