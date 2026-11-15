package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private val NonAppPrefixes = listOf("folder:", "widget:", "grace:", "shortcut:")

/** Activity keys only: "com.example/Main", optionally behind "profile:<serial>:". */
internal fun isAppKey(key: String): Boolean {
    val component = if (key.startsWith("profile:")) key.substringAfter(':').substringAfter(':', "") else key
    return '/' in component && NonAppPrefixes.none { component.startsWith(it) } &&
        component != HomeContentKey && component != WidgetScreenKey &&
        component != AgendaWidgetKey && component != AgendaWidgetDraftKey
}

/**
 * Saved app keys whose activity is gone, mapped to the only launcher activity left in the same package and profile.
 * Some apps rename their launcher activity in every update; packages with several activities are left alone.
 */
internal fun renamedAppKeys(saved: Iterable<String>, installed: Collection<String>): Map<String, String> {
    // "profile:7:com.example/Main" -> "profile:7:com.example"
    val byPackage = installed.filter(::isAppKey).groupBy { it.substringBefore('/') }
    val present = installed.toSet()
    return saved.filter { isAppKey(it) && it !in present }.distinct()
        .mapNotNull { key -> byPackage[key.substringBefore('/')]?.singleOrNull()?.let { key to it } }.toMap()
}

/** Replaces whole app keys in a stored value: the value itself, or any string inside JSON. */
internal fun renameValue(value: String, renames: Map<String, String>): String {
    // JSON escapes '/', so look for the package part.
    if (renames.keys.none { it.substringBefore('/') in value }) return value
    renames[value]?.let { return it }
    val start = value.trimStart().firstOrNull()
    if (start != '{' && start != '[') return value
    val parsed = runCatching { JSONTokener(value).nextValue() }.getOrNull() ?: return value
    var changed = false
    fun walk(node: Any?): Any? = when (node) {
        is JSONObject -> JSONObject().also { out -> node.keys().forEach { k -> out.put(walk(k) as String, walk(node.get(k))) } }
        is JSONArray -> JSONArray().also { out -> for (i in 0 until node.length()) out.put(walk(node.get(i))) }
        is String -> (renames[node] ?: node).also { if (it != node) changed = true }
        else -> node
    }
    val result = walk(parsed)
    return if (changed) result.toString() else value
}
