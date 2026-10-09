/*
 * P4 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/overlays/LauncherSheets.kt
 *   与 ui/overlays/HomeWidgetSheet.kt。
 *
 * 保留：LauncherOverlay（裁剪 sealed）/ LauncherPanelTheme / panelWindowHeight /
 *   PanelAction / LauncherOverlays 分发 / HomeWidgetSheet。
 *
 * 裁剪说明（相对原版）：
 *   - LauncherOverlay 只保留 P4 面：Shortcuts / HomeWidgetMenu / CustomWidgetMenu；
 *     其余（AppDetails/IconDesigner/Folder/Agenda/Settings/Search…）随 C 组设置
 *     全家桶与 P6 逐步恢复
 *   - 删 AppDetailsSheet/CategoryPicker/CategoryAppsSheet/TextEntryDialog/
 *     FolderPlacementDialog/DeleteFolderDialog（依赖 LauncherActions 全家/设置页）
 *   - 【实现替换】原版 ModalBottomSheet 底层是 ModalBottomSheetPopup（独立窗口），
 *     Compose 1.12 会把该窗口子树的 LocalContext 重置为宿主 Context —— hotfix2
 *     实锤的资源错位路径（stringResource 解析错值）。HomeWidgetSheet 在本模块
 *     重实现为【同窗口底部面板】（scrim + 圆角 Surface，视觉对齐原版 sheet），
 *     LocalContext 模块覆盖正常穿透，stringResource 可用。
 *   - 非 custom 菜单删 Calendar/Weather/Media/Battery（P6）与编辑收藏全屏页
 *     （ItemSelectionScreen 随 C 组落地）；时钟样式接 cycleClockStyle。
 */
package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R // P4：模块 namespace，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import android.content.ComponentName
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.ui.GracePopupActions
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol

sealed interface LauncherOverlay {
    data class Shortcuts(val app: LauncherApp, val anchor: Rect, val reveal: ShortcutRevealState = ShortcutRevealState()) : LauncherOverlay
    data object HomeWidgetMenu : LauncherOverlay
    data object CustomWidgetMenu : LauncherOverlay
    // P6（B6.2/B6.4）：日期点击 → 日程+天气面板；时钟长按面板 → 图标包选择
    data object Agenda : LauncherOverlay
    data object IconPacks : LauncherOverlay
    // hotfix8：编辑收藏全屏页（原版 LauncherOverlay.Favorites；原为 P4 裁剪，C 组页已就位）
    data object Favorites : LauncherOverlay
    // folders 阶段：文件夹弹层（原版 LauncherOverlay.Folder；成员在路由层解析）
    data class Folder(val folder: com.galaxyrio.gracelauncher.data.LauncherFolder,
        val anchor: Rect, val reveal: ShortcutRevealState = ShortcutRevealState()) : LauncherOverlay
}

@Composable
internal fun LauncherPanelTheme(content: @Composable () -> Unit) {
    // Panels share the chosen dynamic/custom scheme and dark mode with settings.
    content()
}

@Composable
internal fun panelWindowHeight(): Dp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }

/**
 * P6 弹层统一分发（P4 面基础上扩展 Agenda/IconPacks）。全部子层为同窗口实现。
 *
 * @param menuEntriesProvider P4 适配：ShortcutPopup 尾部附加行（收藏增删），
 *   label 必须由调用方主树预解析后返回。
 * @param widgetMenuExtras P6：HomeWidgetMenu（non-custom）面板尾部开关行组。
 */
@Composable
fun LauncherOverlays(
    overlay: LauncherOverlay?,
    layout: HomeLayout,
    apps: List<LauncherApp>,
    actions: GracePopupActions,
    hasShortcutAccess: Boolean,
    menuEntriesProvider: (LauncherApp) -> List<PopupEntry> = { emptyList() },
    onChange: (LauncherOverlay?) -> Unit,
    widgetMenuExtras: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null,
    agenda: (@Composable () -> Unit)? = null,
    iconPacks: (@Composable () -> Unit)? = null,
    // hotfix8：编辑收藏全屏页 slot（路由层以 GraceFavoritesShim 组装）
    favorites: (@Composable () -> Unit)? = null,
    // folders 阶段：文件夹弹层 slot（路由层组装 FolderPopup）
    folder: (@Composable () -> Unit)? = null,
) {
    if (overlay == null) return
    when (overlay) {
        is LauncherOverlay.Shortcuts -> ShortcutPopup(
            app = overlay.app, anchor = overlay.anchor, hasAccess = hasShortcutAccess,
            actions = actions, onLaunchApp = { onChange(null) }, onDismiss = { onChange(null) },
            reveal = overlay.reveal,
            menuEntries = menuEntriesProvider(overlay.app),
        )
        LauncherOverlay.HomeWidgetMenu -> HomeWidgetPanel(layout, apps, actions, custom = false,
            onDismiss = { onChange(null) }, onNavigate = onChange, p6 = widgetMenuExtras)
        LauncherOverlay.CustomWidgetMenu -> HomeWidgetPanel(layout, apps, actions, custom = true,
            onDismiss = { onChange(null) }, onNavigate = onChange)
        // P6：面板内容由路由层注入（AgendaSheet/IconPackPanel 裁剪版），分发只管挂载
        LauncherOverlay.Agenda -> agenda?.invoke()
        LauncherOverlay.IconPacks -> iconPacks?.invoke()
        // hotfix8：编辑收藏（原版 LauncherSheets.kt:115-118 同窗口全屏分发）
        LauncherOverlay.Favorites -> favorites?.invoke()
        // folders 阶段：文件夹弹层（原版 LauncherSheets.kt:164-197）
        is LauncherOverlay.Folder -> folder?.invoke()
    }
}

/**
 * HomeWidgetSheet 的同窗口重实现（scrim + 底部圆角面板）。
 * custom=false：时钟长按（HomeWidgetMenu）；custom=true：widget 长按（CustomWidgetMenu）。
 * P6：non-custom 面板尾部注入附属特性开关行组（p6 slot，由路由层组装）。
 * hotfix8：onNavigate = 面板内导航（编辑收藏），对齐原版 onChange(LauncherOverlay.Favorites)。
 */
@Composable
internal fun HomeWidgetPanel(layout: HomeLayout, apps: List<LauncherApp>, actions: GracePopupActions, custom: Boolean, onDismiss: () -> Unit,
                             onNavigate: (LauncherOverlay) -> Unit = {},
                             p6: (@Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val component = layout.widgetProvider?.let(ComponentName::unflattenFromString)
    val widgetApp = component?.let { provider ->
        apps.firstOrNull { it.packageName == provider.packageName }
            ?: LauncherApp(provider, layout.widgetLabel ?: provider.packageName, null)
    }
    Box(Modifier.fillMaxSize().testTag("launcher_sheet")) {
        // Scrim（原版 scrimColor Black 60%）
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onDismiss).clearAndSetSemantics {})
        // 面板：贴底、顶部圆角 22dp 对齐原版 ModalBottomSheet；高度限内容
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = panelWindowHeight() * 0.9f).testTag("home_widget_panel"),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)) {
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (custom && widgetApp != null) { AppIcon(widgetApp, size = 34.dp); Spacer(Modifier.width(20.dp)) }
                    Text(if (custom) layout.widgetLabel ?: stringResource(R.string.widget_home_menu) else stringResource(R.string.widget_home_menu),
                        fontSize = 25.sp, fontWeight = FontWeight.Medium)
                }
                if (custom) {
                    // hotfix8：原版 HomeWidgetSheet.kt:46 —— custom 面板首行 = 编辑收藏
                    WidgetAction(LauncherSymbol.Star, stringResource(R.string.edit_favorites)) {
                        onDismiss(); onNavigate(LauncherOverlay.Favorites)
                    }
                    if (widgetApp != null) WidgetAction(LauncherSymbol.Info, stringResource(R.string.app_info)) { onDismiss(); actions.appInfo(widgetApp) }
                    WidgetAction(LauncherSymbol.Resize, stringResource(R.string.widget_move_resize)) { onDismiss(); actions.moveWidget() }
                    WidgetAction(LauncherSymbol.Edit, stringResource(R.string.widget_configuration)) { onDismiss(); actions.configureWidget() }
                    WidgetAction(LauncherSymbol.Plus, stringResource(R.string.widget_add)) { onDismiss(); actions.addWidget() }
                    WidgetAction(LauncherSymbol.Delete, stringResource(R.string.widget_remove)) { onDismiss(); actions.removeWidget() }
                } else {
                    // C 组（C4）：时钟样式选择器 —— 原为 cycleClockLayout 演示入口
                    // （循环切 layout），现直达模块设置页 ClockStyle 面板（完整样式编辑）
                    WidgetAction(LauncherSymbol.Clock, stringResource(R.string.settings_clock_style)) {
                        onDismiss(); openGraceSettingsInModule(context, page = "ClockStyle")
                    }
                    WidgetAction(LauncherSymbol.Plus, stringResource(R.string.widget_add)) { onDismiss(); actions.addWidget() }
                    WidgetAction(LauncherSymbol.Move, stringResource(R.string.settings_move_widget)) { onDismiss(); actions.moveWidget() }
                    // P6（B6.4）：图标包选择入口
                    WidgetAction(LauncherSymbol.Apps, stringResource(R.string.settings_icon_pack),
                        summary = stringResource(R.string.settings_icon_pack_summary)) { onDismiss(); actions.openIconPacks() }
                    // P6（B6.x）：附属特性开关行组（原版 WidgetToggle 语义，Route 组装）
                    p6?.invoke(this@Column)
                }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 8.dp),
                    thickness = 1.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                // hotfix8：原版 HomeWidgetSheet.kt:69 —— non-custom 面板 divider 后 = 编辑收藏
                WidgetAction(LauncherSymbol.Star, stringResource(R.string.edit_favorites), arrow = true) {
                    onDismiss(); onNavigate(LauncherOverlay.Favorites)
                }
                // C 组（C1）："Grace settings" 行接通 —— 经模块 App 主入口（LAUNCHER，
                // P6 hotfix3 实锤的 BAL 可行路径）转发到模块进程 SettingsActivity
                WidgetAction(LauncherSymbol.Settings, stringResource(R.string.grace_settings), arrow = false) {
                    onDismiss()
                    openGraceSettingsInModule(context)
                }
            }
        }
    }
}

/** 原版 HomeWidgetSheet.WidgetToggle 逐字：整行 toggleable + Switch。 */
@Composable
internal fun WidgetToggle(label: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
        .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (summary != null) Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun WidgetAction(symbol: LauncherSymbol, label: String, summary: String? = null, arrow: Boolean = false, onClick: () -> Unit) {
    PanelAction(symbol, label, iconColumnWidth = 34.dp, iconTextSpacing = 20.dp, summary = summary,
        trailing = if (arrow) ({ Icon(painterResource(R.drawable.ms_expand_more), null, Modifier.rotate(-90f).size(24.dp)) }) else null,
        onClick = onClick)
}

/** C 组（C1）：宿主 → 模块设置页入口（显式组件 + NEW_TASK；模块 MainActivity route 转发）。 */
internal fun openGraceSettingsInModule(context: android.content.Context, page: String? = null) {
    runCatching {
        context.startActivity(android.content.Intent()
            .setClassName(com.homemode4miui.bridge.GraceBridge.PACKAGE_MODULE,
                "com.homemode4miui.MainActivity")
            .putExtra("route", "grace_settings")
            .apply { page?.let { putExtra("page", it) } }
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { com.homemode4miui.util.ModLog.e("openGraceSettingsInModule failed", it) }
}

@Composable
internal fun PanelAction(
    symbol: LauncherSymbol,
    label: String,
    tag: String = label,
    iconColumnWidth: Dp = 23.dp,
    iconTextSpacing: Dp = 23.dp,
    summary: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(iconColumnWidth), contentAlignment = Alignment.Center) {
            LauncherIcon(symbol, Modifier.size(23.dp).testTag("$tag:icon"))
        }
        Spacer(Modifier.width(iconTextSpacing))
        Column(Modifier.weight(1f)) {
            Text(label, Modifier.testTag("$tag:label"), fontSize = 16.sp, letterSpacing = 0.2.sp)
            if (summary != null) Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (trailing != null) trailing()
    }
}
