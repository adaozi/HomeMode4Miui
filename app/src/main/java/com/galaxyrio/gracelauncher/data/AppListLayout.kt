package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

enum class AppListBlock(val key: String) {
    AllApps("app-list:all-apps"), BottomFolders("app-list:folders"), GraceLauncher("app-list:grace");
}

data class AppListLayout(
    val order: List<String>? = null,
    val showGraceLauncher: Boolean = true,
    val showGraceSettings: Boolean = true,
) {
    /** Empty categories remain selectable blocks even when their runtime headings are hidden. */
    fun orderedKeys(categories: List<AppCategory>): List<String> {
        val visible = categories.filter { it.placement == CategoryPlacement.AppList }.map { it.key } +
            listOf(AppListBlock.AllApps.key, AppListBlock.BottomFolders.key) +
            listOfNotNull(AppListBlock.GraceLauncher.key.takeIf { showGraceLauncher })
        val keys = visible.toSet()
        return ((order ?: defaultOrder) + visible).distinct().filter { it in keys }
    }

    fun normalized() = copy(order = order?.filter(String::isNotBlank)?.distinct())

    internal fun json(): JSONObject = normalized().let { value -> JSONObject().apply {
        value.order?.let { put("order", JSONArray(it)) }
        put("showGraceLauncher", value.showGraceLauncher)
        put("showGraceSettings", value.showGraceSettings)
    } }

    companion object {
        private val defaultOrder = listOf("category:$PinnedCategoryId",
            AppListBlock.AllApps.key, AppListBlock.BottomFolders.key, AppListBlock.GraceLauncher.key)

        internal fun decode(value: JSONObject?): AppListLayout = if (value == null) AppListLayout() else AppListLayout(
            order = value.optJSONArray("order")?.let { array ->
                (0 until array.length()).mapNotNull { array.opt(it) as? String }
            },
            showGraceLauncher = value.optBoolean("showGraceLauncher", true),
            showGraceSettings = value.optBoolean("showGraceSettings", true),
        ).normalized()
    }
}

/** All editors use one transaction to move categories between their exclusive display locations. */
internal fun synchronizeCategoryPlacement(previous: LauncherSettings, next: LauncherSettings): LauncherSettings {
    val nextList = next.listAppearance
    val appList = nextList.appList.copy(showGraceLauncher = nextList.appList.showGraceLauncher ||
        (!previous.listAppearance.showRecentlyInstalled && nextList.showRecentlyInstalled))
    val requested = next.appCategories ?: return next.copy(listAppearance = nextList.copy(appList = appList))
    val previousCategories = previous.appCategories.orEmpty()
    val protectedCategories = requested.toMutableList()
    previousCategories.forEachIndexed { index, category ->
        if (category.isBuiltIn && protectedCategories.none { it.id == category.id }) {
            protectedCategories.add(index.coerceAtMost(protectedCategories.size), category)
        }
    }
    val categories = AppCategory.normalize(protectedCategories)
    val previousById = previousCategories.associateBy { it.id }
    fun movedTo(placement: CategoryPlacement) = categories.filter {
        it.placement == placement && previousById[it.id]?.placement != placement
    }.map { it.key }
    fun cleanOrder(order: List<String>, placement: CategoryPlacement): List<String> {
        val active = categories.filter { it.placement == placement }.map { it.key }.toSet()
        return order.filter { !it.startsWith("category:") || it in active }.distinct()
    }
    val homeAdded = movedTo(CategoryPlacement.Home)
    val removedFromHome = previousCategories.any { it.showOnHome && categories.none { nextCategory -> nextCategory.id == it.id && nextCategory.showOnHome } }
    val homeOrder = if (next.homeLayout.homeOrder != null || homeAdded.isNotEmpty() || removedFromHome) {
        val prior = next.homeLayout.homeOrder ?: previous.homeLayout.homeOrder ?:
            (legacyHomeOrder(previous) + previousCategories.filter { it.showOnHome }.map { it.key })
        normalizeHomeOrder(cleanOrder(prior, CategoryPlacement.Home) + homeAdded)
    } else null
    val listAdded = movedTo(CategoryPlacement.AppList)
    val removedFromList = previousCategories.any { it.placement == CategoryPlacement.AppList &&
        categories.none { nextCategory -> nextCategory.id == it.id && nextCategory.placement == CategoryPlacement.AppList } }
    val listOrder = if (appList.order != null || listAdded.isNotEmpty() || removedFromList) {
        val prior = appList.order ?: if (previous.appCategories == null) appList.orderedKeys(categories)
            else previous.listAppearance.appList.orderedKeys(previousCategories)
        (cleanOrder(prior, CategoryPlacement.AppList) + listAdded).distinct()
    } else null
    return next.copy(appCategories = categories, homeLayout = next.homeLayout.copy(homeOrder = homeOrder),
        listAppearance = nextList.copy(appList = appList.copy(order = listOrder).normalized()))
}
