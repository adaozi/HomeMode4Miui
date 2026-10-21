/*
 * 壁纸效果层（v2.9.43-refactor1 从 GraceLauncherRoute 抽离，保语义搬运）：
 * dimfix / hotfix6 / perf1 五轮装机调试的视觉层，注释与 z 序语义原样随迁。
 * 结构：
 *   - WindowWallpaperBlurEffect：宿主窗口跨窗口模糊半径（S+，MIUI 上静默 no-op 保留）
 *   - rememberWallpaperBlurBitmap：Compose 层壁纸快照 fallback（异步加载 + 分辨率缩放）
 *   - rememberResolvedWallpaperTint：遮罩色解析（hotfix6 手写语义 + S7 自定义色）
 *   - WallpaperEffectLayers：渲染层（模糊位图 + dim 遮罩），调用点必须保持在
 *     BoxWithConstraints 之外的兄弟层位置（dimfix 定案：不吃容器 insets，覆盖全屏）。
 */
package com.homemode4miui.grace

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.wallpaperTint
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 宿主窗口跨窗口模糊半径（Android S+；MIUI home 窗口静默 no-op，其他 ROM 生效时
 * 位图层反正完全覆盖，视觉一致）。半径随效果可见度同步缩放，退disposal清零。
 */
@Composable
internal fun WindowWallpaperBlurEffect(
    hostActivity: Activity?,
    blurOn: Boolean,
    blurRadiusDp: Int,
    effectAlpha: Float,
) {
    if (hostActivity == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
    val wallpaperBlurRadiusPx = with(LocalDensity.current) {
        (if (blurOn) blurRadiusDp.dp * effectAlpha else 0.dp).roundToPx()
    }.coerceIn(0, 150)
    androidx.compose.runtime.SideEffect {
        hostActivity.window.setBackgroundBlurRadius(wallpaperBlurRadiusPx)
    }
    androidx.compose.runtime.DisposableEffect(hostActivity) {
        onDispose { hostActivity.window.setBackgroundBlurRadius(0) }
    }
}

/**
 * 壁纸模糊 fallback 位图（Compose 层壁纸快照）。
 *
 * hotfix6：MIUI home 窗口 setBackgroundBlurRadius 无效（跨窗口模糊可用性与 MIUI
 * 自身策略相关）→ 原版路径降级，补 Niagara 系通用做法：系统壁纸取成 Bitmap，
 * 抽屉打开时以 RenderEffect（Modifier.blur）模糊层淡入覆盖真实壁纸。半径用设置值
 * （0..48dp，与设置页滑杆一致），可见度跟随 effectAlpha —— 与原版"模糊随抽屉渐入"
 * 观感一致；静态壁纸逐像素对齐，动态壁纸降级为快照。
 *
 * perf1（功耗/冷启动专项根因修复）：原实现 remember{} 在首组合【主线程】同步取整屏
 * 壁纸 drawable 并 toBitmap（binder + 全分辨率解码，4K 屏可达数十 MB 分配）—— 桌面
 * 每次重启的首帧最大单点阻塞。改为 LaunchedEffect + Dispatchers.IO 异步加载，并按
 * 屏幕分辨率缩放（模糊层视觉细节本被 blur 抹平，1x 屏幕分辨率足够）。视觉零回退：
 * 模糊层初始 alpha=0（抽屉打开才渐入），异步加载完成前的窗口期内该层本就不可见。
 */
@Composable
internal fun rememberWallpaperBlurBitmap(hostContext: Context?, enabled: Boolean): State<Bitmap?> {
    val bitmap = androidx.compose.runtime.remember { mutableStateOf<Bitmap?>(null) }
    // 模块侧根因修复：原 LaunchedEffect 只 key 在 hostContext —— 壁纸更换后位图永远
    // 是旧快照。接入 rememberWallpaperVersion（颜色监听 + 换壁纸广播双通道）驱动重载；
    // 重载期间保留旧位图，新位图就绪后无缝替换（不闪空窗）。
    val wallpaperVersion = com.galaxyrio.gracelauncher.ui.theme.rememberWallpaperVersion()
    if (enabled && hostContext != null) {
        LaunchedEffect(hostContext, wallpaperVersion) {
            bitmap.value = withContext(Dispatchers.IO) {
                runCatching {
                    val drawable = WallpaperManager.getInstance(hostContext).drawable
                        ?: return@withContext null
                    val dm = hostContext.resources.displayMetrics
                    drawable.toBitmap(dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
                }.onFailure {
                    ModLog.i("WallpaperBlur fallback bitmap load failed: ${it.message}")
                }.getOrNull()
            }
        }
    } else {
        // hotfix：composition 期间直接写 MutableState 是 Compose 明令禁止的
        //（会触发额外失效回溯），包进 SideEffect
        SideEffect { bitmap.value = null }
    }
    return bitmap
}

/**
 * 壁纸遮罩色（hotfix6：原版 LauncherScreen.kt:623-629 wallpaperTint）。
 * 用户指正 + 原版实锤：dim 不是黑 scrim，是 primary 的色调调整 —— darkText（亮字）
 * 用 tone 10 暗罩、暗字用 tone 95 亮罩，两种文字模式都对壁纸有对比度。
 */
@Composable
internal fun rememberResolvedWallpaperTint(wallpaperDimColor: com.galaxyrio.gracelauncher.data.IconColor?): Color {
    val appearance = LocalLauncherAppearance.current
    val primary = MaterialTheme.colorScheme.primary
    // 上游同步 v1.1.8 S7（v1.1.7 特性）：遮罩色可自定义 —— wallpaperDimColor 非空时
    // 走 IconColor.resolve（automatic 仍兜底），语义与 hotfix6 手写版一致，改走上游扩展。
    return remember(primary, appearance.darkText, wallpaperDimColor) {
        appearance.wallpaperTint(primary, wallpaperDimColor)
    }
}

/**
 * 渲染层：模糊位图 + dim 遮罩。调用点必须保持在列表容器（BoxWithConstraints）之外的
 * 兄弟层位置 —— dimfix：不吃容器 safeDrawing insets padding，壁纸效果覆盖全屏（含
 * 状态栏/导航栏/手势区背后），层级与绘制顺序照搬原版（效果层最底 → 列表容器 → overlays）。
 */
@Composable
internal fun WallpaperEffectLayers(
    blurBitmap: Bitmap?,
    blurRadiusDp: Int,
    effectAlpha: Float,
    tint: Color,
    dimEnabled: Boolean,
    dimAmount: Int,
) {
    // blur-gap 专项诊断：Compose 侧认为的窗口尺寸（与视图层 geomery 日志对照）
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    blurBitmap?.let { wallpaperBlur ->
        Image(
            bitmap = wallpaperBlur.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
                .onGloballyPositioned {
                    // blur-gap 看门狗：正常恒为 0,0-windowW,windowH；顶边>0 或高度不足
                    //（= 状态栏条带会露系统壁纸）才打日志，避免常规布局刷屏。
                    val r = it.boundsInWindow()
                    if (r.top != 0f || it.size.height != windowSize.height || it.size.width != windowSize.width) {
                        ModLog.i("blurLayer ANOMALY rect=" + r + " size=" + it.size + " window=" + windowSize)
                    }
                }
                .graphicsLayer { alpha = effectAlpha }
                .blur(blurRadiusDp.coerceIn(0, 48).dp),
        )
    }
    val dimAlpha = if (dimEnabled) dimAmount.coerceIn(0, 100) / 100f * effectAlpha else 0f
    Box(Modifier.fillMaxSize().background(tint.copy(alpha = dimAlpha)))
}
