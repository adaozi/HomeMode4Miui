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

enum class AppNameVisibility { Both, Favorites, AppList, Neither }

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
    val showRecentlyInstalled: Boolean = true,
    /** null uses the localized name of the built-in folder. */
    val recentlyInstalledName: String? = null,
    /** Folder contents keep their labels unless explicitly linked to the outer list. */
    val applyNameVisibilityToFolders: Boolean = false,
    val appList: AppListLayout = AppListLayout(),
    val symbolHorizontalOffset: Int = 0,
) {
    fun showNames(favorites: Boolean, insideFolder: Boolean = false): Boolean =
        if (insideFolder && !applyNameVisibilityToFolders) true else when (names) {
            AppNameVisibility.Both -> true
            AppNameVisibility.Favorites -> favorites
            AppNameVisibility.AppList -> !favorites
            AppNameVisibility.Neither -> false
        }

    fun normalized() = copy(appSpacing = appSpacing.coerceIn(0, 64), sidePadding = sidePadding.coerceIn(8, 80),
        iconNameGap = iconNameGap.coerceIn(0, 64), fontSize = fontSize.coerceIn(10, 32), alphabet = alphabet.normalized(),
        symbolHorizontalOffset = symbolHorizontalOffset.coerceIn(-16, 16),
        recentlyInstalledName = recentlyInstalledName?.trim()?.takeIf(String::isNotEmpty), appList = appList.normalized())

    fun encode(): String = normalized().let { safe -> JSONObject().apply {
        put("appSpacing", safe.appSpacing); put("sidePadding", safe.sidePadding); put("iconNameGap", safe.iconNameGap)
        put("fontId", safe.fontId ?: JSONObject.NULL); put("fontSize", safe.fontSize)
        put("fontColor", safe.fontColor?.json())
        put("wallpaperDimColor", safe.wallpaperDimColor?.json())
        put("alphabet", safe.alphabet.json())
        put("names", safe.names.name); put("leftAlphabet", safe.leftAlphabet)
        put("applyNameVisibilityToFolders", safe.applyNameVisibilityToFolders)
        put("showRecentlyInstalled", safe.showRecentlyInstalled)
        put("recentlyInstalledName", safe.recentlyInstalledName ?: JSONObject.NULL)
        put("appList", safe.appList.json())
        put("symbolHorizontalOffset", safe.symbolHorizontalOffset)
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
                applyNameVisibilityToFolders = value.optBoolean("applyNameVisibilityToFolders", false),
                showRecentlyInstalled = value.optBoolean("showRecentlyInstalled", true),
                recentlyInstalledName = if (value.isNull("recentlyInstalledName")) null else value.optString("recentlyInstalledName"),
                appList = AppListLayout.decode(value.optJSONObject("appList")),
                symbolHorizontalOffset = value.optInt("symbolHorizontalOffset", 0),
            ).normalized()
        }.getOrElse { ListAppearance() }
    }
}
