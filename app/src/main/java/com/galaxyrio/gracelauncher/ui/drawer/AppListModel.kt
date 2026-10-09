/*
 * P2 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/drawer/AppListModel.kt
 *
 * 裁剪说明（恢复节点见 PORTING_GraceLauncher.md B2.1）：
 *   - folders 阶段：恢复 LauncherFolder / inlineFolders / drawerFolders / DrawerItem.Folder
 *     分支（原版逐字；抽屉文件夹两种位置语义：按名内联排序 or 底部 ◇ 区）
 *   - 仍删 Private Space（privateFolder/privateApps，P6 Private Space 阶段）
 *   - 仍删 recentlyInstalledFolder（后续阶段）
 *   - 保留：LauncherAppOrder 排序 + section 分组 + 字母表顺序（含非字母 section 与 '#'）
 *   - 保留 FolderSection/GraceSection 常量（AlphabetRail 引用 GraceSection；P3 恢复分支）
 */
package com.galaxyrio.gracelauncher.ui.drawer

import com.galaxyrio.gracelauncher.data.LauncherAlphabet
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherAppOrder
import com.galaxyrio.gracelauncher.data.LauncherFolder

const val FolderSection = "◇"
const val GraceSection = "grace-launcher"

sealed interface DrawerItem {
    val key: String
    val section: String

    data class Header(override val section: String) : DrawerItem {
        override val key = "header:$section"
    }

    data class App(val app: LauncherApp, override val section: String = app.section) : DrawerItem {
        override val key = app.key
    }
    data class Folder(val folder: LauncherFolder, override val section: String = FolderSection) : DrawerItem {
        override val key = "folder:${folder.id}"
    }
}

class AppListModel(apps: List<LauncherApp>, folders: List<LauncherFolder> = emptyList()) {
    private val inlineFolders = folders.filter { it.placement.inAppList && !it.appListAtBottom }.associateBy(LauncherFolder::key)
    // Use exactly the same name/pinyin ordering and alphabet sections as apps.
    private val grouped = (apps + inlineFolders.values.map(LauncherFolder::asApp))
        .sortedWith(LauncherAppOrder).groupBy(LauncherApp::section)
    private val appLetters = LauncherAlphabet.filter { it != "#" && it in grouped } +
        grouped.keys.filter { it !in LauncherAlphabet }.sorted() +
        if ("#" in grouped) listOf("#") else emptyList()
    private val drawerFolders = folders.filter { it.placement.inAppList && it.appListAtBottom }
    val items: List<DrawerItem> = buildList {
        appLetters.forEach { letter ->
            add(DrawerItem.Header(letter))
            grouped.getValue(letter).forEach { app ->
                val folder = inlineFolders[app.key]
                add(if (folder == null) DrawerItem.App(app, letter) else DrawerItem.Folder(folder, letter))
            }
        }
        if (drawerFolders.isNotEmpty()) {
            add(DrawerItem.Header(FolderSection))
            drawerFolders.forEach { add(DrawerItem.Folder(it)) }
        }
    }
    // Keep the rail in sync with every section, including launcher utilities.
    val letters: List<String> = items.filterIsInstance<DrawerItem.Header>().map { it.section }
    private val sectionIndices = items.mapIndexedNotNull { index, item ->
        (item as? DrawerItem.Header)?.let { it.section to index }
    }.toMap()

    /** Every index refers to the same complete list, even while other groups are hidden. */
    fun indexOfSection(letter: String): Int =
        sectionIndices[letter] ?: 0
}
