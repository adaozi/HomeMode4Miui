package com.galaxyrio.gracelauncher.data

import org.json.JSONArray
import org.json.JSONObject

const val PrivateSpaceFolderId = "private-space"
const val PrivateSpaceFolderKey = "folder:$PrivateSpaceFolderId"
const val PrivateSpaceDefaultName = "private"
const val WorkProfileFolderId = "work-profile"
const val WorkProfileFolderKey = "folder:$WorkProfileFolderId"
const val WorkProfileDefaultName = "work"

enum class PrivateSpaceDisplay { List, Folder, NormalApp }

/** Shared presentation. Security fields are used only by Private Space, never Work Profile. */
data class ProfileSettings(
    val enabled: Boolean = true,
    val passwordProtected: Boolean = true,
    val showIndicator: Boolean = true,
    val lockImmediately: Boolean = true,
    val display: PrivateSpaceDisplay = PrivateSpaceDisplay.Folder,
    val name: String = PrivateSpaceDefaultName,
    val appOrder: List<String> = emptyList(),
) {
    val protectsApps: Boolean get() = display != PrivateSpaceDisplay.NormalApp && passwordProtected
    val exposesApps: Boolean get() = enabled && !protectsApps
    val locksOnExit: Boolean get() = enabled && display != PrivateSpaceDisplay.NormalApp && lockImmediately
    fun folder(apps: List<LauncherApp> = emptyList(), id: String = PrivateSpaceFolderId) = LauncherFolder(
        id, name, orderedApps(apps).map { it.key }, FolderPlacement.AppList,
    )

    fun orderedApps(apps: List<LauncherApp>): List<LauncherApp> {
        val byKey = apps.associateBy(LauncherApp::key)
        return appOrder.distinct().mapNotNull(byKey::get) + apps.sortedWith(LauncherAppOrder).filterNot { it.key in appOrder }
    }

    fun encode(): String = JSONObject().put("enabled", enabled).put("passwordProtected", passwordProtected)
        .put("showIndicator", showIndicator).put("lockImmediately", lockImmediately)
        .put("display", display.name).put("name", name).put("appOrder", JSONArray(appOrder.distinct())).toString()

    companion object {
        fun workDefaults() = ProfileSettings(passwordProtected = false, lockImmediately = false, name = WorkProfileDefaultName)

        fun decode(json: String?, defaults: ProfileSettings = ProfileSettings()): ProfileSettings = runCatching {
            val value = JSONObject(json ?: return defaults)
            val order = value.optJSONArray("appOrder") ?: JSONArray()
            ProfileSettings(
                enabled = value.optBoolean("enabled", true),
                passwordProtected = value.optBoolean("passwordProtected", defaults.passwordProtected),
                showIndicator = value.optBoolean("showIndicator", true),
                lockImmediately = value.optBoolean("lockImmediately", defaults.lockImmediately),
                display = PrivateSpaceDisplay.entries.firstOrNull { it.name == value.optString("display") } ?: PrivateSpaceDisplay.Folder,
                name = value.optString("name", defaults.name).trim().ifBlank { defaults.name },
                appOrder = (0 until order.length()).mapNotNull { (order.opt(it) as? String)?.takeIf(String::isNotBlank) }.distinct(),
            )
        }.getOrDefault(defaults)
    }
}
