package com.homemode4miui.hook;

import com.homemode4miui.MainHook;
import com.homemode4miui.util.ModLog;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 返回桌面动画统一 —— 关闭"应用窗口飞回桌面图标"的图标回位动画（2026-11-15）。
 *
 * 症状：从应用返回桌面，**部分应用**窗口缩小后飞进桌面图标位置（图标全程可见），
 *   另一部分应用则是窗口缩小消失到屏幕中部。上游（GraceLauncher 作为第三方桌面）
 *   在 MIUI 上恒为后者，本模块出现两种表现 → 观感不一致。
 *
 * 根因（OS2 RELEASE-5.39.x / OS3 RELEASE-6.01.x dex 逆向，两版本同构）：
 *   MIUI 关闭动画的目标元素由 `com.miui.home.recents.CloseShortcutIconUtils.getCloseShortcutIcon(
 *   ComponentName, int userId, Launcher|BaseLauncher)` 解析 —— 内部走
 *   `launcher.getShowingItem(flatten, userId, 0)` + `launcher.getClosingShortcutIcon()`
 *   ，即"在宿主自己的 Workspace 里找当前正在显示的图标 View"。
 *   本模块的桌面是 Compose 自绘、盖在宿主 Workspace 之上，宿主那套图标 View 与
 *   模块图标的屏幕位置并不重合（且多数被遮住）：
 *     · 宿主的 getShowingItem 恰好命中 → 返回图标 View → 动画飞向**宿主的**图标位置；
 *     · 命中不到 → 返回 null → 原生自动回落 `DeviceConfig.getClosingAppDefaultDisappearRect()`
 *       （窗口缩到屏幕中部消失，即上游那种统一表现）。
 *   于是"是否有图标可被宿主命中"变成了随机分界 → 一半飞图标、一半缩小消失。
 *
 * 两个调用方（xref 实锤，null 均被原生兜底，不会 NPE）：
 *   ① `NavStubView.findClosingShortcutIcon(ClosingAppInfo)`（MIUI 手势导航自绘的关闭动画）
 *      → target==null 时 `mAppToHomeTargetRect.set(getClosingAppDefaultDisappearRect(mTouchRange))`；
 *   ② `WindowAnimParamsProvider.getClosingShortcutIcon(...)`（RemoteAnimation 关闭路径）
 *      → 返回 null → `findClosingAnimTarget` 返回 null → `getIconRect` 取
 *        `DeviceConfig.getClosingAppDefaultDisappearRect(...)`。
 *   因此只钩这一个静态方法即可让两条路径统一到"缩小消失"。
 *
 * 干预面：仅在自定义桌面模式（MainHook.isCustomModeActive）下返回 null；
 *   原生 MIUI 桌面模式下完全放行，MIUI 自己的图标回位动画照旧。
 *   方法缺失/包名漂移 → tryHook 记录失败并放行原逻辑，绝不阻断宿主。
 */
public final class AppCloseAnim {

    private static final String CLS_UTILS = "com.miui.home.recents.CloseShortcutIconUtils";
    private static final String M_GET_ICON = "getCloseShortcutIcon";
    /** 前几次吞掉时的诊断日志条数（确认 hook 真命中，不刷屏）。 */
    private static final int LOG_BUDGET = 3;

    private static volatile int sSuppressed;

    private AppCloseAnim() {
    }

    public static void install(ClassLoader cl) {
        Class<?> cls = XposedHelpers.findClass(CLS_UTILS, cl);
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!MainHook.isCustomModeActive()) return;
                    param.setResult(null);
                    if (sSuppressed < LOG_BUDGET) {
                        sSuppressed++;
                        ModLog.i("AppCloseAnim: icon target suppressed -> default disappear rect"
                                + " (" + sSuppressed + "/" + LOG_BUDGET + ")");
                    }
                } catch (Throwable t) {
                    ModLog.e("AppCloseAnim suppress failed", t);
                }
            }
        };
        // 参数表两版本不同（OS2 = Launcher / OS3 = BaseLauncher），按方法名匹配
        Set<XC_MethodHook.Unhook> unhooks = XposedBridge.hookAllMethods(cls, M_GET_ICON, hook);
        if (unhooks == null || unhooks.isEmpty()) {
            throw new NoSuchMethodError(CLS_UTILS + "#" + M_GET_ICON);
        }
        ModLog.i("AppCloseAnim installed: " + CLS_UTILS + "#" + M_GET_ICON
                + " (" + unhooks.size() + " overload)");
    }
}
