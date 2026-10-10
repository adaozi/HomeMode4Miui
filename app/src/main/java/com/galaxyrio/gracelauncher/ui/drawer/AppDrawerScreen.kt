/*
 * P2 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/drawer/AppDrawerScreen.kt
 *
 * 裁剪说明（恢复节点见 PORTING_GraceLauncher.md B2.1）：
 *   - folders 阶段：恢复 DrawerItem.Folder 行分支（FolderRow）+ onOpenFolder/onEditFolder
 *     回调（原版逐字；reveal 拖拽 morph 参数保留默认 no-op，弹层同窗口实现）
 *   - P6（B6.3）：恢复 notifications 参数（原版 AppDrawerScreen.kt:66/152 逐字：
 *     行内通知预览 label+age+正文+箭头，渲染分支在 LauncherRow）；
 *     folderApps / highlightedAppKey 仍待对应阶段
 *   - P4 升级：恢复 onAppShortcuts（行右滑 → ShortcutPopup reveal morph，原版
 *     soul 交互）+ rowGestures（拖拽 reveal/launchAt）；删 P2 临时 DropdownMenu
 *     长按菜单（收藏增删入口迁入弹层 menuEntries，长按保持原版应用信息语义）
 *   - 保留 retainItemSpace（字母 scrub 期间隐藏其他组不改滚动几何）
 *   - scrubfix：retainItemSpace 拆双参 —— composedVisible 仍按组合期值清语义
 *     （一帧滞后仅影响 TalkBack，不可见）；liveVisible 改为 placement 块内实时
 *     读 selectedLetterState 快照（snapshot-observed，Modifier.offset{} 同款
 *     重放置观察语义）：即使重组因任何原因滞后一帧，隐藏/显示也在当帧 layout
 *     生效 —— 快速滑字母条时旧字母行残留的第二道防线（第一道 =
 *     GraceHomeView.createRecomposer 换 AndroidUiDispatcher，重组先于 traversal）
 */
package com.galaxyrio.gracelauncher.ui.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R // P2：模块 namespace 为 com.homemode4miui，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.data.ShortcutsFoldersSettings
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.galaxyrio.gracelauncher.ui.components.AppRowGestures
import com.galaxyrio.gracelauncher.ui.components.FolderRow
import com.galaxyrio.gracelauncher.ui.components.LauncherAppRow
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.LocalListAppearance
import com.galaxyrio.gracelauncher.ui.components.startPadding
import com.galaxyrio.gracelauncher.ui.components.endPadding
import com.galaxyrio.gracelauncher.ui.components.listLabelStyle
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance

@Composable
fun AppDrawerScreen(
    model: AppListModel,
    listState: LazyListState,
    selectedLetter: String?,
    // scrubfix：与 selectedLetter 同源的原始 state —— placement 块实时读取用。
    // 不能用 rememberUpdatedState 包装（那是组合期写入，同样滞后一帧）。
    selectedLetterState: State<String?>,
    topSpace: Dp,
    onLaunchApp: (LauncherApp) -> Unit,
    onAppDetails: (LauncherApp) -> Unit,
    onAppShortcuts: (LauncherApp, Rect) -> Unit,
    rowGestures: AppRowGestures = AppRowGestures(),
    modifier: Modifier = Modifier,
    onOpenFolder: (LauncherFolder, Rect) -> Unit = { _, _ -> },
    onEditFolder: (LauncherFolder) -> Unit = {},
    // P6（B6.3）：行内通知预览（快照桥下发；空 map = 无通知/监听未授权，行自然无预览）
    notifications: Map<String, List<AppNotification>> = emptyMap(),
    // 上游同步 v1.1.8：ShortcutsFoldersSettings（通知显示策略；swipeLeftToOpenFirst 随 S3/S7）
    popupSettings: ShortcutsFoldersSettings = ShortcutsFoldersSettings(),
) {
    val appearance = LocalLauncherAppearance.current
    // 上游同步 v1.1.8：列表外观（边距/字号/名字可见性）+ 行标签样式
    val listAppearance = LocalListAppearance.current
    val labelStyle = listLabelStyle()
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag("app_drawer"),
        // Scrollable leading space, not padding on the viewport: earlier groups
        // may occupy this area after jumping to a later letter. The small, real
        // bottom inset lets LazyColumn naturally clamp sections near the end
        // without clipping scrolling rows above the navigation bar.
        contentPadding = PaddingValues(start = listAppearance.startPadding, end = listAppearance.endPadding, top = topSpace, bottom = 24.dp),
        userScrollEnabled = selectedLetter == null,
    ) {
        items(
            items = model.items,
            key = DrawerItem::key,
            contentType = { when (it) { is DrawerItem.Header -> "header"; is DrawerItem.App -> "app"; is DrawerItem.Folder -> "folder" } },
        ) { item ->
            // Keep every item's key and measured height. Hiding a group must
            // not change scroll bounds or move the selected header on release.
            Box(
                Modifier.retainItemSpace(
                    composedVisible = selectedLetter == null || item.section == selectedLetter,
                    liveVisible = { selectedLetterState.value == null || item.section == selectedLetterState.value },
                )
            ) {
                when (item) {
                    is DrawerItem.Header -> Text(
                        text = when (item.section) {
                            GraceSection -> stringResource(R.string.app_name)
                            else -> item.section
                        },
                        modifier = Modifier
                            .height(44.dp)
                            .padding(start = LauncherLayout.ContentInset, end = LauncherLayout.ContentInset, top = 12.dp)
                            .testTag("section:${item.section}"),
                        color = labelStyle.color,
                        style = TextStyle(
                            fontFamily = labelStyle.fontFamily,
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.Normal,
                            shadow = appearance.textShadow,
                        ),
                    )
                    is DrawerItem.App -> LauncherAppRow(
                        app = item.app,
                        onClick = { onLaunchApp(item.app) },
                        onLongClick = { onAppDetails(item.app) },
                        onSwipeRight = { onAppShortcuts(item.app, it) },
                        gestures = rowGestures,
                        // 上游同步 v1.1.8：名字可见性 + 通知按 shortcutsFolders 策略过滤
                        showLabel = listAppearance.showNames(favorites = false),
                        notification = notifications[item.app.packageName].orEmpty()
                            .firstOrNull { popupSettings.showsNotification(it.silent, expanded = false) }
                            .takeUnless { item.app.user != null },
                    )
                    is DrawerItem.Folder -> FolderRow(
                        folder = item.folder,
                        onOpen = { onOpenFolder(item.folder, it) },
                        onLongClick = { onEditFolder(item.folder) },
                        // 上游同步 v1.1.8 S7：抽屉文件夹行左滑打开内部第一项
                        onSwipeLeft = rowGestures.onOpenFirst?.let { open -> { bounds ->
                            open(item.folder.asApp(), bounds)
                        } },
                    )
                }
            }
        }
    }
}

private fun Modifier.retainItemSpace(
    composedVisible: Boolean,
    liveVisible: () -> Boolean,
): Modifier =
    // Unlike alpha=0, not placing a child also removes its invisible hit targets.
    // Clear descendants from TalkBack while retaining the complete list geometry.
    // liveVisible 在 placement 块内求值并观察快照：状态变化只触发重放置（不重测量、
    // 不依赖重组时序），scrub 跳转与区段隐藏严格同帧。
    then(if (composedVisible) Modifier else Modifier.clearAndSetSemantics {}).layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            if (liveVisible()) placeable.placeRelative(0, 0)
        }
    }
