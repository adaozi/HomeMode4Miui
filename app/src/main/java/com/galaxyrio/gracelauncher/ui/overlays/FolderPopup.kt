/*
 * folders 阶段 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：ui/overlays/ShortcutPopup.kt 的 FolderPopup（:61-71）+ ShortcutPopup 文件夹分支
 * （:91-96 isFolder 判定 / :108-109 panelTag·title / :117-118 header click→onEdit /
 * :126 members 列表 / :136-148 空态 folder_contents_empty + 编辑按钮）。
 *
 * 模块适配：原版 FolderPopup 是 ShortcutPopup 的薄包装（uiState.popups 提供成员），
 * 模块 ShortcutPopup 为 P4 裁剪版（GracePopupActions/menuEntries 适配，无 uiState），
 * 故按原版文件夹分支语义独立成文件，复用同一 SwipeRevealPanel（同窗口 morph）。
 * 降级登记：成员图标不做 ItemIcon 装饰（原版经 uiState.folderItem 走 itemIcons，
 * 模块 folders 弹层暂走原始应用图标，图标设计器对文件夹成员的覆盖随后续阶段）。
 */
package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R // 模块 namespace R 类
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherFolder
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.widgets.HomeWidget
import com.galaxyrio.gracelauncher.ui.widgets.rememberHostedWidget

/** 原版 FolderPopup 语义（ShortcutPopup.kt:59 注释逐字）：文件夹弹层与快捷方式弹层共用 UI，仅 owner 与初始内容不同。 */
@Composable
fun FolderPopup(
    folder: LauncherFolder,
    apps: List<LauncherApp>,
    anchor: Rect,
    onLaunchApp: (LauncherApp, Rect) -> Unit,
    onDismiss: () -> Unit,
    reveal: ShortcutRevealState = remember { ShortcutRevealState() },
    onEdit: (() -> Unit)? = null,
    onDetails: (LauncherApp) -> Unit = {},
    // hotfix folders2：弹层 widget 项（原版 ShortcutPopup entry.widget 分支 ShortcutPopup.kt:172-182）。
    // 降级登记：widget 行排成员尾部（原版按 popup JSON 顺序与 app 成员交错）。
    widgets: List<HomeLayout> = emptyList(),
) {
    val members = remember(folder.appKeys, apps) { folder.appKeys.mapNotNull { key -> apps.firstOrNull { it.key == key } } }
    SwipeRevealPanel(
        anchor = anchor,
        reveal = reveal,
        panelTag = "folder_popup",
        title = folder.name,
        onDismiss = onDismiss,
    ) { maxListHeight ->
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("shortcut_header")
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    enabled = onEdit != null,
                    onLongClick = { onDetails(folder.asApp()) },
                    onClick = { if (onEdit != null) onEdit() },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LauncherIcon(LauncherSymbol.Outbound, Modifier.size(19.dp))
            Spacer(Modifier.width(10.dp))
            Text(folder.name, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.heightIn(max = maxListHeight).testTag("folder_members")) {
            if (members.isEmpty()) item {
                Text(stringResource(R.string.folder_contents_empty),
                    Modifier.testTag("folder_empty").padding(horizontal = 12.dp, vertical = 18.dp),
                    style = MaterialTheme.typography.bodyMedium)
                if (onEdit != null) {
                    TextButton(onClick = onEdit, modifier = Modifier.testTag("folder_edit")) {
                        Text(stringResource(R.string.settings_folder_edit))
                    }
                }
            }
            items(members, key = { "entry:${it.key}" }) { itemApp ->
                var iconBounds by remember { mutableStateOf(Rect.Zero) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("entry:${itemApp.key}")
                        .clip(RoundedCornerShape(16.dp))
                        .combinedClickable(enabled = !reveal.dragging && reveal.expanded, onClick = {
                            onLaunchApp(itemApp, iconBounds)
                        }).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(itemApp, Modifier.onGloballyPositioned { iconBounds = it.boundsInWindow() }, size = 36.dp)
                    Spacer(Modifier.width(22.dp))
                    Text(itemApp.label, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            // hotfix folders2：弹层 widget 项（原版 ShortcutPopup.kt:172-182 逐字：
            // rememberHostedWidget + HomeWidget，editing=false，长按进编辑）
            items(widgets, key = { "widget:${it.widgetId}" }) { layout ->
                val hosted = rememberHostedWidget(layout)
                Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    HomeWidget(layout, hosted, hosted.defaultHeight, editing = false,
                        enabled = !reveal.dragging && reveal.expanded, hapticsEnabled = true,
                        onLongPress = { onEdit?.invoke() })
                }
            }
        }
    }
}
