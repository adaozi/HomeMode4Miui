package com.galaxyrio.gracelauncher.data.icons

import org.xmlpull.v1.XmlPullParser

/** ADW/Nova appfilter data only. No classes or code are loaded from icon packages. */
internal data class IconPackDefinition(
    val icons: Map<String, String>,
    val calendars: Map<String, String>,
    val backgrounds: List<String>,
    val masks: List<String>,
    val overlays: List<String>,
    val scale: Float,
) {
    // An unambiguous package match covers renamed launcher activities without
    // assigning one arbitrary icon to packages with several different activities.
    private val packageIcons = uniquePackageEntries(icons)
    private val packageCalendars = uniquePackageEntries(calendars)

    fun candidates(component: String, dayOfMonth: Int): List<String> {
        val key = normalizeIconComponent(component) ?: return emptyList()
        val pkg = key.substringBefore('/')
        return buildList {
            calendars[key]?.let { add("$it${dayOfMonth.coerceIn(1, 31)}") }
            icons[key]?.let(::add)
            packageCalendars[pkg]?.let { add("$it${dayOfMonth.coerceIn(1, 31)}") }
            packageIcons[pkg]?.let(::add)
        }.distinct()
    }
}

private fun uniquePackageEntries(entries: Map<String, String>): Map<String, String> =
    entries.entries.groupBy { it.key.substringBefore('/') }.mapNotNull { (pkg, values) ->
        val explicit = entries[pkg]
        val unique = values.map { it.value }.distinct().singleOrNull()
        (explicit ?: unique)?.let { pkg to it }
    }.toMap()

internal fun normalizeIconComponent(value: String): String? {
    val raw = value.trim().let {
        if (it.startsWith("ComponentInfo{")) {
            if (!it.endsWith('}')) return null
            it.removePrefix("ComponentInfo{").dropLast(1)
        } else it
    }
    val pkg = raw.substringBefore('/').trim()
    if (!pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) return null
    if ('/' !in raw) return pkg
    val activity = raw.substringAfter('/').trim()
    if (activity.isBlank() || activity.any { it.isWhitespace() || it == '/' || it == '{' || it == '}' }) return null
    return "$pkg/${if (activity.startsWith('.')) pkg + activity else activity}"
}

internal fun parseIconPack(parser: XmlPullParser, checkCancelled: () -> Unit = {}): IconPackDefinition {
    val icons = linkedMapOf<String, String>()
    val calendars = linkedMapOf<String, String>()
    var backgrounds = emptyList<String>()
    var masks = emptyList<String>()
    var overlays = emptyList<String>()
    var scale = 1f
    var nodes = 0
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (++nodes % 256 == 0) checkCancelled()
        require(nodes <= 200_000) { "Icon pack appfilter is too large" }
        if (parser.eventType == XmlPullParser.START_TAG) {
            fun attribute(name: String) = parser.getAttributeValue(null, name)?.trim()?.takeIf { it.isNotEmpty() }
            fun images() = (0 until parser.attributeCount).mapNotNull { index ->
                parser.getAttributeName(index).takeIf { it.matches(Regex("img\\d*")) }
                    ?.let { parser.getAttributeValue(index).trim().takeIf(String::isNotEmpty) }
            }
            when (parser.name) {
                "item", "calendar" -> {
                    val component = attribute("component")?.let(::normalizeIconComponent)
                    val resource = attribute(if (parser.name == "calendar") "prefix" else "drawable")
                    if (component != null && resource != null) {
                        if (parser.name == "calendar") calendars[component] = resource else icons[component] = resource
                    }
                }
                "iconback" -> backgrounds = images()
                "iconmask" -> masks = images()
                "iconupon" -> overlays = images()
                "scale" -> attribute("factor")?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
                    ?.let { scale = it.coerceIn(0.1f, 2f) }
            }
        }
        parser.next()
    }
    return IconPackDefinition(icons, calendars, backgrounds, masks, overlays, scale)
}
