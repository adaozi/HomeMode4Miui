/*
 * P4 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/widgets/WidgetPicker.kt
 *
 * 裁剪说明（相对原版）：
 *   - SettingsScaffold（C 组设置体系）→ 同窗口自绘 scaffold（返回 + 标题 + 搜索）
 *   - LauncherUiState → apps: List<LauncherApp> 显式参数
 *   - 删 IconPackRepository / applyPrivateIconPacks（P6 图标包）
 *   - 【运行位置变更】原版在 WidgetSetupActivity（独立 Activity，模块 App 进程）。
 *     本模块不搬该 Activity：其 Room 写入落在模块 App 进程，与宿主进程的
 *     grace_launcher.db 不互通（进程数据目录隔离）→ picker 改为宿主进程内
 *     Compose 全屏浮层（对齐 AppSearchScreen 的同窗口 overlay 形态），bind/
 *     configure 链路在宿主进程完成（见 GraceHomeViewModel.addHomeWidget）。
 */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.widgets

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.pm.LauncherApps
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.homemode4miui.R // P4：模块 namespace，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.ui.components.LauncherSearchBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

private data class WidgetEntry(val info: AppWidgetProviderInfo, val label: String) {
    val key get() = info.provider.flattenToString() + ":" + info.profile
}

private data class WidgetAppGroup(
    val key: String, val packageName: String, val label: String,
    val app: LauncherApp, val widgets: List<WidgetEntry>,
)

@Composable
internal fun WidgetPicker(providers: List<AppWidgetProviderInfo>?, busy: Boolean, apps: List<LauncherApp>, onBack: () -> Unit, onSelect: (AppWidgetProviderInfo) -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    var expandedApps by rememberSaveable { mutableStateOf(listOf<String>()) }
    val groups by produceState<List<WidgetAppGroup>?>(null, providers, configuration, apps) {
        value = providers?.let { withContext(Dispatchers.IO) { loadWidgetGroups(context, it, apps) } }
    }
    val filtered = remember(groups, query) {
        val term = query.trim()
        groups.orEmpty().mapNotNull { app ->
            if (term.isEmpty() || app.label.contains(term, true) || app.packageName.contains(term, true)) app
            else app.widgets.filter { it.label.contains(term, true) }.takeIf { it.isNotEmpty() }?.let { app.copy(widgets = it) }
        }
    }
    val toggle: (String) -> Unit = { key -> expandedApps = if (key in expandedApps) expandedApps - key else expandedApps + key }
    // 同窗口 scaffold（原版 SettingsScaffold 形态：返回 + 大标题）
    Surface(Modifier.fillMaxSize().testTag("widget_picker"), color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("widget_picker_back")) {
                    Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.settings_back))
                }
                Text(stringResource(R.string.widget_choose), Modifier.weight(1f), fontSize = 25.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.fillMaxSize()) {
                LauncherSearchBar(
                    queryState, stringResource(R.string.widget_search), "widget_search_query",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), enabled = !busy && groups != null,
                )
                if (groups == null || busy) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    WidgetGroups(filtered, expandedApps, !busy, toggle, onSelect, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WidgetGroups(
    groups: List<WidgetAppGroup>, expandedApps: List<String>, enabled: Boolean,
    onToggle: (String) -> Unit, onSelect: (AppWidgetProviderInfo) -> Unit, modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (groups.isEmpty()) item {
            Text(stringResource(R.string.widget_none), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        groups.forEach { app ->
            val expanded = app.key in expandedApps
            item(key = "app:${app.key}", contentType = "app") {
                val angle by animateFloatAsState(if (expanded) 180f else 0f, MaterialTheme.motionScheme.fastEffectsSpec(), label = "widgetAppArrow")
                val expansion = stringResource(if (expanded) R.string.widget_group_expanded else R.string.widget_group_collapsed)
                ListItem(
                    onClick = { onToggle(app.key) }, enabled = enabled,
                    modifier = Modifier.animateItem().testTag("widget_app:${app.key}").semantics { stateDescription = expansion },
                    leadingContent = { WidgetAppIcon(app.app.icon) },
                    content = { Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(pluralStringResource(R.plurals.widget_count, app.widgets.size, app.widgets.size)) },
                    trailingContent = { Icon(painterResource(R.drawable.ms_expand_more), null, Modifier.rotate(angle)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            }
            if (expanded) items(app.widgets, key = { "preview:${it.key}" }, contentType = { "preview" }) { entry ->
                OutlinedCard(
                    onClick = { onSelect(entry.info) }, enabled = enabled,
                    modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth().testTag("widget_preview:${entry.key}"),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WidgetPreview(entry.info, app.app.icon, Modifier.fillMaxWidth().height(184.dp))
                        Text(entry.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WidgetAppIcon(icon: ImageBitmap?, modifier: Modifier = Modifier.size(44.dp)) {
    if (icon != null) Image(icon, contentDescription = null, modifier = modifier)
    else Icon(painterResource(R.drawable.ms_apps), null, modifier, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

private suspend fun loadWidgetGroups(context: Context, providers: List<AppWidgetProviderInfo>, apps: List<LauncherApp>): List<WidgetAppGroup> {
    val pm = context.packageManager
    val launcherApps = context.getSystemService(LauncherApps::class.java)
    val iconSize = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
    val collator = Collator.getInstance(context.resources.configuration.locales[0])
    return providers.groupBy { it.provider.packageName to it.profile }.map { (identity, widgets) ->
        val (packageName, profile) = identity
        val appInfo = runCatching { launcherApps.getApplicationInfo(packageName, 0, profile) }.getOrNull()
        val label = runCatching { appInfo?.loadLabel(pm)?.toString() }.getOrNull() ?: packageName
        val badgedLabel = pm.getUserBadgedLabel(label, profile).toString()
        val icon = runCatching {
            val drawable = appInfo?.loadIcon(pm) ?: widgets.first().loadIcon(context, context.resources.displayMetrics.densityDpi)
            pm.getUserBadgedIcon(drawable, profile).toBitmap(iconSize, iconSize).asImageBitmap()
        }.getOrNull()
        val entries = widgets.map { WidgetEntry(it, runCatching { it.loadLabel(pm) }.getOrDefault(label)) }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
        val app = apps.firstOrNull {
            it.shortcut == null && it.packageName == packageName && (it.user ?: android.os.Process.myUserHandle()) == profile
        } ?: LauncherApp(widgets.first().provider, badgedLabel, icon)
        WidgetAppGroup("$packageName:$profile", packageName, badgedLabel, app, entries)
    }.sortedWith { a, b -> collator.compare(a.label, b.label) }
}
