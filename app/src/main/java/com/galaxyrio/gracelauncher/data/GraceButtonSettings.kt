package com.galaxyrio.gracelauncher.data

import org.json.JSONObject

enum class GraceButtonAction {
    App, Shortcut, Folder, Settings, Search, Website, LockScreen, AppList, Notifications, QuickSettings, Assistant, Agenda, Disabled;

    val requiresAccessibility: Boolean get() = this == LockScreen || this == Notifications || this == QuickSettings
}

enum class GraceButtonGesture(val storageKey: String) {
    Tap("tap"), LongPress("longPress"), DoubleTap("doubleTap"),
    SwipeUp("swipeUp"), SwipeDown("swipeDown"), SwipeLeft("swipeLeft"), SwipeRight("swipeRight");
}

data class GraceButtonTarget(
    val action: GraceButtonAction,
    val itemKey: String? = null,
    val enabled: Boolean = action != GraceButtonAction.Disabled,
    val url: String? = null,
) {
    val active: Boolean get() = enabled && action != GraceButtonAction.Disabled
}

data class GraceButtonSettings(
    val enabled: Boolean = true,
    val tap: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search),
    val longPress: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Settings),
    val doubleTap: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search, enabled = false),
    val swipeUp: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search, enabled = false),
    val swipeDown: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search, enabled = false),
    val swipeLeft: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search, enabled = false),
    val swipeRight: GraceButtonTarget = GraceButtonTarget(GraceButtonAction.Search, enabled = false),
    val animationsEnabled: Boolean = true,
    val searchAnimationDurationMs: Int = 500,
    val appListAnimationDurationMs: Int = 500,
    /** 100 keeps the normal distance and velocity thresholds. Higher is easier. */
    val swipeSensitivity: Int = 100,
    /** null follows the system double-tap timeout. */
    val doubleTapIntervalMs: Int? = null,
) {
    fun target(gesture: GraceButtonGesture): GraceButtonTarget = when (gesture) {
        GraceButtonGesture.Tap -> tap
        GraceButtonGesture.LongPress -> longPress
        GraceButtonGesture.DoubleTap -> doubleTap
        GraceButtonGesture.SwipeUp -> swipeUp
        GraceButtonGesture.SwipeDown -> swipeDown
        GraceButtonGesture.SwipeLeft -> swipeLeft
        GraceButtonGesture.SwipeRight -> swipeRight
    }

    fun withTarget(gesture: GraceButtonGesture, target: GraceButtonTarget): GraceButtonSettings = when (gesture) {
        GraceButtonGesture.Tap -> copy(tap = target)
        GraceButtonGesture.LongPress -> copy(longPress = target)
        GraceButtonGesture.DoubleTap -> copy(doubleTap = target)
        GraceButtonGesture.SwipeUp -> copy(swipeUp = target)
        GraceButtonGesture.SwipeDown -> copy(swipeDown = target)
        GraceButtonGesture.SwipeLeft -> copy(swipeLeft = target)
        GraceButtonGesture.SwipeRight -> copy(swipeRight = target)
    }

    fun withEnabled(gesture: GraceButtonGesture, enabled: Boolean): GraceButtonSettings {
        val current = target(gesture)
        val action = if (current.action != GraceButtonAction.Disabled) current.action
            else if (gesture == GraceButtonGesture.LongPress) GraceButtonAction.Settings else GraceButtonAction.Search
        return withTarget(gesture, current.copy(action = action, enabled = enabled))
    }

    fun encode(): String = JSONObject().apply {
        put("enabled", enabled)
        put("animationsEnabled", animationsEnabled)
        put("searchAnimationDurationMs", searchAnimationDurationMs.coerceIn(100, 1500))
        put("appListAnimationDurationMs", appListAnimationDurationMs.coerceIn(100, 1500))
        put("swipeSensitivity", swipeSensitivity.coerceIn(50, 200))
        put("doubleTapIntervalMs", doubleTapIntervalMs?.coerceIn(150, 700))
        GraceButtonGesture.entries.forEach { gesture ->
            val target = target(gesture)
            put(gesture.storageKey, JSONObject().put("action", target.action.name).put("key", target.itemKey)
                .put("enabled", target.active).put("url", target.url))
        }
    }.toString()

    companion object {
        fun homeDefaults() = GraceButtonSettings(
            tap = GraceButtonTarget(GraceButtonAction.Disabled),
            longPress = GraceButtonTarget(GraceButtonAction.Disabled),
            swipeUp = GraceButtonTarget(GraceButtonAction.Search),
            swipeDown = GraceButtonTarget(GraceButtonAction.Notifications),
        )

        fun decode(json: String?, defaults: GraceButtonSettings = GraceButtonSettings()): GraceButtonSettings = runCatching {
            val data = JSONObject(json ?: "{}")
            var settings = defaults.copy(
                enabled = data.optBoolean("enabled", defaults.enabled),
                animationsEnabled = data.optBoolean("animationsEnabled", defaults.animationsEnabled),
                searchAnimationDurationMs = data.optInt("searchAnimationDurationMs", defaults.searchAnimationDurationMs).coerceIn(100, 1500),
                appListAnimationDurationMs = data.optInt("appListAnimationDurationMs", defaults.appListAnimationDurationMs).coerceIn(100, 1500),
                swipeSensitivity = data.optInt("swipeSensitivity", defaults.swipeSensitivity).coerceIn(50, 200),
                doubleTapIntervalMs = if (data.has("doubleTapIntervalMs") && !data.isNull("doubleTapIntervalMs"))
                    data.optInt("doubleTapIntervalMs", 300).coerceIn(150, 700) else defaults.doubleTapIntervalMs,
            )
            GraceButtonGesture.entries.forEach { gesture ->
                val saved = data.optJSONObject(gesture.storageKey) ?: return@forEach
                val fallback = settings.target(gesture)
                val action = GraceButtonAction.entries.firstOrNull { it.name == saved.optString("action") } ?: fallback.action
                settings = settings.withTarget(gesture, GraceButtonTarget(action,
                    saved.optString("key").takeIf { it.isNotBlank() && it != "null" },
                    // Old tap/long-press targets had no enabled field.
                    saved.optBoolean("enabled", action != GraceButtonAction.Disabled),
                    saved.optString("url").takeIf { it.isNotBlank() && it != "null" }))
            }
            settings
        }.getOrDefault(defaults)
    }
}
