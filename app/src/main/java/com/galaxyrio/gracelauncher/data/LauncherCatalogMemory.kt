package com.galaxyrio.gracelauncher.data

import com.galaxyrio.gracelauncher.data.icons.IconPackStatus
import java.time.LocalDate

/** One rendered public catalog survives Activity/ViewModel replacement within this process. */
internal object LauncherCatalogMemory {
    data class Snapshot(
        val key: String,
        val entryKeys: Map<String, String>,
        val repositoryRevision: Long,
        val iconPackRevision: Long,
        val profiles: LauncherProfileSnapshot,
        val day: LocalDate,
        val shortcutAccess: Boolean,
        val entries: List<LauncherApp>,
        val iconPackStatus: IconPackStatus,
        val complete: Boolean,
    )

    @Volatile private var snapshot: Snapshot? = null

    fun read(key: String): Snapshot? = snapshot?.takeIf { it.key == key }

    fun publish(value: Snapshot) {
        snapshot = value.copy(entries = value.entries.filter {
            !it.isPrivateSpace && it.shortcut?.isPrivateSpace != true
        })
    }
}
