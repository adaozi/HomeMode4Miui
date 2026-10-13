/*
 * C 组（C1）模块进程设置页入口 —— 新建文件（非搬运，对齐原版 SettingsActivity.kt 语义）。
 *
 * 原版 SettingsActivity 与桌面同进程共用 LauncherViewModel；本仓设置页独立运行在
 * 模块 App 进程（com.homemode4miui），数据经 C3 广播桥与宿主 grace_launcher.db 同步。
 * 正常 Compose Activity，不碰宿主（P6 hotfix2/hotfix3 教训：独立 task 被 MIUI 回收 /
 * 宿主直启被 BAL 拦截 —— 故入口统一走本模块 LAUNCHER Activity 转发）。
 */
package com.homemode4miui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.galaxyrio.gracelauncher.data.ThemeMode
import com.galaxyrio.gracelauncher.ui.settings.LauncherSettingsScreen
import com.homemode4miui.grace.settings.SettingsViewModel
import com.homemode4miui.util.ModLog
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import com.galaxyrio.gracelauncher.ui.theme.GraceLauncherTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/**
 * 主题（原版 LauncherAppTheme 同构）：设置页自己消费快照里的外观设置。
 * 抽成文件级函数放在这里（原版是 ui/LauncherAppTheme.kt + LauncherViewModel 参数，
 * 本仓 VM 类型不同，包名也要求 ui.grace 以免与宿主进程文件混淆）。
 */
object GraceSettingsTheme {
    @androidx.compose.runtime.Composable
    internal fun LauncherAppTheme(viewModel: SettingsViewModel, content: @androidx.compose.runtime.Composable () -> Unit) {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val dark = when (state.settings.darkMode) {
            ThemeMode.System -> isSystemInDarkTheme()
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        GraceLauncherTheme(
            darkTheme = dark,
            amoledMode = state.settings.amoledMode,
            dynamicColor = state.settings.useDynamicColors,
            seedColor = Color(state.settings.themeColor),
            fontId = state.settings.appFontId,
            content = content,
        )
    }
}

class SettingsActivity : ComponentActivity() {
    private val settingsViewModel: SettingsViewModel by viewModels()
    private var initialPage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialPage = intent?.getStringExtra("page")
        enableEdgeToEdge()
        setContent {
            // hotfix1（装机崩溃：点击时钟样式即重启）：本进程没有宿主，必须 provide
            // LocalHostContext，否则 ClockStylePreview → HomeClockHeader → ClockFace 读
            // LocalHostContext.current 直接走 error(...) 默认值，组合期抛
            // IllegalStateException → 模块进程崩溃（宿主桌面由 GraceHomeRoot 提供）。
            // 提供模块 applicationContext 后，hostContext 消费点安全降级：
            //   - ClockFontStore 指向模块 filesDir/clock_fonts（空目录）→ typeface()
            //     的 file.isFile 守卫返回 null → 回落 preset/默认字体；
            //   - 即设置页预览不含宿主导入的自定义时钟字体（跨进程文件本就不可达，
            //     见 PORTING C 组验收节降级登记），桌面侧渲染不受影响。
            androidx.compose.runtime.CompositionLocalProvider(
                com.homemode4miui.grace.LocalHostContext provides applicationContext,
            ) {
                GraceSettingsTheme.LauncherAppTheme(settingsViewModel) {
                    LauncherSettingsScreen(
                        uiState = settingsViewModel.uiState.collectAsStateWithLifecycle().value,
                        actions = settingsViewModel.actions,
                        onBack = ::finish,
                        // 独立 task 的 LAUNCHER 入口：保留系统跨任务转场，root back 直接 finish
                        handleRootBack = false,
                        // C 组（C4）：时钟长按面板直达 ClockStyle 面板（原为 cycleClockLayout 演示）
                        initialPage = initialPage,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // hotfix：CLEAR_TOP|SINGLE_TOP 复用实例的入口 —— 刷新目标页；page 变化时
        // setIntent + recreate 重定向 NavHost startDestination（initialPage 只在
        // onCreate 读，普通字段变更不会触发重组导航）
        setIntent(intent)
        val page = intent.getStringExtra("page")
        if (page != initialPage) {
            initialPage = page
            recreate()
        }
    }

    override fun onResume() {
        super.onResume()
        // 回前台重拉快照（设置页打开期间桌面侧可能改过设置/收藏）
        ModLog.i("SettingsActivity onResume: request snapshot")
        com.homemode4miui.bridge.GraceSettingsSnapshotStore.requestSnapshot(applicationContext)
        settingsViewModel.refreshApps()
        // hotfix2：跨进程 UI 动作（moveWidget）→ 发完命令关设置页，回桌面看编辑态
        if (!closeRequestsJobActive) {
            closeRequestsJobActive = true
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    settingsViewModel.closeRequests.collect { finish() }
                }
            }
        }    }

    private var closeRequestsJobActive = false
}
