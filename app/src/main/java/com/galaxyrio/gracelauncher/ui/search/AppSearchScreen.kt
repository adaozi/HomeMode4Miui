/*
 * P2 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/search/AppSearchScreen.kt
 *
 * 裁剪说明（恢复节点见 PORTING_GraceLauncher.md B2.3）：
 *   - 删 SearchContacts / ContactAvatar（联系人索引，宿主进程权限模型不同，P6 评估恢复）
 *   - 删 SearchResult.Internet / SearchLauncher（外部搜索入口，P6 评估恢复）
 *   - 删 settings 按钮（SearchSettings 全家桶在模块 App 进程，C 组设置页落地后接回）
 *   - LauncherUiState/LauncherActions 聚合参数以显式小参数替代（P3 恢复聚合签名）
 *   - 保留：SearchQuery/SearchName 打分（拼音 initials/子序列/模糊匹配）、
 *     最近应用建议、行布局/首结果加粗圆点、IME 视口处理
 */
package com.galaxyrio.gracelauncher.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.ui.components.observeListGestures
import com.galaxyrio.gracelauncher.ui.components.rememberLauncherListGestureConnection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.homemode4miui.R // P2：模块 namespace 为 com.homemode4miui，R 类随之（原版为 com.galaxyrio.gracelauncher.R）
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherAppOrder
import com.galaxyrio.gracelauncher.data.SearchName
import com.galaxyrio.gracelauncher.data.SearchQuery
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.LauncherSearchBar
import com.galaxyrio.gracelauncher.ui.components.stableStatusBarInset
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance

// P2 裁剪：原版 SearchResult sealed interface（App/Contact/Internet）只剩 App 一种
// 形态，直接以 LauncherApp 承载；P6 恢复 Contact/Internet 时还原 sealed 结构。
private val searchResultKey: (LauncherApp) -> String = { "app:" + it.key }

@Composable
internal fun AppSearchScreen(
    apps: List<LauncherApp>,
    recentAppKeys: List<String>,
    fuzzy: Boolean,
    suggestions: Boolean,
    // P5：onLaunch 透传结果行 iconBounds（Compose Rect，可空）——搜索启动同享图标动画
    onLaunch: (LauncherApp, Rect?) -> Unit,
    onDetails: (LauncherApp) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    enterAlpha: Float = 1f,
    // 上游同步 v1.1.8 S3：下拉关闭灵敏度与桌面共享（上游经 uiState.settings 透传，
    // 本模块搜索屏为裁剪签名，改为显式参数由 Route 传入）
    swipeSensitivity: Int = 100,
    queryState: TextFieldState = rememberTextFieldState(),
) {
    val query = queryState.text.toString().trim()
    val keyboard = LocalSoftwareKeyboardController.current
    val appearance = LocalLauncherAppearance.current
    val names = remember(apps) { apps.associate {
        val label = SearchName(it.label)
        it.key to (label to if (it.originalLabel == it.label) label else SearchName(it.originalLabel))
    } }
    val results: List<LauncherApp> = remember(apps, names, query, fuzzy, suggestions, recentAppKeys) {
        if (query.isEmpty()) {
            if (suggestions) {
                val byKey = apps.associateBy { it.key }
                recentAppKeys.mapNotNull(byKey::get).distinctBy { it.key }.take(5)
            } else emptyList()
        } else {
            val term = SearchQuery(query, fuzzy)
            apps.mapNotNull { app ->
                val (label, original) = names.getValue(app.key)
                val score = listOfNotNull(label.score(term), original.score(term)).minOrNull() ?: return@mapNotNull null
                score to app
            }.sortedWith(compareBy<Pair<Int, LauncherApp>> { it.first }.thenComparator { a, b -> LauncherAppOrder.compare(a.second, b.second) })
                .map { it.second }
        }
    }
    fun open(app: LauncherApp, bounds: Rect? = null) {
        keyboard?.hide()
        onDismiss()
        onLaunch(app, bounds)
    }
    val list = rememberLazyListState()
    // 上游同步 v1.1.8 S3（v1.1.7 特性）：结果列表顶部下拉关闭搜索页；
    // 与桌面共享 Lab 的 swipeSensitivity/距离/释放速度阈值。零消费观察，不抢行内手势。
    val searchOverscroll = rememberOverscrollEffect()
    val searchScrollConnection = rememberLauncherListGestureConnection(
        searchOverscroll, list, swipeSensitivity,
        canTrigger = { it == GraceButtonGesture.SwipeDown },
        onGesture = { keyboard?.hide(); onDismiss() },
    )
    LaunchedEffect(query) { list.requestScrollToItem(0) }
    val density = LocalDensity.current
    var searchBarHeight by remember { mutableStateOf(56.dp) }
    val listTop = 12.dp + searchBarHeight / 2
    // The IME resizes the viewport; navigation-bar space stays inside the list
    // so rows can draw behind the bar and still scroll fully above it.
    val bottomInset = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    // Wallpaper tint and window blur are owned by the route, exactly as for the app list.
    Box(modifier.fillMaxSize().testTag("app_search")
        .nestedSearchGestures(searchScrollConnection)
        .graphicsLayer {
        alpha = enterAlpha
    }.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        .padding(top = stableStatusBarInset())
        .imePadding().padding(horizontal = 16.dp)) {
        // Clip at the field's widest part, where the overlaid pill conceals the
        // entire edge. Content padding preserves the first result's resting gap.
        LazyColumn(Modifier.fillMaxSize().padding(top = listTop).overscroll(searchOverscroll), state = list,
            contentPadding = PaddingValues(top = searchBarHeight / 2 + 16.dp, bottom = 24.dp + bottomInset)) {
            if (query.isNotEmpty() && results.isEmpty()) item {
                Text(stringResource(R.string.search_no_results), Modifier.padding(20.dp), color = appearance.text)
            }
            itemsIndexed(results, key = { _, app -> searchResultKey(app) }) { index, app ->
                val label = app.label
                SearchResultRow(label, searchResultKey(app), first = index == 0, onClick = { open(app, it) },
                    onLongClick = { keyboard?.hide(); onDetails(app) }) { iconModifier ->
                    AppIcon(app, iconModifier, size = 40.dp)
                }
            }
        }
        LauncherSearchBar(
            queryState, stringResource(R.string.settings_search), "app_search_query",
            modifier = Modifier.padding(top = 12.dp).onSizeChanged { searchBarHeight = with(density) { it.height.toDp() } },
            autoFocus = true,
            onSearch = { results.firstOrNull()?.let { open(it) } },
        )
    }
}

@Composable
private fun SearchResultRow(label: String, key: String, first: Boolean, onClick: (Rect) -> Unit,
    onLongClick: (() -> Unit)?, icon: @Composable (Modifier) -> Unit) {
    val appearance = LocalLauncherAppearance.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(Modifier.fillMaxWidth().heightIn(min = LauncherLayout.RowMinHeight).testTag(key)
        .clip(LauncherLayout.RowShape).combinedClickable(onClick = { onClick(bounds) }, onLongClick = onLongClick)
        .semantics { selected = first }.padding(LauncherLayout.ContentInset), verticalAlignment = Alignment.CenterVertically) {
        icon(Modifier.onGloballyPositioned { bounds = it.boundsInWindow() })
        Spacer(Modifier.width(LauncherLayout.IconLabelGap))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f, fill = false).testTag(key + ":label"), color = appearance.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (first) FontWeight.Bold else FontWeight.Normal, shadow = appearance.textShadow),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (first) Box(Modifier.padding(start = 8.dp).size(5.dp).background(appearance.text, CircleShape).testTag("search_first_result_dot"))
        }
    }
}

// 上游同步 v1.1.8 S3：搜索页手势观察挂点（上游在 LauncherScreen 组合；本模块收进本文件）。
// nestedScroll 供连接器读取边界剩余动量；observeListGestures 负责手势开始/取消的武装与解除。
private fun Modifier.nestedSearchGestures(connection: com.galaxyrio.gracelauncher.ui.components.LauncherListGestureConnection): Modifier =
    this.nestedScroll(connection).observeListGestures(connection)
