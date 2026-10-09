/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/data/FavoritesStore.kt
 * P1 移植：逐字拷贝，未改动。SP 文件名原版即带 grace_ 前缀（grace_launcher_preferences），
 *   落在 com.miui.home 进程 shared_prefs 下，与宿主偏好天然隔离。
 */
package com.galaxyrio.gracelauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray

class FavoritesStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))

    fun favoritesFor(apps: List<LauncherApp>): List<String> {
        preferences.getString(KEY_ORDER, null)?.let { encoded ->
            runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { array.getString(it) }.filter(String::isNotBlank).distinct()
            }.getOrNull()?.let { return it }
        }
        if (preferences.contains(KEY_FAVORITES)) {
            val previous = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
            // Sets had no stored order. Preserve the old, name-sorted home layout
            // during migration, including hidden or temporarily unavailable apps.
            val installed = apps.map(LauncherApp::key).filter { it in previous }
            val migrated = installed + (previous - installed.toSet()).sorted()
            save(migrated)
            return migrated
        }

        val initial = apps.sortedWith(
            compareBy<LauncherApp> { preferredRank(it) }.thenBy { it.label.lowercase() },
        ).take(DEFAULT_FAVORITE_COUNT).map(LauncherApp::key)
        save(initial)
        return initial
    }

    fun toggle(appKey: String, current: List<String>): List<String> {
        val updated = if (appKey in current) current - appKey else current + appKey
        save(updated)
        return updated
    }

    fun reorder(requested: List<String>, current: List<String>): List<String> {
        // Only reorder existing members. The editor omits hidden/uninstalled apps;
        // leave their slots intact so unhiding or reinstalling restores them.
        val movable = requested.distinct().filter { it in current }
        val moving = movable.toSet()
        val replacements = movable.iterator()
        val updated = current.distinct().map { if (it in moving) replacements.next() else it }
        save(updated)
        return updated
    }

    /** P1 新增（原版无）：清空已存收藏；下一次 favoritesFor 重新走默认初始化。
     *  供 P1 空态引导（HomeScreen favorites_empty）恢复演示闭环。 */
    fun reset() {
        preferences.edit {
            remove(KEY_ORDER)
            remove(KEY_FAVORITES)
        }
    }

    /**
     * C 组新增（原版无）：只读当前收藏序（无默认初始化副作用）。
     * settings 桥快照 / reorderFavorites 命令需要"现值"语义 —— 走 favoritesFor
     * 会在空 apps 输入时把空表写回 SP，反而摧毁 P1 的默认初始化链路。
     * 返回 null = 尚未初始化（宿主 VM 首挂载前），桥侧按空表处理。
     */
    fun currentOrder(): List<String>? {
        preferences.getString(KEY_ORDER, null)?.let { encoded ->
            return runCatching {
                val array = JSONArray(encoded)
                List(array.length()) { array.getString(it) }.filter(String::isNotBlank).distinct()
            }.getOrNull()
        }
        if (preferences.contains(KEY_FAVORITES)) {
            val previous = preferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
            return previous.sorted()
        }
        return null
    }

    private fun save(keys: List<String>) {
        preferences.edit {
            putString(KEY_ORDER, JSONArray(keys.distinct()).toString())
            putStringSet(KEY_FAVORITES, keys.toSet())
        }
    }

    private fun preferredRank(app: LauncherApp): Int {
        val searchable = "${app.packageName} ${app.label}".lowercase()
        val preferredTokens = listOf(
            "dialer", "phone", "telecom",
            "message", "sms",
            "camera",
            "chrome", "browser",
            "gmail", "mail",
            "calendar",
        )
        val firstMatch = preferredTokens.indexOfFirst(searchable::contains)
        return if (firstMatch >= 0) firstMatch else Int.MAX_VALUE
    }

    private companion object {
        const val FILE_NAME = "grace_launcher_preferences"
        const val KEY_FAVORITES = "favorite_components"
        const val KEY_ORDER = "favorite_component_order"
        const val DEFAULT_FAVORITE_COUNT = 6
    }
}
