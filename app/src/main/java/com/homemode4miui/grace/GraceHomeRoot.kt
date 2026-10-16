/*
 * P1 路由 —— 升级自 P0 最小骨架，裁剪自 GraceLauncher v1.1.4（GPL-3.0，
 * https://github.com/Galaxy-rio/GraceLauncher），参考 ui/LauncherScreen.kt（LauncherRoute）。
 *
 * P0 职责保留：首帧心跳（持久化熔断）+ 壁纸动态取色 + 深色主题。
 * P1 新增（PORTING_GraceLauncher.md B1.1–B1.4）：
 *   - GraceHomeViewModel（B1.3，GraceHomeView 构造传入，绕过 viewModels() 委托）
 *   - HomeScreen 全量首页（favorites + 嵌套滚动 + 自绘时钟 ClockFace）
 *   - 时钟样式 SP 落地（B1.4，GraceHomeSettingsStore）
 * 原 LauncherRoute 的抽屉/搜索路由（P2）、弹层（P4）逐步在此扩展。
 *
 * hotfix（v2.2.1：装机长按收藏行崩溃，日志 2026-10-08 12:53:09）：
 *   模块运行在宿主进程内，Compose LocalContext 默认是宿主 Context，其 resources
 *   解析不了模块资源 ID → stringResource 抛 NotFoundException（组合期，熔断接不住，
 *   桌面进程死亡）。修复：LocalContext 覆盖为模块包 Context；R.string/R.font 全部
 *   改走模块资源表。需要宿主侧行为（filesDir/Activity window）的组件改用
 *   LocalHostContext（见 HostContext.kt）。
 */
package com.homemode4miui.grace

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.galaxyrio.gracelauncher.data.ThemeMode
import com.galaxyrio.gracelauncher.ui.theme.GraceLauncherTheme
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance
import com.homemode4miui.util.ModLog

/** 模块 APK 的包名（= 本模块 applicationId）。宿主进程经 createPackageContext 读其资源。 */
private const val MODULE_PACKAGE = "com.homemode4miui"

/**
 * 模块侧内容根：底座 GraceHomeView.setContent 的唯一入口。
 *
 * @param viewModel P1 手动构造（GraceHomeView 持有生命周期，shutdown 由 onHomeDetached 调用）
 * @param onComposeAlive 首帧【绘制完成】后的心跳回调（GraceHomeView 持久化熔断用）：
 *   先 withFrameNanos 等到真正出一帧再上报——若 measure/layout/draw 阶段崩进程，
 *   心跳不会发出，pending 标记跨重启存活，熔断计数才能覆盖帧阶段的不可捕获异常。
 * @param hostActivity P5：宿主 Launcher Activity 实例（GraceHomeView.mLauncher 透传）。
 *   ComposeView 的 LocalContext 是 ModuleResourceBridge 包裹后的 ContextWrapper，
 *   拿不到 Activity —— 启动动画中枢 LauncherAppTransitions 需要真实 Activity 引用。
 */
@Composable
fun GraceHomeRoot(
    viewModel: GraceHomeViewModel,
    onComposeAlive: (() -> Unit)? = null,
    hostActivity: android.app.Activity? = null,
) {
    val hostContext = LocalContext.current
    // 模块 Context：resources/assets 指向本模块 APK，R.string/R.font 才可解析。
    // 只取资源，不带 INCLUDE_CODE（模块代码已由 LSPosed 注入，避免二次类加载）。
    val moduleContext = remember(hostContext) {
        runCatching {
            hostContext.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.onFailure { ModLog.e("GraceHomeRoot createPackageContext($MODULE_PACKAGE) failed, resource access will degrade", it) }
            .getOrDefault(hostContext)
    }
    // 宿主桌面窗口铺在壁纸之上，深色主题 + 壁纸动态取色是桌面默认语义
    //
    // hotfix（appFont/iconSize 桌面接线，C 组登记"仅持久化、宿主渲染待接"补漏）：
    // 设置页经桥把 appFontId / iconDesignJson（design.iconSize = 图标显示尺寸）
    // 落 Room launcher_settings，宿主 VM observeSettings 流驱动本根组件重组。
    // 三处硬编码根因：
    //   ① fontId = null → 桌面 typography 永远 Josefin（设置页传了 appFontId 所以
    //      设置里生效、桌面不生效）；
    //   ② LocalFontLibrary 在 GraceLauncherRoute（主题内部）才提供 → 主题内
    //      rememberAppFontFamily 解析回退链时读到空默认库，导入字体全部回落；
    //   ③ rememberLauncherAppearance 未传 iconSize → LocalLauncherAppearance.iconSize
    //      恒 100，设置页滑杆只影响设置页自己的预览（LauncherComponents 的
    //      displaySize 消费端永远拿到 100）。
    val uiState = viewModel.uiState.collectAsState().value
    val settings = uiState.settings
    // darkMode 三态 + amoledMode + 动态取色/种子色：对齐上游 LauncherAppTheme
    // （桌面与设置页共用同一外观设置语义；seedColor 在 useDynamicColors=true 时
    // 被 GraceLauncherTheme 的动态取色分支短路，传参语义与上游一致）。
    val dark = when (settings.darkMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
        GraceLauncherTheme(
            darkTheme = dark,
            amoledMode = settings.amoledMode,
            dynamicColor = settings.useDynamicColors,
            seedColor = Color(settings.themeColor),
            // 上游同步 v1.1.9：调色板风格（TonalSpot/Vibrant/Expressive/…，动态色同样生效）
            paletteStyle = settings.paletteStyle,
            // 上游同步 v1.1.8 S5：全局字体 = 字体库 selected 链（字体库管理页首位）。
            // appFontId 为 legacy 列，设置页从不写入 —— 接它永远不生效（装机实锤）。
            fontLibrary = settings.fontLibrary,
            // hotfix（appFont 桌面接线补漏）：主题层字体解析必须用模块 Context ——
            // 此处的 LocalContext 是 ModuleResourceBridge 包装的宿主 Context（宿主
            // assets 无模块字体资产，宿主 filesDir 无导入字体），不传则字体恒回落。
            fontContext = moduleContext,
        ) {
            // 审计修缮（2026-10-16）：壁纸文字颜色接宿主 SP —— 设置页 TEXT_MODE 桥命令
            // 落 launcher_appearance.xml（此前硬编码 Auto，Light/Dark 档只在设置页预览生效）。
            // SP 写入不触发 Room 设置流，以 settingsRevision（onSettingsMutated 自增）驱动重组。
            val textMode = remember(uiState.settingsRevision) {
                com.galaxyrio.gracelauncher.data.LauncherPreferences(hostContext).textMode
            }
            val appearance = rememberLauncherAppearance(
                textMode, themedIcons = true,
                iconSize = settings.iconDesign?.design?.iconSize ?: 100,
            )
            CompositionLocalProvider(
                LocalContext provides moduleContext,
                LocalHostContext provides hostContext,
                LocalLauncherAppearance provides appearance,
            ) {
                // 心跳：等首帧真正绘制完成再上报（见函数注释）
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    onComposeAlive?.invoke()
                }
                // P2：路由升级为两态（首页/抽屉/搜索），见 GraceLauncherRoute；
                // P5：透传宿主 Activity（启动动画中枢构造用）
                GraceLauncherRoute(viewModel, hostActivity = hostActivity)
            }
        }
}
