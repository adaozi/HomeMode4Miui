package com.homemode4miui.hook;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import com.homemode4miui.home.HomeView;
import com.homemode4miui.home.HomeViewFactory;
import com.homemode4miui.util.ModLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 底座核心：隐藏原生桌面表面（Workspace/HotSeats/抽屉视图/DropTargetBar）、
 * 在 DragLayer 上挂载/卸载 {@link HomeView}（由 HomeViewFactory 提供，UI 与底座的唯一接缝）、
 * 聚合应用增删广播、右滑打开负一屏（MinusOneScreenView）。
 *
 * 逆向依据（OS2: RELEASE-5.39.40.10843 / OS3: RELEASE-6.01.03.1927）：
 * Launcher 有 getWorkspace()/getHotSeats()/getDragLayer() 方法，
 * 字段 mAppsView(AllAppsContainerView)、mDropTargetBar(DropTargetBar)；
 * 负一屏 = mMinusOneScreenView(MinusOneScreenView，setupViews 由布局 inflate)，
 * PA 内容经 addMinusOneView → loadPaView 反射装载 AssistHolderView 到 mMinusViewGroup；
 * openMinusOne() = workspace.snapToScreen(0) + 自身 snapToScreen(0)，
 * 宿主页面指示条（MultiSeekBarIndicator）点击即走此入口。
 * OS3 变化：上述 Launcher 方法上移到 BaseLauncher（hook 经 HostCompat 解析），
 * getDragLayer()/getMinusOneScreenView()/addMinusOneView()/isMinusScreenShowing()/
 * isUseMiuiMinusScreen() 两版本均在，反射调用不受影响。
 */
public final class HomeController {

    private static final String TAG = "HomeMode4Miui";

    private static volatile HomeView sView;
    private static final Map<View, Integer> sOriginalVisibility = new HashMap<>();
    private static volatile BroadcastReceiver sPkgReceiver;
    /** 负一屏打开期间的可见性还原账本（祖先链 + 被临时隐藏的原生表面） */
    private static final Map<View, Integer> sMinusRestore = new HashMap<>();
    /** 负一屏是否处于"我们打开"的状态 */
    private static volatile boolean sMinusOpen;
    private static volatile long sMinusOpenedAt;
    /** 一次性日志开关 */
    private static volatile boolean sMinusNoUseLogged;

    private HomeController() {
    }

    /** 同步 UI 到目标状态：on=true 挂载自定义视图，false 卸载并还原原生表面 */
    public static void sync(Activity launcher, boolean on) {
        log("sync(on=" + on + ")");
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

    /* ---------------- 右滑打开负一屏（自定义 UI 的可用宿主能力） ---------------- */

    /**
     * 自定义 UI 右滑手势的落点：复刻宿主"页面指示条点击打开负一屏"的完整序列。
     * 【语义已固化】负一屏手势恒定隔离（无放行开关），自定义 UI 可直接调用本方法。
     */
    public static void openMinusOneScreen() {
        Activity launcher = currentLauncher();
        if (launcher == null) return;
        try {
            // 智能助理（负一屏内容源）未启用时不动作，避免打开空壳页
            Object use = callMethodSafe(launcher, "isUseMiuiMinusScreen");
            if (Boolean.FALSE.equals(use)) {
                logOnceNoUse();
                return;
            }
            Object minus = callMethodSafe(launcher, "getMinusOneScreenView");
            if (!(minus instanceof View)) {
                log("openMinusOne: MinusOneScreenView not found");
                return;
            }
            // PA 内容惰性装载（宿主 mAdded 账本保证幂等）
            callMethodSafe(launcher, "addMinusOneView");

            showMinusChain(launcher, (View) minus);
            hideNativesForMinus(launcher, (View) minus);

            HomeView v = sView;
            if (v != null) v.setVisibility(View.GONE); // 让位：负一屏在其下方 z 序

            sMinusOpen = true;
            sMinusOpenedAt = SystemClock.uptimeMillis();
            callMethodSafe(minus, "openMinusOne");
            log("openMinusOne fired");
        } catch (Throwable t) {
            ModLog.e("openMinusOneScreen failed", t);
            restoreFromMinusScreen(launcher);
        }
    }

    /** 负一屏滚动回调同步：确认已关闭时还原桌面视图与原生表面（onResume 为兜底还原点） */
    public static void syncMinusClosed(Object launcherObj) {
        if (!sMinusOpen) return;
        try {
            // 打开动画宽限期内不判定（isMinusScreenShowing 语义未逆向，防误判提前还原）
            if (SystemClock.uptimeMillis() - sMinusOpenedAt < 800) return;
            Object showing = callMethodSafe(launcherObj, "isMinusScreenShowing");
            if (Boolean.TRUE.equals(showing)) return;
            restoreFromMinusScreen(launcherObj instanceof Activity ? (Activity) launcherObj : null);
        } catch (Throwable t) {
            ModLog.e("syncMinusClosed failed", t);
        }
    }

    private static void restoreFromMinusScreen(Activity launcher) {
        if (!sMinusOpen) return;
        sMinusOpen = false;
        try {
            for (View v : new ArrayList<>(sMinusRestore.keySet())) {
                Integer old = sMinusRestore.get(v);
                if (old != null) v.setVisibility(old);
            }
            sMinusRestore.clear();
            HomeView v = sView;
            if (v != null) v.setVisibility(View.VISIBLE);
            log("minus screen closed -> desktop restored");
        } catch (Throwable t) {
            ModLog.e("restoreFromMinusScreen failed", t);
        }
    }

    /**
     * 显示负一屏祖先链：从 MinusOneScreenView 向上走到隐藏容器的直接子级，
     * 逐级记录原可见性后置 VISIBLE（负一屏静置时本就"可见但不显示内容"，为宿主原生状态）。
     */
    private static void showMinusChain(Activity launcher, View minus) {
        ViewGroup container = resolveContainer(launcher);
        if (container == null) return;
        View top = ancestorUnderContainer(minus, container);
        if (top == null) {
            log("minus screen not under hiding container (chain visible natively)");
            return;
        }
        View v = minus;
        while (true) {
            if (v.getVisibility() != View.VISIBLE) {
                sMinusRestore.put(v, v.getVisibility());
                v.setVisibility(View.VISIBLE);
            }
            if (v == top) break;
            Object p = v.getParent();
            if (!(p instanceof View)) break;
            v = (View) p;
        }
    }

    /** 负一屏打开期间，把同容器的原生表面（Workspace/HotSeats 等）按名临时隐藏 */
    private static void hideNativesForMinus(Activity launcher, View minus) {
        ViewGroup container = resolveContainer(launcher);
        if (container == null) return;
        View top = ancestorUnderContainer(minus, container);
        if (top == null) return; // 负一屏不在隐藏容器内 → 无需处理
        hideNativeBranch(top, minus);
    }

    /** 从 minus 向上找"隐藏容器的直接子级"（找不到返回 null） */
    private static View ancestorUnderContainer(View minus, ViewGroup container) {
        View walker = minus;
        while (walker != null) {
            Object p = walker.getParent();
            if (p == container) return walker;
            if (!(p instanceof View)) return null;
            walker = (View) p;
        }
        return null;
    }

    /** 分支内按类名隐藏原生表面（命中即整棵隐藏，不再下钻）；负一屏路径天然不匹配 */
    private static void hideNativeBranch(View root, View minus) {
        String name = root.getClass().getName();
        if (isNativeSurfaceName(name)) {
            if (root.getVisibility() != View.GONE && !sMinusRestore.containsKey(root)) {
                sMinusRestore.put(root, root.getVisibility());
                root.setVisibility(View.GONE);
            }
            return;
        }
        if (root == minus) return; // 负一屏自身及其祖先不隐藏
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                hideNativeBranch(g.getChildAt(i), minus);
            }
        }
    }

    /** 原生表面类名特征（负一屏打开期间需让位的宿主视图） */
    private static boolean isNativeSurfaceName(String qualified) {
        return qualified.contains("Workspace")
                || qualified.contains("HotSeat")
                || qualified.contains("DropTargetBar")
                || qualified.contains("pageindicators")
                || qualified.contains("SearchEdgeLayout");
    }

    /* ---------------- 负一屏关闭同步 hook（MainHook #10 调用） ---------------- */

    public static void installMinusCloseSync(final ClassLoader cl) {
        // 版本路由：OS2 声明于 Launcher，OS3 上移至 BaseLauncher（HostCompat 解析声明类）
        // 异常直接抛给 MainHook.tryHook 记 FAILED（此前内部吞掉会打出误导性 installed）
        XposedHelpers.findAndHookMethod(HostCompat.launcherHookClass(cl,
                "onMinusOneScreenViewScroll"), "onMinusOneScreenViewScroll", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            syncMinusClosed(param.thisObject);
                        } catch (Throwable t) {
                            log("minus close sync callback failed: " + t);
                        }
                    }
                });
        log("minus close sync installed (Launcher.onMinusOneScreenViewScroll)");
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
                            HomeView v = sView;
                            if (v != null) v.onHomeIntent();
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
                                HomeView v = sView;
                                if (v != null) v.onHomeIntent();
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
            // 复用路径也要重扫：原生可能在首次挂载后重建/补加了视图
            int rehidden = hideLauncherSurfaces(container);
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
        view.onHomeAttached(launcher);
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
        }, 600);
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

    private static void restoreLauncherSurfaces(Activity launcher) {
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

    /* ---------------- 反射工具（宿主方法可能非 public，统一 setAccessible） ---------------- */

    private static Activity currentLauncher() {
        HomeView v = sView;
        Context ctx = v != null ? v.getContext() : null;
        return ctx instanceof Activity ? (Activity) ctx : null;
    }

    private static Object callMethodSafe(Object target, String method) throws Throwable {
        if (target == null) return null;
        Method m = findMethodDeep(target.getClass(), method);
        if (m == null) throw new NoSuchMethodException(target.getClass().getName() + "." + method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /**
     * 沿父类链找无参方法（惰性）：getDeclaredMethod 单方法精确匹配，
     * 【严禁】getDeclaredMethods() 全量扫描 —— 宿主类链有方法引用类路径缺失的
     * com.miui.newhome.*（看点/内容中心独立包），全量扫描必炸 NoClassDefFoundError
     * （OS2 5.39.40 实锤，与 HostCompat.declares 同一条铁律）。
     */
    private static Method findMethodDeep(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static void logOnceNoUse() {
        if (!sMinusNoUseLogged) {
            sMinusNoUseLogged = true;
            log("openMinusOne skipped: personal assistant off (isUseMiuiMinusScreen=false)");
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
        ctx.registerReceiver(r, f);
        sPkgReceiver = r;
    }

    private static void unregisterPkgReceiver(Context ctx) {
        BroadcastReceiver r = sPkgReceiver;
        if (r != null) {
            try {
                ctx.unregisterReceiver(r);
            } catch (Throwable ignored) {
            }
            sPkgReceiver = null;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
