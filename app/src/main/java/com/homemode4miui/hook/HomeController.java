package com.homemode4miui.hook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import android.view.ViewGroup;

import com.homemode4miui.home.HomeView;
import com.homemode4miui.home.HomeViewFactory;
import com.homemode4miui.util.ModLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 底座核心：隐藏原生桌面表面（Workspace/HotSeats/抽屉视图/DropTargetBar）、
 * 在 DragLayer 上挂载/卸载 {@link HomeView}（由 HomeViewFactory 提供，UI 与底座的唯一接缝）、
 * 聚合应用增删广播。
 *
 * 负一屏（v2.9.42 清除）：右滑手势经 GestureIsolator 恒定隔离（无放行开关），
 * "自定义 UI 主动打开"预留入口 openMinusOneScreen() 因零调用方连同关闭同步链
 * （syncMinusClosed/restoreFromMinusScreen/showMinusChain/hideNatives* 与
 * MainHook #10 onMinusOneScreenViewScroll 钩子）一并删除 —— 页面指示条在自定义
 * 模式下随原生表面隐藏，负一屏在本模式下无任何入口。
 *
 * 逆向依据（OS2: RELEASE-5.39.40.10843 / OS3: RELEASE-6.01.03.1927）：
 * Launcher 有 getWorkspace()/getHotSeats()/getDragLayer() 方法，
 * 字段 mAppsView(AllAppsContainerView)、mDropTargetBar(DropTargetBar)。
 * OS3 变化：上述 Launcher 方法上移到 BaseLauncher（hook 经 HostCompat 解析）。
 */
public final class HomeController {

    private static final String TAG = "HomeMode4Miui";

    private static volatile HomeView sView;
    private static final Map<View, Integer> sOriginalVisibility = new HashMap<>();
    private static volatile BroadcastReceiver sPkgReceiver;
    /** 进入第四模式前 DecorView 的 systemUiVisibility（退出时原样还原）。 */
    private static int sRestoreSystemUi = Integer.MIN_VALUE;

    private HomeController() {
    }

    /**
     * blur-gap（v2.9.123）：原生桌面 ↔ 第四模式来回切后，本次会话内 Compose 根只被量到
     * 2296（窗口 2400），状态栏 104px 露出系统壁纸，且 decorFitsSystemWindows(false)+
     * requestLayout 均纠正不了 —— MIUI 内部某层 insets/translation 在切换后残留。
     * 用户实测：进程重启一次就恢复。于是把"切【进】第四模式"做成显式重启：
     * 只在状态沿（false→true）触发，进程冷启动首帧（null→true）不触发，天然无循环。
     */
    private static Boolean sPrevCustomActive = null;
    private static long sLastRestartAt = 0L;

    private static void scheduleRestartOnEntering(Activity launcher, boolean on) {
        try {
            boolean entering = on && sPrevCustomActive != null && !sPrevCustomActive;
            sPrevCustomActive = on;
            if (!entering) return;
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastRestartAt < 3_000) return;   // 切换连击/广播+回调双路径去抖
            sLastRestartAt = now;
            log("custom mode ON edge -> schedule launcher restart (blur-gap)");
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    log("launcher restart now (custom mode entry, HOME auto-relaunch)");
                    android.os.Process.killProcess(android.os.Process.myPid());
                } catch (Throwable t) {
                    log("launcher restart failed: " + t);
                }
            }, 600);
        } catch (Throwable t) {
            log("scheduleRestartOnEntering failed: " + t);
        }
    }

    /** 同步 UI 到目标状态：on=true 挂载自定义视图，false 卸载并还原原生表面 */
    public static void sync(Activity launcher, boolean on) {
        log("sync(on=" + on + ")");
        scheduleRestartOnEntering(launcher, on);
        try {
            if (on) {
                attach(launcher);
            } else {
                detach(launcher);
            }
        } catch (Throwable t) {
            log("sync failed: " + t);
        }
    }

    /** 应用增删广播聚合入口（MainHook onResume / PACKAGE_* 广播调用） */
    public static void notifyPackagesChanged() {
        HomeView v = sView;
        if (v != null) {
            v.post(v::onPackagesChanged);
        }
    }

    /**
     * Launcher.onPause 同步（MainHook hook 5b 调用，perf3 功耗专项）：
     * 通知挂载视图退后台 —— Compose 生命周期推到 STOPPED，时钟循环挂起、
     * widget 宿主 stopListening，后台期间零周期唤醒。onPause 是主线程生命周期
     * 回调，直接调用（无需 post）。视图未挂载/已卸载时为 no-op。
     */
    public static void notifyHomePaused() {
        HomeView v = sView;
        if (v != null) {
            v.onHomePaused();
        }
    }

    /* ---------------- Home 键回首页 hook ---------------- */

    /**
     * Launcher.onNewIntent（Home 键回桌面走此入口，onResume 不触发——Activity 未 pause）：
     * 通知挂载视图复位（两态 UI 在 onHomeIntent 里切回首页态）。
     * 找不到方法/签名漂移时静默降级。
     */
    public static void installHomeIntentSync(final ClassLoader cl) {
        // 版本路由：onNewIntent(Intent) 在 OS3 同样上移至 BaseLauncher。
        // 主 hook 异常直接抛给 tryHook 记 FAILED（不吞）。
        XposedHelpers.findAndHookMethod(HostCompat.launcherHookClass(cl,
                "onNewIntent", Intent.class), "onNewIntent", Intent.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // 仅主意图（Home 键回桌面）复位；其他 onNewIntent 不动
                            Object tag = param.args != null && param.args.length > 0
                                    ? param.args[0] : null;
                            if (!(tag instanceof Intent)
                                    || !Intent.ACTION_MAIN.equals(((Intent) tag).getAction())) {
                                return;
                            }
                            // hotfix return-anim：Intent 原样透传 —— 返回动画需要
                            // Quickstep 注入的 GestureNavContract extra（无则动画静默退场）
                            HomeView v = sView;
                            if (v != null) v.onHomeIntent((Intent) tag);
                        } catch (Throwable t) {
                            log("home intent sync failed: " + t);
                        }
                    }
                });
        log("home intent sync installed (Launcher.onNewIntent)");
        // OS3 新增路径：onNewIntentWhenAlreadyOnHome()（RELEASE-6.01.03 把"已在桌面时
        // 回首页态"拆成独立方法）。OS2 无此方法，找不到即静默跳过；与 onNewIntent
        // 同场景双触发时 onHomeIntent 为幂等复位，无副作用。
        try {
            XposedHelpers.findAndHookMethod(HostCompat.launcherHookClass(cl,
                    "onNewIntentWhenAlreadyOnHome"), "onNewIntentWhenAlreadyOnHome", new XC_MethodHook() {
                        @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            // 已在桌面回首页：无 intent（无 GestureNavContract），传 null 仅复位
                            HomeView v = sView;
                            if (v != null) v.onHomeIntent(null);
                        } catch (Throwable t) {
                            log("home intent (already-on-home) sync failed: " + t);
                        }
                    }
                    });
            log("home intent sync installed (onNewIntentWhenAlreadyOnHome)");
        } catch (Throwable t) {
            log("onNewIntentWhenAlreadyOnHome absent — skip (OS2 属正常) — " + t);
        }
    }

    /* ---------------- 挂载/卸载 ---------------- */

    private static void attach(Activity launcher) {
        // notif6：每次挂载（首挂 + 复用）都向模块进程拉取当前快照（通知/媒体/天气）。
        // 此前只在宿主 VM init 发一次 —— 宿主进程存活期内模块 listener 重连/重装
        // 模块 APK 后，宿主可能停在旧快照或空快照上（装机实锤：通知行消失）。
        try {
            launcher.sendBroadcast(com.homemode4miui.bridge.GraceBridge.snapshotRefreshIntent());
        } catch (Throwable t) {
            ModLog.e("HomeController snapshot refresh send failed", t);
        }
        // keep1：桌面挂载顺带拉起模块进程（前台应用 startService 不受后台限制；
        // 模块进程死后，用户回到桌面即自动复活 —— 主保活链路，root-free）。
        // 广播到不了一个死进程，所以拉起必须由宿主主动 startService。
        try {
            com.homemode4miui.bridge.GraceKeepAliveService.pokeFromHost(launcher);
        } catch (Throwable t) {
            ModLog.e("HomeController module wake failed", t);
        }
        // 容器解析降级链：getDragLayer() → mDragLayer 字段 → DecorView 兜底
        // （宿主版本漂移时任一环失败都有后路，且逐级留痕）
        ViewGroup container = resolveContainer(launcher);
        if (container == null) {
            log("attach aborted: no container resolved");
            return;
        }

        HomeView existing = sView;
        if (existing != null && existing.getParent() == container) {
            existing.setVisibility(View.VISIBLE);
            existing.onHomeAttached(launcher);
            existing.clearSystemBarPadding();
            // 复用路径也要重扫：原生可能在首次挂载后重建/补加了视图
            int rehidden = hideLauncherSurfaces(container);
            ensureFullBleedWindow(launcher);   // 窗口可能被原生切回 fitsSystemWindows
            log("attach: reused existing view (re-hidden " + rehidden + ")");
            return;
        }
        detach(launcher);

        // UI 与底座的唯一接缝：根视图由工厂提供（移植时只改 HomeViewFactory）
        HomeView view = HomeViewFactory.create(launcher);
        container.addView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sView = view;

        int hidden = hideLauncherSurfaces(container);
        ensureFullBleedWindow(launcher);
        view.onHomeAttached(launcher);
        view.clearSystemBarPadding();
        registerPkgReceiver(launcher);
        log("attached: container=" + container.getClass().getName()
                + ", view=" + view.getClass().getSimpleName()
                + ", siblings hidden=" + hidden);
        // 延迟补扫：捕获原生在 setupViews 之后异步重建的视图（模式切换重建等场景）
        container.postDelayed(() -> {
            HomeView v = sView;
            if (v == null || v.getParent() != container) return;
            int n = hideLauncherSurfaces(container);
            if (n > 0) log("late re-hidden " + n + " sibling(s) after attach");
            // 模式切换后原生可能再次把窗口切回 fitsSystemWindows（内容区缩到状态栏下沿），
            // 这里与其后各补一次，保证 Compose 根始终按整屏高度测量。
            ensureFullBleedWindow(launcher);
            v.clearSystemBarPadding();
            logGeometry(container);
        }, 600);
        container.postDelayed(() -> {
            HomeView v = sView;
            if (v == null || v.getParent() != container) return;
            ensureFullBleedWindow(launcher);
            v.clearSystemBarPadding();
        }, 1_500);
    }

    /**
     * 几何诊断（blur-gap 专项）：挂载视图是否真正铺满窗口。
     * 装机现象：原生桌面 ↔ 第四模式来回切后，抽屉模糊层/壁纸遮罩不覆盖状态栏条带
     * （状态栏高度那段是未处理的系统壁纸）—— 等价于我们的 ComposeView 顶边不在屏幕 0 点。
     * 这里打印 view 在屏幕上的 y、容器 padding/尺寸，区分三种根因：
     *   ① 容器有 paddingTop（DragLayer 为原生布局加的）→ view 被 padding 内缩；
     *   ② 容器自身 y>0（窗口不铺状态栏，fitsSystemWindows 类行为）；
     *   ③ 两者都为 0 但 view 高度小于屏幕 → 容器高度被裁。
     */
    private static void logGeometry(ViewGroup container) {
        try {
            View v = sView;
            if (v == null) return;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int[] cloc = new int[2];
            container.getLocationOnScreen(cloc);
            int screenH = v.getResources().getDisplayMetrics().heightPixels;
            log("geometry: viewTop=" + loc[1] + " viewH=" + v.getHeight()
                    + " | containerTop=" + cloc[1] + " containerH=" + container.getHeight()
                    + " paddingTop=" + container.getPaddingTop()
                    + " clipToPadding=" + (container instanceof android.view.ViewGroup
                        && ((ViewGroup) container).getClipToPadding())
                    + " | screenH=" + screenH);
        } catch (Throwable t) {
            log("geometry log failed: " + t);
        }
    }

    /** 容器解析：宿主 API → 宿主字段 → 框架 DecorView（版本无关的最后兜底） */
    private static ViewGroup resolveContainer(Activity launcher) {
        try {
            Object dl = XposedHelpers.callMethod(launcher, "getDragLayer");
            if (dl instanceof ViewGroup) return (ViewGroup) dl;
            log("getDragLayer() unavailable, trying mDragLayer field");
        } catch (Throwable t) {
            log("getDragLayer() failed: " + t);
        }
        try {
            Object dl = XposedHelpers.getObjectField(launcher, "mDragLayer");
            if (dl instanceof ViewGroup) return (ViewGroup) dl;
        } catch (Throwable t) {
            log("mDragLayer field failed: " + t);
        }
        try {
            ViewGroup decor = (ViewGroup) launcher.getWindow().getDecorView();
            log("falling back to DecorView container (native surfaces may stay visible)");
            return decor;
        } catch (Throwable t) {
            log("decor fallback failed: " + t);
            return null;
        }
    }

    private static void detach(Activity launcher) {
        unregisterPkgReceiver(launcher);
        HomeView view = sView;
        if (view != null) {
            try {
                view.onHomeDetached();
            } catch (Throwable t) {
                log("onHomeDetached failed: " + t);
            }
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
            sView = null;
        }
        restoreLauncherSurfaces(launcher);
    }

    /**
     * 隐藏原生桌面表面 —— 版本无关实现：
     * 遍历容器，把除挂载视图之外的所有子 View 记账后 GONE
     * （壁纸在窗口之后的系统层，不受影响；退出模式时逐一还原）。
     * 原生 getWorkspace/getHotSeats 等名字在新版本上不可靠，不再依赖。
     */
    private static int hideLauncherSurfaces(ViewGroup container) {
        int hidden = 0;
        StringBuilder names = new StringBuilder();
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child == sView || child == null) continue;
            if (child.getVisibility() == View.GONE) continue;
            setGoneRecorded(child);
            hidden++;
            if (names.length() < 160) {
                names.append(child.getClass().getSimpleName()).append(" ");
            }
        }
        if (hidden == 0) {
            log("warn: no siblings hidden (container=" + container.getClass().getName() + ")");
        } else {
            log("hidden siblings: " + hidden + " [" + names.toString().trim() + "]");
        }
        return hidden;
    }

    /**
     * 强制宿主窗口铺满整屏（含状态栏/导航栏区域）。
     *
     * 装机实锤（blur-gap）：原生桌面 ↔ 第四模式来回切后，抽屉的模糊壁纸/dim 遮罩
     * 不覆盖状态栏那一条（模糊层 rect 只有 0..2296，窗口是 2400；整块 Compose 内容
     * 被下移了一个状态栏高度）。根因不是视图层 padding，而是宿主 DecorView 回到了
     * fitsSystemWindows=true（内容区从状态栏下沿起算）—— 此时 DragLayer 的 0 点
     * 已经不在屏幕顶端，Compose 再 fillMaxSize 也够不到状态栏。
     *
     * 处理：挂载/复用时确保 decorFitsSystemWindows=false（API 30+ 直调，minSdk 33），
     * 退出第四模式按进入前的 systemUiVisibility 原样还原，原生桌面行为不受影响。
     */
    private static void ensureFullBleedWindow(Activity launcher) {
        try {
            android.view.Window w = launcher.getWindow();
            if (w == null) return;
            View decor = w.getDecorView();
            if (decor == null) return;
            if (sRestoreSystemUi == Integer.MIN_VALUE) {
                sRestoreSystemUi = decor.getSystemUiVisibility();
            }
            // 幂等：始终声明"内容铺到系统栏背后"（等价 SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|
            // LAYOUT_HIDE_NAVIGATION）。原生若切回 fitsSystemWindows=true，这一句把它掰回来。
            w.setDecorFitsSystemWindows(false);
            decor.requestApplyInsets();
            // 关键：只改 flags 不会触发内容区重新 measure —— 原生上一次布局把
            // Compose 根量成了"屏高 - 状态栏"（2296），必须 requestLayout 让它按
            // 新的 2400 重新测量，否则 blur 层永远停在旧高度。
            decor.requestLayout();
            log("window: decorFitsSystemWindows(false) enforced, restoreVis=" + sRestoreSystemUi
                    + " decor=" + decor.getWidth() + "x" + decor.getHeight());
        } catch (Throwable t) {
            log("ensureFullBleedWindow failed: " + t);
        }
    }

    /** 退出第四模式：还原进入前的 systemUiVisibility（fitsSystemWindows 状态随之恢复）。 */
    private static void restoreWindowInsets(Activity launcher) {
        try {
            if (sRestoreSystemUi == Integer.MIN_VALUE) return;
            View decor = launcher.getWindow().getDecorView();
            if (decor != null) decor.setSystemUiVisibility(sRestoreSystemUi);
        } catch (Throwable t) {
            log("restoreWindowInsets failed: " + t);
        } finally {
            sRestoreSystemUi = Integer.MIN_VALUE;
        }
    }

    /** 退出第四模式的完整还原（摘要 + 窗口 insets）。 */
    private static void restoreLauncherSurfaces(Activity launcher) {
        restoreWindowInsets(launcher);
        for (View v : new ArrayList<>(sOriginalVisibility.keySet())) {
            restore(v);
        }
        sOriginalVisibility.clear();
    }

    private static void setGoneRecorded(View v) {
        if (v == null) return;
        sOriginalVisibility.put(v, v.getVisibility());
        v.setVisibility(View.GONE);
    }

    private static void restore(View v) {
        if (v == null) return;
        Integer old = sOriginalVisibility.get(v);
        if (old != null) {
            v.setVisibility(old);
        }
    }

    private static void registerPkgReceiver(Context ctx) {
        if (sPkgReceiver != null) return;
        IntentFilter f = new IntentFilter();
        f.addDataScheme("package");
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                notifyPackagesChanged();
            }
        };
        // hotfix：用 ApplicationContext 注册/反注册 —— Launcher 重建时 detach 传入的
        // 是新 Activity 的 ContextImpl，用它反注册旧 ContextImpl 注册的接收器必抛
        // IllegalArgumentException（曾被吞 → 旧接收器持旧 Activity 泄漏 + 广播双份分发）
        ctx.getApplicationContext().registerReceiver(r, f);
        sPkgReceiver = r;
    }

    private static void unregisterPkgReceiver(Context ctx) {
        BroadcastReceiver r = sPkgReceiver;
        if (r != null) {
            try {
                ctx.getApplicationContext().unregisterReceiver(r);
            } catch (Throwable t) {
                com.homemode4miui.util.ModLog.e("HomeController unregisterPkgReceiver failed", t);
            }
            sPkgReceiver = null;
        }
    }

    // 审计修缮（2026-10-16）：改走 ModLog 双写（XposedBridge + logcat tag HomeMode4Miui）——
    // 此前只走 XposedBridge.log，LogExporter 的 logcat 兜底来源看不到 sync/attached 系列宿主日志。
    private static void log(String msg) {
        com.homemode4miui.util.ModLog.i(msg);
    }
}
