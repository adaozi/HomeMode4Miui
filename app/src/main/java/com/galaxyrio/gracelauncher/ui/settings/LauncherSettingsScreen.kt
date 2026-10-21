@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.GraceButtonAction
import com.galaxyrio.gracelauncher.data.GraceButtonGesture
import com.galaxyrio.gracelauncher.data.GraceButtonTarget
import com.galaxyrio.gracelauncher.ui.components.ShortcutPickerScreen
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherTypography
import com.galaxyrio.gracelauncher.ui.theme.SystemLauncherTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SettingsPage {
    Root, Productivity, Clock, ClockStyle, Calendar, Weather, Themes, Advanced, About, HiddenApps, Folders, FolderEditor,
    Changelog, Licenses, AppLicense, IconPacks, IconDesigner, IconDesignerApp, PrivateSpace, PrivateSpaceEditor, Search,
    MediaPlayer, GraceButton,
    // 上游同步 v1.1.8（S2）：A-Z 列表 + 列表外观设置页（Fonts 页随 S5 批次补）
    ListAppearance, Alphabet, ShortcutsFolders, Fonts, WorkProfile, WorkProfileEditor,
    // 上游同步 v1.1.8（S3）：手势目标编辑 + Lab；旧 GraceTap*/GraceLong* 四页被
    // GraceAction/{gesture}?home={home} 参数化路由取代
    GraceAction, GraceApp, GraceShortcut, GraceFolder, Gestures, LabGraceButton, LabGesture, LabPopup, HomeOrder, AppStacks,
    // 上游同步 v1.1.9：外观编辑页（Grace 按钮外观/位置 + 日历/天气/媒体播放器外观占位）
    GraceButtonAppearance, GraceButtonPosition, CalendarAppearance, WeatherAppearance, MediaPlayerAppearance,
}

/** Navigation owns each page's saved state and seekable predictive-back transition. */
@Composable
fun LauncherSettingsScreen(
    uiState: LauncherUiState,
    actions: LauncherActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPage: String? = null,
    initialFolderId: String? = null,
    handleRootBack: Boolean = true,
    initialIconDesignerApp: LauncherApp? = null,
    closePrivateSpaceOnDispose: Boolean = true,
) {
    val navController = rememberNavController()
    val closePrivateSpace by rememberUpdatedState(actions.closePrivateSpace)
    DisposableEffect(closePrivateSpaceOnDispose) { onDispose { if (closePrivateSpaceOnDispose) closePrivateSpace() } }
    val startDestination = remember(initialPage, initialFolderId) {
        when {
            initialFolderId != null -> "FolderEditor/${Uri.encode(initialFolderId)}"
            initialPage == "folders" -> SettingsPage.Folders.name
            initialPage != null -> SettingsPage.entries.firstOrNull { it.name == initialPage }?.name ?: SettingsPage.Root.name
            else -> SettingsPage.Root.name
        }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry?.id) {
        // NavHost's predictive back can pop the editor without invoking its app-bar callback.
        if (currentEntry?.destination?.route == SettingsPage.PrivateSpace.name) closePrivateSpace()
    }
    val canPop = currentEntry != null && navController.previousBackStackEntry != null
    val scope = rememberCoroutineScope()
    val rootExit = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val latestOnBack by rememberUpdatedState(onBack)
    val distance = with(LocalDensity.current) { 30.dp.roundToPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val closeRoot: () -> Unit = {
        if (!closing) {
            if (!handleRootBack) latestOnBack() else {
                closing = true
                scope.launch {
                    rootExit.animateTo(1f, tween(300))
                    latestOnBack()
                }
            }
        }
    }

    // Embedded settings reveal the retained desktop. The standalone SettingsActivity
    // leaves its root callback disabled, preserving Android's cross-task animation.
    PredictiveBackHandler(enabled = handleRootBack && !canPop && !closing) { events ->
        try {
            events.collect { rootExit.snapTo(it.progress) }
            closing = true
            rootExit.animateTo(1f, tween(180))
            latestOnBack()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { rootExit.animateTo(0f, tween(180)) }
            closing = false
            throw cancelled
        }
    }

    val storage = SettingsStorageState(
        uiState.isLoadingSettings, uiState.settingsLoadFailed, uiState.settingsSaveFailed,
    )
    MaterialTheme(typography = if (uiState.settings.applyFontToSettings) LocalLauncherTypography.current else SystemLauncherTypography) {
     CompositionLocalProvider(LocalSettingsStorageState provides storage) {
      NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize().testTag("settings_navigation").graphicsLayer {
            alpha = 1f - rootExit.value
            translationX = distance * direction * rootExit.value
        }.background(if (currentEntry?.destination?.route in listOf(SettingsPage.ClockStyle.name, SettingsPage.IconDesigner.name,
            SettingsPage.ListAppearance.name, SettingsPage.Alphabet.name, SettingsPage.GraceButtonAppearance.name, SettingsPage.GraceButtonPosition.name))
            Color.Transparent else MaterialTheme.colorScheme.surfaceContainer),
        enterTransition = { settingsEnter(distance) },
        exitTransition = { settingsExit(distance) },
        popEnterTransition = { settingsEnter(distance, back = true) },
        popExitTransition = { settingsExit(distance, back = true) },
      ) {
        SettingsPage.entries.forEach { page ->
            val isEditor = page == SettingsPage.FolderEditor
            // 上游同步 v1.1.8 S3：手势目标编辑页参数化路由（gesture + home 双参数）
            val isGraceAction = page in listOf(SettingsPage.GraceAction, SettingsPage.GraceApp, SettingsPage.GraceShortcut, SettingsPage.GraceFolder)
            composable(
                route = when {
                    isEditor -> "FolderEditor/{folderId}"
                    isGraceAction -> "${page.name}/{gesture}?home={home}"
                    else -> page.name
                },
                arguments = when {
                    isEditor -> listOf(navArgument("folderId") { type = NavType.StringType })
                    isGraceAction -> listOf(navArgument("gesture") { type = NavType.StringType },
                        navArgument("home") { type = NavType.BoolType; defaultValue = false })
                    else -> emptyList()
                },
            ) { entry ->
                // Ignore double taps and clicks on an outgoing/predictively revealed page.
                fun isCurrent() = navController.currentBackStackEntry === entry &&
                    entry.lifecycle.currentState == Lifecycle.State.RESUMED && !closing
                val navigate: (SettingsPage) -> Unit = { destination ->
                    if (isCurrent()) navController.navigate(destination.name) { launchSingleTop = true }
                }
                val back: () -> Unit = {
                    if (isCurrent()) {
                        if (navController.previousBackStackEntry != null) navController.popBackStack()
                        else closeRoot()
                    }
                }
                // Never initialize an editable draft before Room's first snapshot.
                if (!storage.canEdit) {
                    SettingsScaffold(stringResource(R.string.settings_title), "settings_storage_blocked", back) { }
                } else when (page) {
                    SettingsPage.Root -> SettingsHome(back, navigate)
                    SettingsPage.Productivity -> ProductivitySettings(uiState, actions, back, navigate)
                    SettingsPage.Clock -> ClockSettings(uiState, actions, back) { navigate(SettingsPage.ClockStyle) }
                    SettingsPage.Search -> SearchSettingsScreen(uiState, actions, back)
                    SettingsPage.MediaPlayer -> MediaPlayerSettings(uiState, actions, back) { navigate(SettingsPage.MediaPlayerAppearance) }
                    SettingsPage.GraceButton, SettingsPage.Gestures -> GraceButtonSettingsScreen(uiState, actions, back,
                        // 上游同步 v1.1.8 S3：home = 手势面（homeGestures 模型）；
                        // v1.1.9：图标编辑入口移除，改走外观页（GraceButtonAppearance）
                        home = page == SettingsPage.Gestures,
                        onAppearance = { navigate(SettingsPage.GraceButtonAppearance) }) { gesture ->
                        if (isCurrent()) navController.navigate(
                            "${SettingsPage.GraceAction.name}/${gesture.name}?home=${page == SettingsPage.Gestures}") { launchSingleTop = true }
                    }
                    SettingsPage.GraceAction -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        GraceButtonActionSettings(gesture, uiState, actions, back, home) { action ->
                            val destination = when (action) {
                                GraceButtonAction.Folder -> SettingsPage.GraceFolder
                                GraceButtonAction.Shortcut -> SettingsPage.GraceShortcut
                                else -> SettingsPage.GraceApp
                            }
                            if (isCurrent()) navController.navigate("${destination.name}/${gesture.name}?home=$home") { launchSingleTop = true }
                        }
                    }
                    SettingsPage.GraceApp -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        val target = uiState.settings.gestureSettings(home).target(gesture)
                        AppSelectionSettings(uiState, actions, back, title = stringResource(R.string.grace_button_open_app),
                            tag = "grace_button_app", selectedKey = target.itemKey.takeIf { target.action == GraceButtonAction.App }, excludeOwnApp = false,
                            onSelect = { key -> if (isCurrent() && key != null) {
                                actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture, GraceButtonTarget(GraceButtonAction.App, key)) } }
                                back()
                            } })
                    }
                    SettingsPage.GraceFolder -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        val target = uiState.settings.gestureSettings(home).target(gesture)
                        val selectedFolder = uiState.folders.firstOrNull { target.action == GraceButtonAction.Folder && it.key == target.itemKey }
                        FolderSettings(uiState, actions, back, selectedIds = setOfNotNull(selectedFolder?.id), singleChoice = true,
                            onToggle = { id ->
                                val folder = uiState.folders.firstOrNull { it.id == id }
                                if (isCurrent() && folder != null) {
                                    actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture,
                                        GraceButtonTarget(GraceButtonAction.Folder, folder.key)) } }
                                    back()
                                }
                            },
                            onEdit = { id -> if (isCurrent()) navController.navigate("FolderEditor/${android.net.Uri.encode(id)}") })
                    }
                    SettingsPage.GraceShortcut -> {
                        val gesture = GraceButtonGesture.entries.firstOrNull { it.name == entry.arguments?.getString("gesture") } ?: GraceButtonGesture.Tap
                        val home = entry.arguments?.getBoolean("home") == true
                        val target = uiState.settings.gestureSettings(home).target(gesture)
                        val pickerScope = rememberCoroutineScope()
                        var saving by remember { mutableStateOf(false) }
                        ShortcutPickerScreen(uiState, actions, setOfNotNull(target.itemKey.takeIf { target.action == GraceButtonAction.Shortcut }),
                            back, singleChoice = true, busy = saving, onSelect = { app ->
                                saving = true
                                pickerScope.launch {
                                    try {
                                        if (actions.rememberShortcut(app) && isCurrent()) {
                                            actions.updateSettings { it.withGestureSettings(home) { settings -> settings.withTarget(gesture,
                                                GraceButtonTarget(GraceButtonAction.Shortcut, app.key)) } }
                                            back()
                                        }
                                    } finally { saving = false }
                                }
                            })
                    }
                    SettingsPage.Weather -> WeatherSettings(uiState, actions, back) { navigate(SettingsPage.WeatherAppearance) }
                    SettingsPage.Fonts -> FontSettings(uiState, actions, back)
                    SettingsPage.LabGraceButton -> LabGraceButtonSettings(uiState, actions, back)
                    SettingsPage.LabGesture -> LabGestureSettings(uiState, actions, back)
                    // 上游同步 1.2.0：Pop-up 触发灵敏度（额外滑动距离 / 允许偏离角度）
                    SettingsPage.LabPopup -> LabPopupSettings(uiState, actions, back)
                    // 上游同步 1.2.0（S6c）：主页区块顺序编辑
                    // 上游同步 1.2.0（S4c）：应用堆独立管理页（与文件夹同页复用，只列 isAppStack）
                    SettingsPage.AppStacks -> FolderSettings(uiState, actions, back, appStacks = true) { folderId ->
                        if (isCurrent()) navController.navigate("FolderEditor/${Uri.encode(folderId)}")
                    }
                    SettingsPage.HomeOrder -> HomeOrderSettings(uiState, actions, back)
                    SettingsPage.ListAppearance -> ListAppearanceSettings(uiState, actions, back) { }
                    SettingsPage.ShortcutsFolders -> ShortcutsFoldersSettingsScreen(uiState, actions, back)
                    SettingsPage.Alphabet -> AlphabetSettings(uiState, actions, back) { }
                    SettingsPage.Themes -> ThemeSettings(uiState, actions, back,
                        onClockStyle = { navigate(SettingsPage.ClockStyle) }, onIconPacks = { navigate(SettingsPage.IconPacks) },
                        onFonts = { navigate(SettingsPage.Fonts) }, onListAppearance = { navigate(SettingsPage.ListAppearance) },
                        onAlphabet = { navigate(SettingsPage.Alphabet) }, onGraceButton = { navigate(SettingsPage.GraceButtonAppearance) },
                        onCalendar = { navigate(SettingsPage.CalendarAppearance) }, onWeather = { navigate(SettingsPage.WeatherAppearance) },
                        onMediaPlayer = { navigate(SettingsPage.MediaPlayerAppearance) })
                    SettingsPage.GraceButtonAppearance -> IconDesignerSettings(uiState, actions,
                        com.galaxyrio.gracelauncher.data.icons.GraceButtonIcon.key, back,
                        onChooseApp = {}, onSelectApp = {}, onPositionAndSize = { navigate(SettingsPage.GraceButtonPosition) })
                    SettingsPage.GraceButtonPosition -> GraceButtonPositionSettings(uiState, actions, back)
                    SettingsPage.CalendarAppearance -> AppearancePlaceholder(stringResource(R.string.widget_calendar), "calendar_appearance", back)
                    SettingsPage.WeatherAppearance -> AppearancePlaceholder(stringResource(R.string.settings_weather), "weather_appearance", back)
                    SettingsPage.MediaPlayerAppearance -> AppearancePlaceholder(stringResource(R.string.settings_media_player), "media_appearance", back)
                    SettingsPage.ClockStyle -> ClockStyleSettings(uiState, actions, back)
                    SettingsPage.IconPacks -> IconPackSettings(uiState, actions, back) { navigate(SettingsPage.IconDesigner) }
                    SettingsPage.IconDesigner -> {
                        val selectedKey by entry.savedStateHandle.getStateFlow<String?>("icon_designer_app", initialIconDesignerApp?.key).collectAsState()
                        IconDesignerSettings(uiState, actions, selectedKey, back,
                            onChooseApp = { navigate(SettingsPage.IconDesignerApp) },
                            onSelectApp = { key -> entry.savedStateHandle.set("icon_designer_app", key) },
                            fallbackApp = initialIconDesignerApp)
                    }
                    SettingsPage.IconDesignerApp -> AppSelectionSettings(
                        uiState, actions, back, title = stringResource(R.string.icon_designer_choose_app), tag = "icon_designer",
                        selectedKey = navController.previousBackStackEntry?.savedStateHandle?.get<String>("icon_designer_app"),
                        onSelect = { key ->
                            if (isCurrent() && key != null) {
                                navController.previousBackStackEntry?.savedStateHandle?.set("icon_designer_app", key)
                                back()
                            }
                        },
                    )
                    SettingsPage.Calendar -> CalendarSettings(uiState, actions, back) { navigate(SettingsPage.CalendarAppearance) }
                    // 上游同步 v1.1.8 S3：Advanced 页 = Lab 入口（原 coming-soon 占位移除）
                    SettingsPage.Advanced -> AdvancedSettings(back, navigate)
                    SettingsPage.About -> AboutSettings(back, navigate)
                    SettingsPage.HiddenApps -> HiddenAppsSettings(uiState, actions, back)
                    // 上游同步 v1.1.8 S8b：工作资料设置页（ProfileSettingsScreen work 面）+ 应用编辑器
                    SettingsPage.WorkProfile -> ProfileSettingsScreen(uiState, actions, back, work = true) { navigate(SettingsPage.WorkProfileEditor) }
                    SettingsPage.WorkProfileEditor -> ProfileEditorSettings(uiState, actions, back, work = true, onEditIcon = { app ->
                        if (isCurrent()) {
                            navController.navigate(SettingsPage.IconDesigner.name)
                            navController.currentBackStackEntry?.savedStateHandle?.set("icon_designer_app", app.key)
                        }
                    })
                    SettingsPage.PrivateSpace -> PrivateSpaceSettings(uiState, actions, back) { navigate(SettingsPage.PrivateSpaceEditor) }
                    SettingsPage.PrivateSpaceEditor -> PrivateSpaceEditorSettings(uiState, actions,
                        onBack = { actions.closePrivateSpace(); back() }, onEditIcon = { app ->
                            if (isCurrent()) {
                                navController.navigate(SettingsPage.IconDesigner.name)
                                navController.currentBackStackEntry?.savedStateHandle?.set("icon_designer_app", app.key)
                            }
                        })
                    SettingsPage.Folders -> FolderSettings(uiState, actions, back) { folderId ->
                        if (isCurrent()) navController.navigate("FolderEditor/${Uri.encode(folderId)}")
                    }
                    SettingsPage.FolderEditor -> FolderEditorSettings(entry.arguments?.getString("folderId"), uiState, actions, back,
                        onEditIcon = { app ->
                            if (isCurrent()) {
                                navController.navigate(SettingsPage.IconDesigner.name)
                                navController.currentBackStackEntry?.savedStateHandle?.set("icon_designer_app", app.key)
                            }
                        })
                    SettingsPage.Changelog -> ChangelogSettings(back)
                    SettingsPage.Licenses -> LicenseSettings(back, navigate)
                    SettingsPage.AppLicense -> AppLicenseSettings(back)
                }
            }
        }
      }
     }
    }
}

@Composable
private fun SettingsHome(
    onBack: () -> Unit,
    navigate: (SettingsPage) -> Unit,
) {
    val categories = listOf(
        SettingsPage.Productivity to LauncherSymbol.Star,
        SettingsPage.Themes to LauncherSymbol.Palette,
        SettingsPage.Advanced to LauncherSymbol.Settings,
        SettingsPage.About to LauncherSymbol.Info,
    )
    SettingsScaffold(stringResource(R.string.settings_title), "settings_root", onBack) { padding ->
        SettingsList(padding) {
            item(key = "settings_intro") { Spacer(Modifier.height(24.dp)) }
            categories.forEachIndexed { index, (page, symbol) ->
                item(key = page.name) {
                    val title = when (page) {
                        SettingsPage.Productivity -> R.string.settings_productivity
                        SettingsPage.Themes -> R.string.settings_themes
                        SettingsPage.Advanced -> R.string.settings_advanced
                        else -> R.string.settings_about
                    }
                    val summary = when (page) {
                        SettingsPage.Productivity -> R.string.settings_productivity_summary
                        SettingsPage.Themes -> R.string.settings_themes_summary
                        SettingsPage.Advanced -> R.string.settings_advanced_summary
                        else -> R.string.settings_about_summary
                    }
                    SettingsActionItem(
                        stringResource(title), stringResource(summary), index, categories.size,
                        tag = "settings_category_${page.name.lowercase()}",
                        leading = {
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                LauncherIcon(symbol, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        },
                    ) { navigate(page) }
                }
            }
        }
    }
}

// Same hierarchy motion as Sudoku's MaterialTransitionPatterns: 30 dp travel,
// 300 ms spatial motion, an outgoing 90 ms fade and an incoming 210 ms fade.
// NavHost also seeks these transitions while a predictive-back gesture is held.
internal fun <S> AnimatedContentTransitionScope<S>.settingsEnter(distance: Int, back: Boolean = false) =
    slideIntoContainer(
        towards = if (back) AnimatedContentTransitionScope.SlideDirection.End else AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        initialOffset = { it.coerceIn(-distance, distance) },
    ) + fadeIn(tween(210, delayMillis = 90, easing = LinearEasing))

internal fun <S> AnimatedContentTransitionScope<S>.settingsExit(distance: Int, back: Boolean = false) =
    slideOutOfContainer(
        towards = if (back) AnimatedContentTransitionScope.SlideDirection.End else AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        targetOffset = { it.coerceIn(-distance, distance) },
    ) + fadeOut(tween(90, easing = LinearEasing))
