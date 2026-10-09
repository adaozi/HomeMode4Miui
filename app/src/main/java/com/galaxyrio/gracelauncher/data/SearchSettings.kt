package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

enum class SearchEngine(val id: String, val urlTemplate: String? = null) {
    Google("google", "https://www.google.com/search?q={query}"),
    Bing("bing", "https://www.bing.com/search?q={query}"),
    Sogou("sogou", "https://www.sogou.com/web?query={query}"),
    DuckDuckGo("duckduckgo", "https://duckduckgo.com/?q={query}"),
    Qwant("qwant", "https://www.qwant.com/?q={query}"),
    Startpage("startpage", "https://www.startpage.com/sp/search?query={query}"),
    Custom("custom"),
}

data class SearchSettings(
    val enabled: Boolean = true,
    val suggestions: Boolean = true,
    val contacts: Boolean = false,
    val fuzzy: Boolean = true,
    val internet: Boolean = false,
    val hiddenApps: Boolean = true,
    val recentAppKeys: List<String> = emptyList(),
    val engine: SearchEngine = SearchEngine.Google,
    val customEngineName: String = "",
    val customEngineUrl: String = "",
) {
    fun recordApp(key: String) = copy(recentAppKeys = (listOf(key) + recentAppKeys).distinct().take(5))

    fun encode(): String = JSONObject().put("enabled", enabled).put("suggestions", suggestions)
        .put("contacts", contacts).put("fuzzy", fuzzy).put("internet", internet).put("hiddenApps", hiddenApps)
        .put("recentApps", JSONArray(recentAppKeys.distinct().take(5)))
        .put("engine", engine.id).put("customEngineName", customEngineName).put("customEngineUrl", customEngineUrl).toString()

    companion object {
        fun decode(value: String?): SearchSettings = runCatching {
            if (value == null) return SearchSettings()
            val json = JSONObject(value)
            val recent = json.optJSONArray("recentApps") ?: JSONArray()
            SearchSettings(
                enabled = json.optBoolean("enabled", true), suggestions = json.optBoolean("suggestions", true),
                contacts = json.optBoolean("contacts", false), fuzzy = json.optBoolean("fuzzy", true),
                internet = json.optBoolean("internet", false), hiddenApps = json.optBoolean("hiddenApps", true),
                recentAppKeys = (0 until recent.length()).mapNotNull { (recent.opt(it) as? String)?.takeIf(String::isNotBlank) }.distinct().take(5),
                engine = SearchEngine.entries.firstOrNull { it.id == json.optString("engine") } ?: SearchEngine.Google,
                customEngineName = json.optString("customEngineName", ""),
                customEngineUrl = json.optString("customEngineUrl", ""),
            )
        }.getOrDefault(SearchSettings())
    }
}
