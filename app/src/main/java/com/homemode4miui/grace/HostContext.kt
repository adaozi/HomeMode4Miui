/*
 * 宿主 Context CompositionLocal（hotfix：P1 装机长按收藏行崩溃，日志 2026-10-08 12:53:09）。
 *
 * 背景：模块运行在 com.miui.home 宿主进程内，Compose 的 LocalContext 默认是宿主
 * Context，其 resources 无法解析模块 APK 的资源 ID（R.string/R.font）——
 * stringResource 直接抛 Resources$NotFoundException 且发生在组合期，挂载熔断接不住。
 * 修复：GraceHomeRoot 把 LocalContext 覆盖为模块包 Context（createPackageContext），
 * 字符串/字体资源全部改走模块资源表。
 *
 * 本地提供宿主原 Context，供覆盖后仍需宿主侧行为的场景：
 *   - ClockFontStore 的 filesDir（必须留在宿主数据目录，宿主进程可写；模块目录
 *     属另一 UID，宿主进程写必 SecurityException，P6 字体导入依赖此目录）
 *   - WallpaperBlur 的 (context as? Activity)?.window（P4 起需要宿主窗口）
 *
 * 提供方（缺省 error() —— 组合期快速失败，装机教训见 SettingsActivity hotfix1）：
 *   - 宿主桌面：GraceHomeRoot
 *   - 模块设置页进程：SettingsActivity（provide 模块 applicationContext，
 *     ClockFontStore 等消费点安全降级——预览不含宿主导入的自定义时钟字体）
 */
package com.homemode4miui.grace

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

val LocalHostContext = staticCompositionLocalOf<Context> {
    error("LocalHostContext not provided by GraceHomeRoot")
}
