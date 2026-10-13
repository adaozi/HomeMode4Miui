/*
 * P4 路由 —— 升级自 P2 两态路由，裁剪自 GraceLauncher v1.1.4（GPL-3.0，
 * https://github.com/Galaxy-rio/GraceLauncher），参考 ui/LauncherScreen.kt（LauncherScreen）。
 *
 * P4 新增（PORTING_GraceLauncher.md B4.1–B4.3）：
 *   - rememberWidgetHost() + LocalWidgetHost provides（widget 生命周期随自托管
 *     LifecycleOwner：ON_START startListening / ON_STOP stopListening）
 *   - LauncherOverlay 弹层编排（ShortcutPopup reveal morph + HomeWidgetPanel），
 *     同窗口实现（见 HomeSheets.kt 替换说明），hotfix2 的 Popup 窗口规则不适用
 *   - AppRowGestures 接线：onPrepare 预取快捷方式 / onDrag 拖拽 reveal / onLaunchAt
 *     图标位启动（P5 前等同 onLaunchApp）
 *   - editingHome 编辑态（时钟长按菜单 → Move widget 进入；FAB 变完成键退出）；
 *     HomeScreen 接 onTopOffsetChange/onWidgetHeightChange 落 Room homeLayout
 *   - WidgetPicker 全屏浮层（原版 WidgetSetupActivity 裁剪，见 WidgetPicker.kt 头注）
 *   - Home 键回桌面复位扩展至 overlay/editingHome/picker
 *
 * 仍裁剪（对齐原版 LauncherScreen）：BackHandler/PredictiveBackHandler（宿主非
 *   ComponentActivity）；WallpaperBlur/dim/homeScrim（设置项随 C 组）；graceButton
 *   配置化（FAB 固定搜索/编辑完成双态）；LauncherActions 全家 → GracePopupActions。
 */
package com.homemode4miui.grace

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import android.app.Activity
import androidx.compose.ui.geometry.Rect
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import com.homemode4miui.R
import com.homemode4miui.bridge.GraceBridge
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.homemode4miui.grace.WallpaperEffectLayers
import com.homemode4miui.grace.rememberResolvedWallpaperTint
import com.homemode4miui.grace.rememberWallpaperBlurBitmap
import com.homemode4miui.grace.WindowWallpaperBlurEffect
import com.homemode4miui.util.ModLog
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherShortcut
import com.galaxyrio.gracelauncher.data.weather.WeatherStatus
import com.galaxyrio.gracelauncher.platform.AppLaunchTransition
import com.galaxyrio.gracelauncher.platform.LauncherAppTransitions
import com.galaxyrio.gracelauncher.platform.PrivateSpaceProbe
import com.galaxyrio.gracelauncher.ui.GracePopupActions
import com.galaxyrio.gracelauncher.ui.components.AlphabetRail
import com.galaxyrio.gracelauncher.ui.components.AppRowGestures
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.LocalAppTransitions
import com.galaxyrio.gracelauncher.ui.components.LocalHomeAnimationTarget
import com.galaxyrio.gracelauncher.ui.components.rememberLauncherSystemBars
import com.galaxyrio.gracelauncher.ui.components.stableStatusBarInset
import com.galaxyrio.gracelauncher.ui.components.statusBarContentFade
import com.galaxyrio.gracelauncher.ui.drawer.AppDrawerScreen
import com.galaxyrio.gracelauncher.ui.drawer.AppListModel
import com.galaxyrio.gracelauncher.ui.home.HomeScreen
import com.galaxyrio.gracelauncher.ui.overlays.AgendaIntents
import com.galaxyrio.gracelauncher.ui.overlays.AgendaSheet
import com.galaxyrio.gracelauncher.ui.overlays.FavoritesScreen
import com.galaxyrio.gracelauncher.ui.overlays.FolderPopup
import com.galaxyrio.gracelauncher.ui.overlays.IconPackPanel
import com.galaxyrio.gracelauncher.ui.overlays.LauncherOverlay
import com.galaxyrio.gracelauncher.ui.overlays.LauncherOverlays
import com.galaxyrio.gracelauncher.ui.overlays.openGraceSettingsInModule
import com.galaxyrio.gracelauncher.ui.overlays.PopupEntry
import com.galaxyrio.gracelauncher.ui.overlays.ShortcutRevealState
import com.galaxyrio.gracelauncher.ui.overlays.WidgetToggle
import com.galaxyrio.gracelauncher.ui.search.AppSearchScreen
import com.galaxyrio.gracelauncher.ui.theme.wallpaperTint
import com.galaxyrio.gracelauncher.ui.theme.rememberWallpaperBlurAvailable
import com.galaxyrio.gracelauncher.ui.widgets.LocalWidgetHost
import com.galaxyrio.gracelauncher.ui.widgets.WidgetPicker
import com.galaxyrio.gracelauncher.ui.widgets.rememberWidgetHost
import kotlinx.coroutines.launch

@Composable
fun GraceLauncherRoute(viewModel: GraceHomeViewModel, hostActivity: Activity? = null) {
    // hotfix9：activity-compose 1.13 的 BackHandler 组合期硬校验 ——
    // LocalNavigationEventDispatcherOwner / LocalOnBackPressedDispatcherOwner 双缺省
    // 直接 IllegalStateException（P2"宿主非 ComponentActivity BackHandler 不可用"的
    // 字节码级根因）。provide no-op owner 保证 BackHandler 可组合；宿主返回语义 =
    // Home 键复位，不经 dispatcher，注册的回调永不触发（见 HostBackPressedDispatcherOwner）。
    val hostLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val backDispatcherOwner = remember(hostLifecycleOwner) {
        HostBackPressedDispatcherOwner(hostLifecycleOwner.lifecycle)
    }
    // 上游同步 v1.1.8：LocalListAppearance 全局提供（行标签字号/字体/颜色、名字可见性；
    // 对齐上游 LauncherScreen 根 provider 位置）。
    val listAppearanceState = viewModel.uiState.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides backDispatcherOwner,
        com.galaxyrio.gracelauncher.ui.components.LocalListAppearance provides listAppearanceState.value.settings.listAppearance,
        // 上游同步 v1.1.8 S5：字体库全局提供（桌面文本回退链 + 设置页预览共用）
        com.galaxyrio.gracelauncher.ui.theme.LocalFontLibrary provides listAppearanceState.value.settings.fontLibrary
    ) {
        GraceLauncherRouteContent(viewModel, hostActivity)
    }
}

@Composable
private fun GraceLauncherRouteContent(viewModel: GraceHomeViewModel, hostActivity: Activity?) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val launchView = LocalView.current
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    // scrubfix：保留原始 MutableState —— AppDrawerScreen 的 placement 块要实时读它
    // （retainItemSpace liveVisible），重组滞后一帧也能当帧隐藏/显示。
    val selectedLetterState = remember { mutableStateOf<String?>(null) }
    var selectedLetter by selectedLetterState
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<LauncherOverlay?>(null) }
    // 上游同步 v1.1.8 S4：Grace Button 过渡状态机（原版 LauncherScreen.kt:519-606）
    var buttonTransition by remember { mutableStateOf<com.galaxyrio.gracelauncher.ui.components.GraceButtonTransition?>(null) }
    var instantButtonEntrance by remember { mutableStateOf(false) }
    val currentButtonTransition by androidx.compose.runtime.rememberUpdatedState(buttonTransition)
    val searchTransition = buttonTransition?.takeIf { it.action == com.galaxyrio.gracelauncher.data.GraceButtonAction.Search }
    val drawerTransition = buttonTransition?.takeIf { it.action == com.galaxyrio.gracelauncher.data.GraceButtonAction.AppList }
    var editingHome by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // folders 阶段（hotfix folders2）：非 null = 设置页 ADD_POPUP_WIDGET 请求的弹层 widget
    // picker（WidgetPicker 复用，onSelect 落 app_popups 而非 homeLayout）
    var popupPickerOwner by rememberSaveable { mutableStateOf<String?>(null) }
    // hotfix folders3：弹层 widget 添加成功后的回弹语义（原版同进程：WidgetSetupActivity
    // 结束后弹层仍开着、新 widget 即时可见）。popupWidgetReturn = 待回弹的 ownerKey；
    // lastFolderAnchor = 最近一次打开文件夹弹层的锚点（reveal morph 复位用）。
    var popupWidgetReturn by rememberSaveable { mutableStateOf<String?>(null) }
    var lastFolderAnchor by rememberSaveable { mutableStateOf<Rect?>(null) }
    val searchQuery = rememberTextFieldState()
    // P4：收藏增删弹层附加行（label 主树预解析；ShortcutPopup 为同窗口实现，
    // stringResource 本身安全，统一预解析保持与 P1/P2 文案注入同构）
    val removeFromFavoritesLabel = stringResource(R.string.remove_from_favorites)
    val addToFavoritesLabel = stringResource(R.string.add_to_favorites)
    // P6（B6.3）：通知附加行文案（主树预解析）
    val notificationOpenLabel = stringResource(R.string.notification_open)
    val notificationDismissLabel = stringResource(R.string.notification_dismiss)

    // hotfix7：抽屉字母列表 = appListApps（原版 LauncherViewModel 语义：隐藏应用只
    // 影响字母列表，favorites/其余表面用全量 apps）
    // 上游同步 v1.1.8 S8a：工作资料文件夹进抽屉（无 managed profile 时恒 null，抽屉零变化）
    var workExpanded by rememberSaveable { mutableStateOf(false) }
    val model = remember(state.appListApps, state.folders, state.workFolder, workExpanded, state.workProfileApps) {
        AppListModel(state.appListApps, state.folders, workFolder = state.workFolder,
            workExpanded = workExpanded, workApps = state.workProfileAppsOrdered)
    }
    val drawerState = rememberLazyListState()
    // 过渡生命周期：退后台清未决过渡（已提交锁屏保留黑场直至亮屏）；回前台清残留
    val routeLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(routeLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            val transition = currentButtonTransition ?: return@LifecycleEventObserver
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    transition.leftForeground = true
                    if (!transition.lockCommitted) buttonTransition = null
                }
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> if (transition.leftForeground) buttonTransition = null
                else -> Unit
            }
        }
        routeLifecycle.addObserver(observer)
        onDispose { routeLifecycle.removeObserver(observer) }
    }
    androidx.compose.runtime.LaunchedEffect(buttonTransition) {
        val transition = buttonTransition ?: return@LaunchedEffect
        transition.animate()
        if (transition.action != com.galaxyrio.gracelauncher.data.GraceButtonAction.LockScreen) {
            buttonTransition = null
        } else {
            // 先让 Compose 提交全黑末帧再请求锁屏，否则清屏瞬间漏出一帧亮 HOME 画面
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            if (!routeLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                buttonTransition = null
                return@LaunchedEffect
            }
            transition.lockCommitted = true
            // 模块适配：锁屏动作走 MIUI 原生 toggle 广播（逆向复刻）优先，root/无障碍兜底
            com.galaxyrio.gracelauncher.platform.GraceSystemActions.perform(
                context, com.galaxyrio.gracelauncher.data.GraceButtonTarget(
                    com.galaxyrio.gracelauncher.data.GraceButtonAction.LockScreen))
            // 部分 OEM 接受动作但不执行：1.5s 后若屏幕仍亮且未锁，撤遮罩并提示
            kotlinx.coroutines.delay(1_500)
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            if (!transition.leftForeground && pm?.isInteractive == true && km?.isKeyguardLocked == false) {
                buttonTransition = null
                android.widget.Toast.makeText(context, context.getString(
                    com.homemode4miui.R.string.grace_action_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    val searchAlpha by animateFloatAsState(if (searchOpen) 1f else 0f,
        if (instantButtonEntrance || searchTransition != null) androidx.compose.animation.core.snap() else tween(210), label = "searchFade")
    androidx.compose.runtime.LaunchedEffect(searchOpen, drawerOpen) {
        if (!searchOpen && !drawerOpen) instantButtonEntrance = false
    }
    val drawerAlpha by animateFloatAsState(
        targetValue = if (drawerOpen) 1f else 0f,
        animationSpec = tween(110),
        label = "appListFade",
    )
    val widgetHost = rememberWidgetHost()
    val widgetPickerBusy by viewModel.widgetPickerBusy.collectAsState()
    val widgetProviders by viewModel.widgetProviders.collectAsState()

    // ---- P6（B6.x）附属特性状态 ----
    val iconPacks by viewModel.iconPacks.collectAsState()
    val hostContext = LocalHostContext.current
    // B6.2：天气渲染判定（原版语义：开关开 + 设置加载完成 + 快照就绪）
    val weatherReady = state.settings.weatherEnabled &&
        state.weather.status == WeatherStatus.Ready && state.weather.snapshot?.current != null
    // B6.5：私密空间探测（懒触发即可，纯本地调用）
    val privateSpaceState = remember { PrivateSpaceProbe.probe(hostContext) }

    /**
     * hotfix3：通知监听授权引导 —— 打开【模块 App 主入口】（MainActivity），在模块
     * 自己的 Activity 里再进系统设置页。历史：
     *   hotfix1 Application+NEW_TASK → MIUI 上设置页秒退；
     *   hotfix2 宿主 Activity 同 task 启动 → startActivity 调用成功但页面被系统
     *   静默拦截（无异常抛出的 BAL 拦截，诊断日志实锤：resolved 正确仍不出现）；
     *   hotfix3 = 原版 GraceLauncher 架构：桌面启动其他应用主入口（LAUNCHER intent）
     *   是桌面本职工作永不被拦；模块前台 Activity 再开设置页是标准路径。
     */
    fun openMediaAccessSettings() {
        runCatching {
            val launch = hostContext.packageManager.getLaunchIntentForPackage(GraceBridge.PACKAGE_MODULE)
            if (launch != null) {
                (hostActivity ?: hostContext).startActivity(launch)
                Toast.makeText(context, R.string.media_access_module_hint, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, R.string.media_access_module_hint, Toast.LENGTH_LONG).show()
            }
        }.onFailure { error ->
            ModLog.e("openMediaAccessSettings failed", error)
            Toast.makeText(context, R.string.media_access_module_hint, Toast.LENGTH_LONG).show()
        }
    }

    // ---- P5（B5.1/B5.2）：启动动画 ----
    // 中枢持有宿主 Activity 引用 + 组合级协程作用域（onHomeDetached dispose composition
    // 时随组合取消，与自托管生命周期对齐）。capture 失败/无 Activity 时降级为
    // makeScaleUpAnimation（无 thumbnail）或纯 startMainActivity（bounds == null），
    // 启动本身永远可达成（砍功能不砍体验）。
    val scope = rememberCoroutineScope()
    val appTransitions = remember(hostActivity) { hostActivity?.let { LauncherAppTransitions(it, scope) } }

    // ---- hotfix3 → hotfix5：壁纸模糊 ----
    // 逐字对齐原版两处：LauncherScreen.kt:710-715（半径 = blurWallpaper ? radius.dp *
    // wallpaperEffectVisibility : 0）+ LauncherAppearance.kt:104-124（WallpaperBlur）。
    // hotfix5 根因：hotfix3 把 radius 的 **dp 数值直接当 px** 传给 setBackgroundBlurRadius
    // （默认 16px ≈ 3.6dp@440dpi），模糊强度被压到肉眼不可见 —— 原版 WallpaperBlur 内部
    // 先 with(LocalDensity) roundToPx() 再 coerce(0,150)，必须补回量纲转换。
    // ① 归宿主 DecorView：必须打在 hostActivity 原窗口上——grace 桌面的 LocalContext 是
    //    模块包装 context（GraceHomeRoot 覆盖），原版 (context as? Activity) 在此必 null；
    // dimfix：全屏表面集合与 searching 判定上移到壁纸效果可见度之前（原版
    // LauncherScreen.kt:530/534 定义顺序本就在 wallpaperEffectVisibility 之前）
    val searching = searchOpen
    // hotfix8：编辑收藏全屏页计入 fullScreen（原版 LauncherScreen.kt:534）
    // folders 阶段：文件夹弹层同窗全屏（原版 LauncherScreen.kt:534 fullScreen 集合）
    val fullScreen = searchOpen || pickerOpen || overlay == LauncherOverlay.Favorites ||
        overlay is LauncherOverlay.Folder
    // dimfix：壁纸效果可见度三分支（原版 LauncherScreen.kt:710-714
    // wallpaperEffectVisibility）：搜索时随 searchAlpha 渐入（搜索页壁纸遮罩/模糊），
    // 其余全屏浮层（picker/Favorites/Folder）为 0，平时随抽屉可见度。
    // hotfix6 误简化为恒等于 drawerAlpha —— 搜索页壁纸效果整体缺失（装机截图实锤：
    // 搜索页壁纸清晰无遮罩）。无 predictive back（宿主非 ComponentActivity），
    // 原版 searchAlpha * (1f - backProgress) 中 backProgress 恒 1，直接用 searchAlpha。
    val wallpaperEffectAlpha = when {
        searching -> searchAlpha
        fullScreen -> 0f
        else -> drawerAlpha
    }
    val wallpaperBlurOn = state.settings.blurWallpaper
    val wallpaperBlurRadius = state.settings.wallpaperBlurRadius
    val blurAvailable = rememberWallpaperBlurAvailable()
    LaunchedEffect(blurAvailable) {
        ModLog.i("WallpaperBlur crossWindowBlurEnabled=$blurAvailable blur=$wallpaperBlurOn")
    }
    WindowWallpaperBlurEffect(hostActivity, wallpaperBlurOn, wallpaperBlurRadius, wallpaperEffectAlpha)

    // ---- hotfix6：壁纸模糊 fallback（Compose 层壁纸快照模糊）----
    // 装机两轮实测：MIUI home 窗口上 setBackgroundBlurRadius 无效（系统侧静默 no-op，
    // 跨窗口模糊可用性与 MIUI 自身策略相关，宿主窗口无法改变）→ 原版路径降级，
    // 补 Niagara 系启动器通用做法：把系统壁纸取成 Bitmap，抽屉打开时以
    // RenderEffect（Modifier.blur）模糊层淡入覆盖在真实壁纸之上。半径用设置值
    // （0..48dp，与设置页滑杆一致），可见度跟随 wallpaperEffectAlpha —— 与原版
    // "模糊随抽屉渐入"的观感一致；静态壁纸逐像素对齐，动态壁纸降级为快照。
    // 窗口 blur 尝试保留（其他 ROM 上生效时位图层反正完全覆盖，视觉一致）。
    //
    // perf1（功耗/冷启动专项根因修复）：原实现 remember{} 在首组合【主线程】同步
    // 取整屏壁纸 drawable 并 toBitmap（binder + 全分辨率解码，4K 屏可达数十 MB
    // 分配）—— 桌面每次重启的首帧最大单点阻塞，且常驻内存。改为 LaunchedEffect
    // + Dispatchers.IO 异步加载，并按屏幕分辨率缩放（toBitmap(width,height) 内部
    // 按目标尺寸重绘，模糊层视觉细节本被 blur 抹平，1x 屏幕分辨率足够）。
    // 视觉零回退：模糊层初始 wallpaperEffectAlpha=0（抽屉打开才渐入），异步加载
    // 完成前的窗口期内该层本就不可见。
    val wallpaperBlurBitmap = rememberWallpaperBlurBitmap(hostContext, wallpaperBlurOn)

    // ---- hotfix6：壁纸遮罩色（解析收编 WallpaperEffects.rememberResolvedWallpaperTint）----
    val resolvedWallpaperTint = rememberResolvedWallpaperTint(state.settings.listAppearance.wallpaperDimColor)

    // ---- hotfix6：隐藏状态栏（原版 LauncherScreen.kt:581-585 rememberLauncherSystemBars）----
    // 原版语义 = autoHide：平时隐藏、列表下拉到顶/边缘滑唤出、3s 自动回收（BEHAVIOR_DEFAULT
    // 透明栏，非沉浸 scrim）。fullScreen/searching 取本仓的全屏浮层集合（search/picker）。
    // hostWindow 显式传入（主树 LocalContext 是包装 context，原版 as? Activity 必 null）。
    // （dimfix：searching/fullScreen 定义已上移至壁纸效果可见度之前）
    val appearance = LocalLauncherAppearance.current
    val statusBarPull = rememberLauncherSystemBars(
        autoHide = state.settings.hideStatusBar && (!fullScreen || searching),
        darkIcons = appearance.darkText,
        allowPullDown = !fullScreen && !editingHome && overlay == null,
        hostWindow = hostActivity?.window,
    )

    // Home 键回桌面：全部状态复位（对齐原版 returnHomeRequests.collect）
    LaunchedEffect(Unit) {
        viewModel.returnHomeRequests.collect {
            drawerOpen = false
            selectedLetter = null
            searchOpen = false
            overlay = null
            editingHome = false
            pickerOpen = false
            popupPickerOwner = null
            popupWidgetReturn = null
            // P5：对齐原版 onHomeIntent —— 取消进行中的启动 capture（launchJob cancel）
            appTransitions?.close()
        }
    }

    // hotfix2：设置页"移动小部件"命令 → 桌面进入 widget 编辑模式（关弹层，同原版
    // LauncherScreen.moveWidget 的 UI 状态切换，仅入口跨进程经桥命令到达）
    LaunchedEffect(Unit) {
        viewModel.moveWidgetRequests.collect {
            drawerOpen = false
            selectedLetter = null
            searchOpen = false
            overlay = null
            pickerOpen = false
            editingHome = true
        }
    }
    // 上游同步补漏：设置页"添加自定义小组件"（效率页 settings_add_widget 行）→
    // 桌面开 WidgetPicker。原版同进程 = LauncherActions.addWidget 直调；本仓设置页
    // 在模块进程，此前该 action 是 no-op 占位 → 入口点了没反应。对齐桌面端
    // addWidget 语义（单 widget 上限检查 + 预载 providers），复用 ADD_POPUP_WIDGET
    // 同款弹层编排（ADD_WIDGET 命令 → 宿主 engine → 本 collect）。
    LaunchedEffect(Unit) {
        viewModel.addWidgetRequests.collect {
            drawerOpen = false
            selectedLetter = null
            searchOpen = false
            overlay = null
            popupPickerOwner = null
            if (state.settings.homeLayout.hasWidget) {
                Toast.makeText(context, R.string.widget_single_limit, Toast.LENGTH_LONG).show()
            } else {
                viewModel.loadWidgetProviders()
                pickerOpen = true
            }
        }
    }
    // folders 阶段（hotfix folders2）：设置页"添加新的小组件" → 桌面开 WidgetPicker 弹层模式
    LaunchedEffect(Unit) {
        viewModel.addPopupWidgetRequests.collect { ownerKey ->
            drawerOpen = false
            selectedLetter = null
            searchOpen = false
            overlay = null
            editingHome = false
            pickerOpen = false
            popupWidgetReturn = null
            viewModel.loadWidgetProviders()
            popupPickerOwner = ownerKey
        }
    }
    LaunchedEffect(model.letters) {
        if (selectedLetter !in model.letters) selectedLetter = null
    }
    // P4：widget 添加结果反馈（bind/写库失败 Toast，对齐原版 fail()）。
    // hotfix folders3：弹层 widget 模式成功后重开文件夹弹层（原版 WidgetSetupActivity
    // finish 后 launcher 恢复、弹层仍在的最上层语义；folderWidgets 流携带新 widget）。
    LaunchedEffect(Unit) {
        viewModel.widgetSetupResults.collect { success ->
            val returnOwner = popupWidgetReturn
            popupWidgetReturn = null
            if (!success) {
                Toast.makeText(context, R.string.widget_setup_error, Toast.LENGTH_LONG).show()
            } else if (returnOwner != null) {
                val folder = state.folders.firstOrNull { "folder:${it.id}" == returnOwner }
                if (folder != null) {
                    overlay = LauncherOverlay.Folder(folder, lastFolderAnchor ?: Rect.Zero)
                }
                // 文件夹已被删 → 保持桌面（updatePopup 已由 requireExistingFolder 兜底失败 toast）
            }
        }
    }

    val favoriteKeys = remember(state.favoriteOrder) { state.favoriteOrder.toHashSet() }

    /** 对齐原版 LauncherRoute.launchApp：bounds 来自 LauncherRow 的 iconBounds（P5 前 route 丢弃）。 */
    fun launchApp(app: LauncherApp, bounds: Rect? = null) {
        val start: (AppLaunchTransition?) -> Unit = { transition ->
            if (!viewModel.launch(app, transition?.sourceBounds, transition?.options)) {
                Toast.makeText(context, R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
        if (bounds == null) start(null)
        else if (appTransitions != null) appTransitions.launch(launchView, bounds.toAndroidRect(), start)
        else start(AppLaunchTransition.fromIcon(launchView, bounds.toAndroidRect()))
    }

    // 上游同步 v1.1.8 S3：手势/按钮动作分发（原版 LauncherScreen.performGestureAction 语义，
    // 模块适配：① GraceButtonTransition 英雄动画随 S4；② 无障碍三动作（锁屏/通知栏/快捷设置）
    // 经 GraceSystemActions 本进程三层链执行（原生 → root → 无障碍；SYSTEM_ACTION 跨进程
    // 委派路径已废止删除）；Website/Assistant 宿主进程内直接 startActivity（无需无障碍）。
    val homeGestureRadius = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    fun performGestureAction(target: com.galaxyrio.gracelauncher.data.GraceButtonTarget,
        origin: com.galaxyrio.gracelauncher.ui.components.GraceButtonOrigin,
        fromButton: Boolean = true) {
        val buttonSettings = state.settings.graceButton
        // S8b 诊断打点：动作执行分流
        com.homemode4miui.util.ModLog.i(
            "GraceButton perform action=" + target.action.name + " key=" + target.itemKey +
            " enabled=" + target.enabled + " active=" + target.active +
            " anim=" + buttonSettings.animationsEnabled + " transition=" + (buttonTransition != null))
        if (!target.active || buttonTransition != null) return
        instantButtonEntrance = fromButton && !buttonSettings.animationsEnabled &&
            target.action in listOf(com.galaxyrio.gracelauncher.data.GraceButtonAction.Search,
                com.galaxyrio.gracelauncher.data.GraceButtonAction.AppList)
        when (target.action) {
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Search -> {
                searchQuery.clearText()
                // 英雄过渡：动画开 + 按钮触发才走遮罩展开（搜索页 snap 全透明等待揭幕）
                if (fromButton && buttonSettings.animationsEnabled)
                    buttonTransition = com.galaxyrio.gracelauncher.ui.components.GraceButtonTransition(
                        com.galaxyrio.gracelauncher.data.GraceButtonAction.Search, origin,
                        durationMillis = buttonSettings.searchAnimationDurationMs)
                searchOpen = true
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Settings ->
                openGraceSettingsInModule(context, "")
            com.galaxyrio.gracelauncher.data.GraceButtonAction.App,
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Shortcut -> {
                // GraceHomeUiState 无 findItem：apps + savedShortcutApps 双池按键解析
                // （savedShortcutApps = P4 长按弹层保存的快捷方式项，与 Shortcut 动作对应）
                val app = target.itemKey?.let { key ->
                    state.apps.firstOrNull { it.key == key }
                        ?: state.savedShortcutApps.firstOrNull { it.key == key }
                }
                if (app != null) launchApp(app)
                else android.widget.Toast.makeText(context, context.getString(
                    com.homemode4miui.R.string.app_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Folder -> {
                val folder = state.folders.firstOrNull { it.key == target.itemKey }
                if (folder != null) overlay = LauncherOverlay.Folder(folder, lastFolderAnchor ?: Rect.Zero)
                else android.widget.Toast.makeText(context, context.getString(
                    com.homemode4miui.R.string.folder_unavailable), android.widget.Toast.LENGTH_SHORT).show()
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.AppList -> {
                if (fromButton && buttonSettings.animationsEnabled)
                    buttonTransition = com.galaxyrio.gracelauncher.ui.components.GraceButtonTransition(
                        com.galaxyrio.gracelauncher.data.GraceButtonAction.AppList, origin,
                        durationMillis = buttonSettings.appListAnimationDurationMs)
                selectedLetter = null
                drawerState.requestScrollToItem(0)
                drawerOpen = true
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Agenda -> {
                viewModel.refreshWeather(); viewModel.refreshAgenda()
                overlay = LauncherOverlay.Agenda
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.LockScreen -> {
                // 锁屏走遮罩动画，LaunchedEffect(buttonTransition) 末帧提交（经桥委派）
                buttonTransition = com.galaxyrio.gracelauncher.ui.components.GraceButtonTransition(
                    com.galaxyrio.gracelauncher.data.GraceButtonAction.LockScreen, origin,
                    originIsButton = fromButton,
                    animationsEnabled = !fromButton || buttonSettings.animationsEnabled)
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Website,
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Assistant,
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Notifications,
            com.galaxyrio.gracelauncher.data.GraceButtonAction.QuickSettings -> {
                // 通知栏/快捷设置无遮罩过渡（原版直弹）；宿主原生（StatusBarManager 反射）优先，
                // root/无障碍由 GraceSystemActions 内部兜底链承接
                com.galaxyrio.gracelauncher.platform.GraceSystemActions.perform(context, target)
            }
            com.galaxyrio.gracelauncher.data.GraceButtonAction.Disabled -> Unit
        }
    }

    /** 对齐原版 LauncherRoute.launchShortcut：弹层快捷方式启动同享图标动画。 */
    fun launchShortcut(shortcut: LauncherShortcut, bounds: Rect? = null) {
        val start: (AppLaunchTransition?) -> Unit = { transition ->
            if (!viewModel.launchShortcut(shortcut, transition?.sourceBounds, transition?.options)) {
                Toast.makeText(context, R.string.shortcut_error, Toast.LENGTH_SHORT).show()
            }
        }
        if (bounds == null) start(null)
        else if (appTransitions != null) appTransitions.launch(launchView, bounds.toAndroidRect(), start)
        else start(AppLaunchTransition.fromIcon(launchView, bounds.toAndroidRect()))
    }

    val actions = remember(viewModel) {
        GracePopupActions(
            shortcuts = viewModel::loadShortcuts,
            cachedShortcuts = viewModel::cachedShortcuts,
            launchShortcutAt = { shortcut, bounds -> launchShortcut(shortcut, bounds) },
            launchShortcut = { launchShortcut(it) },
            appInfo = viewModel::openAppDetails,
            addWidget = {
                if (state.settings.homeLayout.hasWidget) {
                    Toast.makeText(context, R.string.widget_single_limit, Toast.LENGTH_LONG).show()
                } else {
                    viewModel.loadWidgetProviders()
                    pickerOpen = true
                }
            },
            configureWidget = viewModel::configureHomeWidget,
            removeWidget = viewModel::removeHomeWidget,
            moveWidget = { drawerOpen = false; selectedLetter = null; overlay = null; editingHome = true },
            cycleClockStyle = viewModel::cycleClockLayout,
            openIconPacks = {
                viewModel.loadIconPacks()
                overlay = LauncherOverlay.IconPacks
            },
        )
    }
    // P4：行拖拽手势（原版 rowGestures：预取 + 拖拽 reveal + 拖拽结束提交）
    // P5：onLaunchAt 接回 iconBounds（P4 临时丢弃），行点击/右滑 reveal 提交均带启动动画
    // 上游同步 v1.1.8 S7（v1.1.7 特性）：文件夹行左滑打开内部第一项。
    // 上游 openFirstItem 走弹层首项（含快捷方式兜底）；本模块宿主 UI 无 popups 模型，
    // 文件夹语义 = appKeys 首个应用直接启动（发布说明口径），弹层版随 P5 弹层批次扩展。
    val openFirstItem: (LauncherApp, Rect) -> Unit = openFirst@{ owner, bounds ->
        if (overlay != null || !state.settings.shortcutsFolders.swipeLeftToOpenFirst) return@openFirst
        // 上游同步 v1.1.8 S8b：openFirstItem 升级弹层首项（原版 firstPopupApp 语义）——
        // 文件夹 = popups 首个非 widget 项；回落 appKeys 首个应用（兼容无弹层数据的旧文件夹）
        val folder = state.folders.firstOrNull { it.key == owner.key } ?: return@openFirst
        val popupKey = state.popups[folder.key].orEmpty().firstOrNull { it.widget == null }?.key
        val app = popupKey?.let { key -> state.apps.firstOrNull { it.key == key } }
            ?: state.apps.firstOrNull { it.key == folder.appKeys.firstOrNull() }
            ?: return@openFirst
        launchApp(app, bounds)
    }
    // perf（v2.9.41）：AppRowGestures 非 @Stable 类实例，旧实现每次重组新建并下传
    // HomeScreen/AppDrawerScreen —— 大子树在每次 uiState 变化（含通知快照高频桥推送）
    // 时跳过不了重组。按语义键 remember：捕获集（viewModel/launchApp/overlay delegate）
    // 均为稳定引用，shortcutsFolders 变化才重建
    val rowGestures = remember(state.settings.shortcutsFolders) {
        AppRowGestures(
            onPrepare = viewModel::prepareShortcuts,
            onLaunchAt = { app, bounds -> launchApp(app, bounds) },
            popupsEnabled = state.settings.shortcutsFolders.enabled,
            onOpenFirst = openFirstItem.takeIf { state.settings.shortcutsFolders.swipeLeftToOpenFirst },
            onDrag = { app, bounds, expanded ->
                val current = overlay as? LauncherOverlay.Shortcuts
                if (current?.app?.key == app.key) {
                    current.reveal.expanded = expanded
                } else {
                    overlay = LauncherOverlay.Shortcuts(app, bounds, ShortcutRevealState(expanded, dragging = true))
                }
            },
            onDragEnd = { commit ->
                (overlay as? LauncherOverlay.Shortcuts)?.reveal?.let {
                    it.expanded = commit
                    it.dragging = false
                }
            },
        )
    }
    // 收藏弹层附加行（零功能回退：P1/P2 临时菜单的收藏增删入口迁入弹层尾部）
    val menuEntriesProvider: (LauncherApp) -> List<PopupEntry> = { app ->
        buildList {
            add(PopupEntry(
                "toggle_favorite",
                if (app.key in favoriteKeys) removeFromFavoritesLabel else addToFavoritesLabel,
            ) { viewModel.toggleFavorite(app) })
            // P6（B6.3）：有通知的应用附加打开/清除行（命令经桥到模块进程执行）
            state.notifications[app.packageName]?.firstOrNull()?.let { notification ->
                if (notification.canOpen) add(PopupEntry(
                    "notification_open", notificationOpenLabel,
                ) { viewModel.openNotification(notification.key, notification.revision) })
                if (notification.canDismiss) add(PopupEntry(
                    "notification_dismiss", notificationDismissLabel,
                ) { viewModel.dismissNotification(notification.key, notification.revision) })
            }
        }
    }

    // P5：顶层 provide LocalAppTransitions（对齐原版 MainActivity.setContent）——
    // launcherAnimationTarget 读此 local 注册 LauncherIconTarget，缺省 null 时
    // 图标动画目标整体不生效（降级为无 bounds 启动）。
    CompositionLocalProvider(LocalAppTransitions provides appTransitions) {
    // ---- dimfix：壁纸效果层上提为全屏兄弟层（对齐原版 LauncherScreen.kt:730-734 层级）----
    // 原版 wallpaperTint 遮罩与 WallpaperBlur 挂在列表容器（BoxWithConstraints）之外，
    // 不吃容器的 safeDrawing insets padding —— 壁纸效果覆盖全屏，含状态栏/导航栏/
    // 手势区背后的壁纸区域。hotfix6 把两层放进了容器内部，insets 区域露出未遮罩壁纸
    // （状态栏条带观感"遮罩没盖住"）。层级与绘制顺序照搬原版：壁纸效果在最底，
    // 列表容器在其上，LauncherOverlays 再其上。
    // dimfix 定案的 z 序（效果层最底 → 列表容器 → overlays）由调用点位置保持
    WallpaperEffectLayers(
        blurBitmap = wallpaperBlurBitmap.value,
        blurRadiusDp = wallpaperBlurRadius,
        effectAlpha = wallpaperEffectAlpha,
        tint = resolvedWallpaperTint,
        dimEnabled = state.settings.dimWallpaper,
        dimAmount = state.settings.wallpaperDimAmount,
    )
    BoxWithConstraints(
        // The lists extend behind both system bars. Insets belong to their
        // scrollable content, not a parent that clips the whole viewport.
        // hotfix6：nestedScroll(statusBarPull) —— 列表滚到顶继续下拉唤出状态栏
        //（原版 LauncherScreen.kt:738 同款挂点）。
        modifier = Modifier.fillMaxSize()
            .nestedScroll(statusBarPull)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val statusBarHeight = stableStatusBarInset()
        val bottomInset = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val safeHeight = (maxHeight - statusBarHeight - bottomInset).coerceAtLeast(0.dp)
        val regularHomeTop = (safeHeight * if (safeHeight < 600.dp) 0.12f else 0.32f).coerceIn(24.dp, 320.dp)
        val homeTop = statusBarHeight + regularHomeTop
        val drawerTop = statusBarHeight + safeHeight * 0.28f
        val railHeight = ((model.letters.size + 1) * 18).dp.coerceAtMost(safeHeight * 0.65f)

        // （dimfix：壁纸模糊 fallback 层与 wallpaperTint 遮罩已上提为全屏兄弟层，
        // 见 BoxWithConstraints 之前 —— 原版 LauncherScreen.kt:731-734 同构层级）
        val railTop = statusBarHeight + (safeHeight * 0.39f).coerceAtMost(safeHeight - railHeight - 72.dp).coerceAtLeast(0.dp)

        // ---- 首页（时钟 + widget + favorites）----
        // P5：LocalHomeAnimationTarget provides true（原版语义）——首页图标 target 标记
        // home = true，为后续 Quickstep 合约返回动画恢复预留（当前裁剪版仅注册）。
        CompositionLocalProvider(LocalHomeAnimationTarget provides true) {
            HomeScreen(
                favorites = state.favoriteItems,
                homeLayout = state.settings.homeLayout,
                clockEnabled = state.settings.clockEnabled,
                clockStyle = state.settings.clockStyle,
                // 上游同步 v1.1.8：列表外观大模型进 HomeScreen（行距/边距/字号/名字可见性）
                listAppearance = state.settings.listAppearance,
                shortcutsFolders = state.settings.shortcutsFolders,
                // 上游同步 v1.1.8 S3：桌面手势（空闲且无弹层/非编辑态时才触发）
                homeGestures = state.settings.homeGestures,
                onHomeGesture = { gesture, position ->
                    if (!drawerOpen && overlay == null && !editingHome) {
                        // 上游同步 v1.1.8 S4：手势原点 = 触点 ±24dp（hero 动画圆心）
                        val r = homeGestureRadius
                        val origin = com.galaxyrio.gracelauncher.ui.components.GraceButtonOrigin(
                            Rect(position.x - r, position.y - r, position.x + r, position.y + r))
                        performGestureAction(state.settings.homeGestures.target(gesture), origin, fromButton = false)
                    }
                },
                allowHapticFeedback = true,
                onLaunchApp = { app -> launchApp(app) },
                onAppDetails = viewModel::openAppDetails,
                onAppShortcuts = { app, bounds -> overlay = LauncherOverlay.Shortcuts(app, bounds) },
                // folders 阶段：收藏区文件夹项（原版 HomeScreen.kt:214-224）
                folders = state.folders,
                onOpenFolder = { folder, bounds ->
                    // 上游同步 v1.1.8 S8a：工作资料文件夹点击 = 抽屉内展开/收起（非弹层）
                    if (folder.id == com.galaxyrio.gracelauncher.data.WorkProfileFolderId) {
                        lastFolderAnchor = bounds
                        workExpanded = !workExpanded
                    } else {
                        lastFolderAnchor = bounds
                        overlay = LauncherOverlay.Folder(folder, bounds)
                    }
                },
                onEditFolder = { folder -> openGraceSettingsInModule(context, "folders") },
                // P6（B6.2）：日期点击 → 日程+天气面板（原版 LauncherOverlay.Agenda 语义）
                onDateClick = { overlay = LauncherOverlay.Agenda },
                onClockClick = viewModel::openClock,
                // hotfix2：面板打开即刷新授权态（hasMediaAccess 随面板打开保持新鲜）
                onWidgetMenu = { viewModel.refreshMediaAccess(); overlay = LauncherOverlay.HomeWidgetMenu },
                onCustomWidgetMenu = { overlay = LauncherOverlay.CustomWidgetMenu },
                editingLayout = editingHome,
                widgetInputEnabled = !drawerOpen && overlay == null && !editingHome && buttonTransition == null,
                topSpace = homeTop,
                onTopOffsetChange = viewModel::updateHomeLayoutTopOffset,
                onWidgetHeightChange = viewModel::updateHomeLayoutWidgetHeight,
                rowGestures = rowGestures,
                // ---- P6（B6.x）附属特性 ----
                // hotfix1：媒体显示改快照驱动（快照到达 = 权限已授 = 可用），
                // hasMediaAccess 只用于面板授权引导，不再拦截渲染 —— 授权发生在
                // 桌面运行期，一次性探测不刷新时原条件会永久隐藏已可用的组件
                homeMedia = if (state.settings.mediaPlayer) state.homeMedia else null,
                onMediaCommand = viewModel::controlMedia,
                onDismissMedia = viewModel::dismissMedia,
                mediaAlwaysVisible = state.settings.mediaAlwaysVisible,
                notifications = state.notifications,
                calendarAgenda = state.settings.calendarAgenda,
                calendarAboveClock = state.settings.calendarAboveClock,
                showBatteryPercentage = state.settings.showBatteryPercentage,
                events = state.events,
                weather = state.weather.snapshot?.current.takeIf { weatherReady },
                modifier = Modifier.retainedPage(visible = !drawerOpen && !searchOpen && !pickerOpen)
                    .graphicsLayer { alpha = 1f - drawerAlpha }
                    .statusBarContentFade(),
            )
        }

        // ---- 应用抽屉（P2 / P4 手势升级）----
        AppDrawerScreen(
            model = model,
            listState = drawerState,
            selectedLetter = selectedLetter,
            selectedLetterState = selectedLetterState,
            topSpace = drawerTop,
            onLaunchApp = { app -> launchApp(app) },
            onAppDetails = viewModel::openAppDetails,
            onAppShortcuts = { app, bounds -> overlay = LauncherOverlay.Shortcuts(app, bounds) },
            rowGestures = rowGestures,
            // folders 阶段：抽屉文件夹（原版 AppDrawerScreen FolderRow 分支）
            onOpenFolder = { folder, bounds ->
                // 上游同步 v1.1.8 S8a：抽屉内工作资料文件夹点击 = 展开/收起
                if (folder.id == com.galaxyrio.gracelauncher.data.WorkProfileFolderId) {
                    workExpanded = !workExpanded
                } else {
                    lastFolderAnchor = bounds
                    overlay = LauncherOverlay.Folder(folder, bounds)
                }
            },
            onEditFolder = { openGraceSettingsInModule(context, "folders") },
            // P6（B6.3）：抽屉行内通知预览（原版 AppDrawerScreen.kt:152 同构）
            notifications = state.notifications,
            // 上游同步 v1.1.8 S8a：工作资料（文件夹展开 + 空态设置入口）
            workExpanded = workExpanded && state.workFolder != null,
            onWorkProfileSettings = { openGraceSettingsInModule(context, "") },
            modifier = Modifier.retainedPage(visible = drawerOpen)
                .graphicsLayer { alpha = drawerAlpha }.statusBarContentFade(),
        )

        // ---- 字母索引 rail（P2）：常驻右侧，scrub 即跳；hotfix3 接 hideAlphabet ----
        // hotfix4 对齐原版 LauncherScreen.kt:837 语义：hideAlphabet ≠ 移除 rail，
        // 而是传 autoHide —— 平时淡出但触摸面保留，手指按下（state.active）立即
        // 淡入显示，scrub 手势完全可用，松手淡出（Niagara 式"隐藏 = 触摸即现"）。
        // 注意 rail 的 onLetterSelected(null) 分支会关抽屉：隐藏态下触摸也走 scrub。
        // searchrail 对齐原版 LauncherScreen.kt:731-741 容器级语义：rail 挂在首页
        // BoxWithConstraints 内，搜索打开时整层随 backProgress 淡出 + 语义清空——
        // 搜索页是 LauncherOverlays 里的独立透明全屏层，从不渲染 rail。移植时容器级
        // alpha 拆到了各子页面（HomeScreen/AppDrawerScreen 均带 !searchOpen），唯独
        // rail 漏了，导致搜索浮层下 A–Z 条常驻（装机截图实锤）。修复 = rail 随
        // searchAlpha 反向淡出（210ms 同款节奏），searchAlpha 到 1 后整个移出组合
        // （不绘制/不命中/无语义，等价原版 retainedPage(visible=false)）。
        if (!editingHome && !pickerOpen && searchAlpha < 1f) AlphabetRail(
            autoHide = state.settings.hideAlphabet && !drawerOpen,
            // 上游同步 v1.1.8：A-Z 列表自定义外观（字形/间距/几何）直达桌面 rail
            alphabet = state.settings.listAppearance.alphabet,
            // 上游同步 v1.1.8 S2 补漏（LauncherScreen.kt:1030 逐字）：屏幕左边缘
            // 28dp 字母 scrub 触摸面（listAppearance.leftAlphabet，默认开）。
            // 模块 AlphabetRail 组件本身已带该参数，但此处漏传 → 左侧永远无效。
            leftTouchEnabled = state.settings.listAppearance.leftAlphabet,
            letters = model.letters,
            selectedLetter = if (drawerOpen) selectedLetter else null,
            height = railHeight,
            onLetterSelected = { letter ->
                if (letter == null) {
                    drawerOpen = false
                    selectedLetter = null
                } else {
                    selectedLetter = letter
                    // Offset zero uses the list's leading content padding.
                    // LazyColumn clamps this request when the remaining apps
                    // cannot fill the viewport; do not append phantom space.
                    drawerState.requestScrollToItem(model.indexOfSection(letter))
                    drawerOpen = true
                }
            },
            modifier = Modifier.align(Alignment.TopEnd).offset(y = railTop)
                .graphicsLayer { alpha = 1f - searchAlpha },
            onScrubFinished = {
                // The list is already positioned. Only remove the temporary mask;
                // scrolling here would cause a jump between the held and released views.
                selectedLetter = null
            },
        )

        // ---- 搜索浮层（P2）：FAB 入口 ----
        if (searchOpen || searchAlpha > 0f) {
            AppSearchScreen(
                // 上游同步 v1.1.8 S3：搜索页下拉关闭与桌面共享 Lab 灵敏度
                swipeSensitivity = state.settings.homeGestures.swipeSensitivity,
                apps = state.apps,
                recentAppKeys = state.settings.searchSettings.recentAppKeys,
                fuzzy = state.settings.searchSettings.fuzzy,
                suggestions = state.settings.searchSettings.suggestions,
                onLaunch = { app, bounds ->
                    viewModel.recordSearchApp(app.key)
                    launchApp(app, bounds)
                },
                onDetails = viewModel::openAppDetails,
                onDismiss = { searchOpen = false },
                enterAlpha = searchAlpha,
                queryState = searchQuery,
            )
        }

        // ---- 上游同步 v1.1.8 S4：Grace Button（对齐原版 LauncherScreen.kt:1052-1060）----
        if (!drawerOpen && (editingHome || state.settings.graceButton.enabled)) {
            com.galaxyrio.gracelauncher.ui.components.GraceButton(
                settings = state.settings.graceButton,
                editing = editingHome,
                // 上游同步 S8b 修正：搜索打开时按钮禁用（上游 overlay != null 同语义；
                // 模块搜索 overlay 独立于 overlay 状态，需显式排除）
                enabled = overlay == null && buttonTransition == null && !searchOpen,
                hidden = searchTransition != null || searchOpen,
                heldOrigin = buttonTransition?.takeIf {
                    it.action == com.galaxyrio.gracelauncher.data.GraceButtonAction.LockScreen && it.originIsButton
                }?.origin,
                // 上游同步 v1.1.8 S8b（LauncherScreen.kt:1054 逐字）：仅当用户保存过
                // 按钮设计（item_icons 有行）才消费自定义 artwork，否则回退默认 G glyph。
                artwork = state.graceButtonApp?.icon.takeIf {
                    com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.key in state.itemIcons
                },
                // 上游同步 v1.1.8 S8b：按钮图标设计面（IconDesigner → item_icons → design）
                design = com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon
                    .choice(state.itemIcons[com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.key])
                    .design ?: com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.defaults,
                onGesture = { gesture, origin ->
                    // S8b 诊断打点：按钮手势 emit（组件层）——桌面拖动/长按无响应时先看此行是否出现
                    com.homemode4miui.util.ModLog.i(
                        "GraceButton emit gesture=" + gesture.name + " active=" +
                        state.settings.graceButton.target(gesture).active +
                        " action=" + state.settings.graceButton.target(gesture).action.name)
                    performGestureAction(state.settings.graceButton.target(gesture), origin)
                },
                onFinishEditing = { editingHome = false },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = com.galaxyrio.gracelauncher.ui.components.LauncherLayout.End,
                        bottom = 22.dp + bottomInset),
            )
        }

        // 过渡遮罩（最顶层：hero 圆形展开/锁屏黑场）+ 无障碍引导（按钮 LockScreen 未授权时）
        buttonTransition?.let {
            com.galaxyrio.gracelauncher.ui.components.GraceButtonTransitionOverlay(it)
        }

        // ---- FAB 已移除（上游同步 S8b 修复）：P4 时代的搜索 FAB 与 GraceButton 完全重叠
        //（同 align/padding/54dp/testTag），z 在上完全遮挡按钮手势——搜索入口由
        // GraceButton 的 tap 动作承担（编辑态完成键 = GraceButton editing 分支）。----

        // ---- WidgetPicker 全屏浮层（P4：原版 WidgetSetupActivity 的宿主内等价物；
        // ---- hotfix folders2：popupPickerOwner 非 null 时为弹层 widget 模式）----
        val popupOwner = popupPickerOwner
        if (pickerOpen || popupOwner != null) {
            WidgetPicker(
                providers = widgetProviders,
                busy = widgetPickerBusy,
                apps = state.apps,
                onBack = { pickerOpen = false; popupPickerOwner = null },
                onSelect = { info ->
                    pickerOpen = false
                    val owner = popupPickerOwner
                    popupPickerOwner = null
                    if (owner != null) {
                        // hotfix folders3：登记回弹目标（widgetSetupResults 成功后重开文件夹弹层）
                        popupWidgetReturn = owner
                        viewModel.addPopupWidgetTo(owner, info)
                    }
                    else viewModel.addHomeWidget(info)
                },
            )
        }

        // ---- 弹层编排（P4 + P6）：ShortcutPopup / HomeWidgetPanel / Agenda / IconPacks ----
        LauncherOverlays(
            overlay = overlay,
            layout = state.settings.homeLayout,
            apps = state.apps,
            actions = actions,
            hasShortcutAccess = state.hasShortcutAccess,
            menuEntriesProvider = menuEntriesProvider,
            onChange = { overlay = it },
            // 上游同步 v1.1.8 S8b：弹层行文字可见性跟随列表外观
            showNames = state.settings.listAppearance.showNames(favorites = false),
            // P6：时钟长按面板的附属特性开关行组（原版 HomeWidgetSheet non-custom 尾部）
            widgetMenuExtras = {
                WidgetToggle(stringResource(R.string.settings_calendar_agenda),
                    stringResource(R.string.settings_calendar_agenda_summary),
                    state.settings.calendarAgenda) { enabled ->
                    viewModel.updateSettings { it.copy(calendarAgenda = enabled) }
                    if (enabled) viewModel.refreshAgenda()
                }
                WidgetToggle(stringResource(R.string.settings_weather),
                    stringResource(R.string.weather_settings_enable_summary),
                    state.settings.weatherEnabled) { enabled ->
                    viewModel.updateSettings { it.copy(weatherEnabled = enabled) }
                    if (enabled) viewModel.refreshWeather()
                }
                WidgetToggle(stringResource(R.string.settings_media_player),
                    stringResource(R.string.media_player_summary),
                    state.settings.mediaPlayer) { enabled ->
                    viewModel.updateSettings { it.copy(mediaPlayer = enabled) }
                    // 原版语义：开启且未授权时引导系统通知监听设置（hotfix2：Activity 启动）
                    if (enabled && !state.hasMediaAccess) openMediaAccessSettings()
                }
                WidgetToggle(stringResource(R.string.settings_show_battery), null,
                    state.settings.showBatteryPercentage) { enabled ->
                    viewModel.updateSettings { it.copy(showBatteryPercentage = enabled) }
                }
                // 上游对齐修正：上游 HomeWidgetSheet（时钟面板）没有私密空间行——
                // 此处原为 P6 自加的探测降级面，漏了 enabled 过滤，用户关闭后仍常驻
                // 显示（用户实测反馈）。私密空间管理入口只在设置（效率页）承载，随上游。
            },
            // P6（B6.2）：日程 + 天气面板
            agenda = {
                AgendaSheet(
                    events = state.events,
                    weather = state.weather,
                    weatherEnabled = state.settings.weatherEnabled,
                    weatherForecastDays = state.settings.weatherForecastDays,
                    calendarAgenda = state.settings.calendarAgenda,
                    scheduleStatus = state.scheduleStatus,
                    onRefreshAgenda = viewModel::refreshAgenda,
                    onRefreshWeather = viewModel::refreshWeather,
                    onOpenWeather = { AgendaIntents.openWeatherApp(hostContext) },
                    onRequestCalendar = { viewModel.openCalendarApp() },
                    onOpenEvent = { AgendaIntents.openEvent(hostContext, it) },
                    onNewEvent = { AgendaIntents.newEvent(hostContext) },
                    onDismiss = { overlay = null },
                )
            },
            // P6（B6.4）：图标包单选面板
            iconPacks = {
                IconPackPanel(
                    packs = iconPacks,
                    selected = state.settings.enabledIconPackPackages.firstOrNull(),
                    busy = false,
                    onSelect = { pkg ->
                        viewModel.setIconPack(pkg)
                        overlay = null
                    },
                    onDismiss = { overlay = null },
                )
            },
            // hotfix8：编辑收藏全屏页（原版 LauncherOverlay.Favorites → FavoritesScreen；
            // uiState/actions 经 GraceFavoritesShim 从宿主 VM 投影，toggle/reorder 直连 FavoritesStore）
            favorites = {
                val shimState = remember(state) { GraceFavoritesShim.uiState(state) }
                val shimActions = remember(viewModel) { GraceFavoritesShim.actions(viewModel) }
                FavoritesScreen(
                    uiState = shimState,
                    actions = shimActions,
                    onToggle = viewModel::toggleFavorite,
                    onReorder = viewModel::reorderFavorites,
                    // folders 阶段：文件夹入口接通（原版默认行为；FavoriteFoldersScreen
                    // 经 shim actions.setFolderFavorites 直连宿主 Room）
                    onDone = { overlay = null },
                )
            },
            // folders 阶段：文件夹弹层（原版 LauncherSheets.kt:164-197；成员路由层解析）
            folder = {
                // 分发保证 slot 仅在 overlay is Folder 时组合
                val folderOverlay = overlay as LauncherOverlay.Folder
                val folder = state.folders.firstOrNull { it.id == folderOverlay.folder.id }
                if (folder == null) {
                    // 原版 LauncherSheets.kt:172-174：文件夹已删 → 关闭弹层
                    androidx.compose.runtime.LaunchedEffect(folderOverlay.folder.id) { overlay = null }
                } else {
                    FolderPopup(
                        folder = folder,
                        // hotfix folders2：成员解析含已保存快捷方式（appKeys 里的 shortcut key）
                        apps = state.apps + state.savedShortcutApps,
                        // hotfix folders2：弹层 widget 项（原版 ShortcutPopup entry.widget 分支）
                        widgets = state.folderWidgets[folder.id].orEmpty(),
                        anchor = folderOverlay.anchor,
                        reveal = folderOverlay.reveal,
                        onLaunchApp = { app, bounds ->
                            overlay = null
                            launchApp(app, bounds)
                        },
                        onEdit = {
                            overlay = null
                            openGraceSettingsInModule(context, "folders")
                        },
                        onDismiss = { overlay = null },
                    )
                }
            },
        )
    }
    } // CompositionLocalProvider(LocalAppTransitions)
}

private fun Rect.toAndroidRect() = android.graphics.Rect(
    left.toInt(), top.toInt(), right.toInt(), bottom.toInt(),
)

private fun Modifier.retainedPage(visible: Boolean): Modifier =
    // A premeasured page must not leak hidden apps into TalkBack's tree.
    then(if (visible) Modifier else Modifier.clearAndSetSemantics {}).layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) {
        if (visible) placeable.placeRelative(0, 0)
    }
}
