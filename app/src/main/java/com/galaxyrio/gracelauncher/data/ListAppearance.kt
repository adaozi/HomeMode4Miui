package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

/** Ordered, per-glyph fallback chain. Unchecked fonts stay available in the manager. */
data class FontLibrary(
    val selected: List<String> = listOf(Josefin),
    val unselected: List<String> = emptyList(),
) {
    fun ordered(available: List<String>): List<String> =
        (selected + unselected + available).distinct().filter { it in available }

    fun toggle(id: String, available: List<String>): FontLibrary {
        val order = ordered(available)
        return if (id in selected) copy(selected = selected - id, unselected = order.filter { it !in selected } + id)
        else copy(selected = selected + id, unselected = order.filter { it !in selected && it != id })
    }

    fun reorder(order: List<String>) = copy(selected = order.filter { it in selected }, unselected = order.filterNot { it in selected })
    fun without(ids: Set<String>) = copy(selected = selected.filterNot { it in ids }, unselected = unselected.filterNot { it in ids })
    fun encode(): String = JSONObject().put("selected", JSONArray(selected.distinct()))
        .put("unselected", JSONArray(unselected.distinct())).toString()

    companion object {
        const val Josefin = "josefin_sans"
        val builtIns: List<String> get() = AppFont.entries.map { it.id ?: Josefin }
        fun decode(json: String?, legacyFont: String? = null): FontLibrary = runCatching {
            if (json == null) FontLibrary(selected = listOf(legacyFont ?: Josefin)) else {
                val value = JSONObject(json)
                fun ids(key: String): List<String> = value.optJSONArray(key)?.let { array ->
                    (0 until array.length()).mapNotNull { (array.opt(it) as? String)?.takeIf(String::isNotBlank) }.distinct()
                }.orEmpty()
                FontLibrary(ids("selected"), ids("unselected"))
            }
        }.getOrElse { FontLibrary() }
    }
}

enum class AppNameVisibility { Both, Favorites, AppList }

/** Geometry is in dp; text size is in sp. The shared icon designer owns icon size. */
data class ListAppearance(
    val appSpacing: Int = 16,
    val sidePadding: Int = 44,
    val iconNameGap: Int = 20,
    val fontId: String? = null,
    val fontSize: Int = 16,
    val fontColor: IconColor? = null,
    val names: AppNameVisibility = AppNameVisibility.Both,
    val leftAlphabet: Boolean = true,
    /** null keeps the automatic, contrast-aware primary tint. */
    val wallpaperDimColor: IconColor? = null,
    val alphabet: AlphabetAppearance = AlphabetAppearance(),
) {
    fun showNames(favorites: Boolean) = names == AppNameVisibility.Both ||
        names == if (favorites) AppNameVisibility.Favorites else AppNameVisibility.AppList

    fun normalized() = copy(appSpacing = appSpacing.coerceIn(0, 64), sidePadding = sidePadding.coerceIn(8, 80),
        iconNameGap = iconNameGap.coerceIn(0, 64), fontSize = fontSize.coerceIn(10, 32), alphabet = alphabet.normalized())

    fun encode(): String = normalized().let { safe -> JSONObject().apply {
        put("appSpacing", safe.appSpacing); put("sidePadding", safe.sidePadding); put("iconNameGap", safe.iconNameGap)
        put("fontId", safe.fontId ?: JSONObject.NULL); put("fontSize", safe.fontSize)
        put("fontColor", safe.fontColor?.json())
        put("wallpaperDimColor", safe.wallpaperDimColor?.json())
        put("alphabet", safe.alphabet.json())
        put("names", safe.names.name); put("leftAlphabet", safe.leftAlphabet)
    }.toString() }

    companion object {
        fun decode(json: String?, hideFavoriteNames: Boolean = false): ListAppearance = runCatching {
            val value = JSONObject(json ?: "{}")
            ListAppearance(
                appSpacing = value.optInt("appSpacing", 16), sidePadding = value.optInt("sidePadding", 44),
                iconNameGap = value.optInt("iconNameGap", 20), fontSize = value.optInt("fontSize", 16),
                fontId = value.optString("fontId").takeIf { it.isNotBlank() && it != "null" },
                fontColor = IconColor.decode(value.optJSONObject("fontColor")),
                wallpaperDimColor = IconColor.decode(value.optJSONObject("wallpaperDimColor")),
                alphabet = AlphabetAppearance.decode(value.optJSONObject("alphabet")),
                names = AppNameVisibility.entries.firstOrNull { it.name == value.optString("names") }
                    ?: if (hideFavoriteNames) AppNameVisibility.AppList else AppNameVisibility.Both,
                leftAlphabet = value.optBoolean("leftAlphabet", true),
            ).normalized()
        }.getOrElse { ListAppearance() }
    }
}
