package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

const val FavoritesCategoryId = "favorites"
const val MusicCategoryId = "music"
const val PinnedCategoryId = "pinned"
const val NotificationCategoryId = "notification"
val BuiltInCategoryIds: Set<String> = setOf(FavoritesCategoryId, MusicCategoryId, PinnedCategoryId, NotificationCategoryId)

enum class CategoryCondition { Always, Headphones, Time }
enum class CategoryPlacement { None, Home, AppList }
enum class CategoryInclusion { Manual, Dynamic }
enum class CategoryDynamicRule { Notifications, RecentlyInstalled, FrequentlyOpened }
enum class CategoryNotificationLevel { Silent, Normal, High }
enum class CategoryIcon { None, MusicNote, Keep, NotificationsUnread, Circle, Info, Schedule, ClearDay, Bedtime }

private fun defaultCategoryIcon(id: String): CategoryIcon = when (id) {
    FavoritesCategoryId -> CategoryIcon.None
    MusicCategoryId -> CategoryIcon.MusicNote
    PinnedCategoryId -> CategoryIcon.Keep
    NotificationCategoryId -> CategoryIcon.NotificationsUnread
    else -> CategoryIcon.Circle
}

data class AppCategory(
    val id: String,
    /** Built-in names are localized until the user renames them. */
    val name: String? = null,
    val itemKeys: List<String> = emptyList(),
    val condition: CategoryCondition = CategoryCondition.Always,
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 18 * 60,
    val placement: CategoryPlacement = CategoryPlacement.None,
    val inclusion: CategoryInclusion = CategoryInclusion.Manual,
    val dynamicRule: CategoryDynamicRule = CategoryDynamicRule.Notifications,
    val notificationLevels: Set<CategoryNotificationLevel> = CategoryNotificationLevel.entries.toSet(),
    val appLimit: Int = 8,
    val icon: CategoryIcon = defaultCategoryIcon(id),
) {
    val key: String get() = "category:$id"
    val isBuiltIn: Boolean get() = id in BuiltInCategoryIds
    val showOnHome: Boolean get() = placement == CategoryPlacement.Home

    fun isVisible(headphones: Boolean, minuteOfDay: Int): Boolean = when (condition) {
        CategoryCondition.Always -> true
        CategoryCondition.Headphones -> headphones
        CategoryCondition.Time -> if (startMinute < endMinute) minuteOfDay in startMinute until endMinute
            else minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun normalized() = copy(name = name?.trim()?.takeIf(String::isNotEmpty),
        itemKeys = itemKeys.filter { it.isNotBlank() && !it.startsWith("category:") &&
            !it.startsWith("widget:") && !it.startsWith("grace:") }.distinct(),
        startMinute = startMinute.coerceIn(0, 1439), endMinute = endMinute.coerceIn(0, 1439), appLimit = appLimit.coerceIn(1, 30),
        icon = if (placement == CategoryPlacement.AppList && icon == CategoryIcon.None) CategoryIcon.Circle else icon)

    companion object {
        fun defaults(favorites: List<String> = emptyList()) = listOf(
            AppCategory(FavoritesCategoryId, itemKeys = favorites, placement = CategoryPlacement.Home),
            AppCategory(MusicCategoryId, condition = CategoryCondition.Headphones, placement = CategoryPlacement.Home),
            AppCategory(PinnedCategoryId, placement = CategoryPlacement.AppList),
            AppCategory(NotificationCategoryId, placement = CategoryPlacement.None, inclusion = CategoryInclusion.Dynamic),
        )

        /** Preserve existing configuration/order; restore only missing protected categories. */
        fun normalize(categories: List<AppCategory>): List<AppCategory> {
            val existing = categories.filter { it.id.isNotBlank() }.map(AppCategory::normalized).distinctBy { it.id }
            val ids = existing.map { it.id }.toSet()
            return existing + defaults().filterNot { it.id in ids }
        }

        fun encode(categories: List<AppCategory>): String = JSONArray().apply {
            normalize(categories).forEach { category -> category.let {
                put(JSONObject().put("id", it.id).put("name", it.name ?: JSONObject.NULL)
                    .put("items", JSONArray(it.itemKeys)).put("condition", it.condition.name)
                    .put("startMinute", it.startMinute).put("endMinute", it.endMinute).put("placement", it.placement.name)
                    .put("inclusion", it.inclusion.name).put("dynamicRule", it.dynamicRule.name)
                    .put("notificationLevels", JSONArray(CategoryNotificationLevel.entries.filter { level -> level in it.notificationLevels }.map { level -> level.name }))
                    .put("appLimit", it.appLimit).put("icon", it.icon.name))
            } }
        }.toString()

        fun decode(json: String?): List<AppCategory>? = json?.let { runCatching {
            val array = JSONArray(it)
            val categories = (0 until array.length()).mapNotNull { index ->
                val value = array.optJSONObject(index) ?: return@mapNotNull null
                val id = (value.opt("id") as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val keys = value.optJSONArray("items") ?: JSONArray()
                val levels = value.optJSONArray("notificationLevels")
                AppCategory(
                    id = id,
                    name = (value.opt("name") as? String)?.takeIf(String::isNotBlank),
                    itemKeys = (0 until keys.length()).mapNotNull { i -> keys.opt(i) as? String },
                    condition = CategoryCondition.entries.firstOrNull { entry -> entry.name == value.optString("condition") } ?: CategoryCondition.Always,
                    startMinute = value.optInt("startMinute", 480), endMinute = value.optInt("endMinute", 1080),
                    placement = CategoryPlacement.entries.firstOrNull { entry -> entry.name == value.optString("placement") }
                        ?: if (value.optBoolean("showOnHome", false)) CategoryPlacement.Home else CategoryPlacement.None,
                    inclusion = CategoryInclusion.entries.firstOrNull { entry -> entry.name == value.optString("inclusion") } ?: CategoryInclusion.Manual,
                    dynamicRule = CategoryDynamicRule.entries.firstOrNull { entry -> entry.name == value.optString("dynamicRule") } ?: CategoryDynamicRule.Notifications,
                    notificationLevels = if (levels == null) CategoryNotificationLevel.entries.toSet() else
                        (0 until levels.length()).mapNotNull { i -> CategoryNotificationLevel.entries.firstOrNull { level -> level.name == levels.optString(i) } }.toSet(),
                    appLimit = value.optInt("appLimit", 8),
                    icon = CategoryIcon.entries.firstOrNull { entry -> entry.name == value.optString("icon") } ?: defaultCategoryIcon(id),
                ).normalized()
            }
            normalize(categories)
        }.getOrNull() }
    }
}

/** Replace legacy app/shortcut rows with one category, keeping widgets and direct groups. */
internal fun migrateHomeCategories(settings: LauncherSettings, legacyFavorites: List<String>): LauncherSettings {
    if (settings.appCategories != null) return settings
    val members = legacyFavorites.filterNot { it.startsWith("folder:") }.distinct()
    val memberSet = members.toSet()
    val previous = normalizeHomeOrder(settings.homeLayout.homeOrder ?: (legacyHomeOrder(settings) + legacyFavorites))
    val orderedMembers = (previous.filter { it in memberSet } + members).distinct()
    val categories = AppCategory.defaults(orderedMembers)
    val order = (previous.map { if (it in memberSet) categories.first().key else it } + categories.filter { it.showOnHome }.map { it.key }).distinct()
    return settings.copy(appCategories = categories, homeLayout = settings.homeLayout.copy(homeOrder = order))
}

internal data class CategoryHomeContent(val items: List<PopupItem>, val owners: Map<String, String>)

/** Expand only active categories; the first visible occurrence owns each row. */
internal fun expandHomeCategories(
    entries: List<PopupItem>, categories: List<AppCategory>, headphones: Boolean, minuteOfDay: Int,
    categoryItems: (AppCategory) -> List<String> = { it.itemKeys },
    available: (String) -> Boolean,
): CategoryHomeContent {
    val byKey = categories.associateBy { it.key }
    val owners = linkedMapOf<String, String>()
    val items = buildList {
        entries.forEach { entry ->
            val category = byKey[entry.key]
            if (category == null) {
                if (owners.putIfAbsent(entry.key, entry.key) == null) add(entry)
            } else if (category.isVisible(headphones, minuteOfDay)) {
                categoryItems(category).forEach { key ->
                    if (available(key) && owners.putIfAbsent(key, entry.key) == null) add(PopupItem(key))
                }
            }
        }
    }
    return CategoryHomeContent(items, owners)
}
