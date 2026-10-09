package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

enum class GraceButtonAction { Search, Settings, App, Shortcut, Disabled }

data class GraceButtonTarget(val action: GraceButtonAction, val itemKey: String? = null)

data class GraceButtonSettings(
    val enabled: Boolean = true,
    val tap: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search),
    val longPress: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Settings),
) {
    fun withTarget(longClick: Boolean, target: GraceButtonTarget) =
        if (longClick) copy(longPress = target) else copy(tap = target)

    fun encode(): String = JSONObject().apply {
        put("enabled", enabled)
        fun write(name: String, target: GraceButtonTarget) {
            put(name, JSONObject().put("action", target.action.name).put("key", target.itemKey))
        }
        write("tap", tap)
        write("longPress", longPress)
    }.toString()

    companion object {
        fun decode(json: String?): GraceButtonSettings = runCatching {
            val data = JSONObject(json ?: "{}")
            fun read(name: String, fallback: GraceButtonAction): GraceButtonTarget {
                val target = data.optJSONObject(name)
                return GraceButtonTarget(
                    GraceButtonAction.entries.firstOrNull { it.name == target?.optString("action") } ?: fallback,
                    target?.optString("key")?.takeIf { it.isNotBlank() && it != "null" },
                )
            }
            GraceButtonSettings(data.optBoolean("enabled", true), read("tap", GraceButtonAction.Search),
                read("longPress", GraceButtonAction.Settings))
        }.getOrDefault(GraceButtonSettings())
    }
}
