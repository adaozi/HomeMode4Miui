package com.homemode4miui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.miui.home.launcher.allapps.LauncherMode;
import com.miui.home.launcher.allapps.LauncherModeController;
import com.homemode4miui.hook.DesktopModeInjector;
import com.homemode4miui.hook.GestureIsolator;
import com.homemode4miui.hook.HomeController;
import com.homemode4miui.mode.CustomLauncherMode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * MIUI Home 自定义桌面模式底座 —— 原生式第四桌面模式（无借壳）。
 *
 * Hook 点清单（共 6 + 3 个实验性设置页注入）：
 *  1. LauncherModeController.getAllModes()            after  → 注册第四模式
 *  2. 模式裁决（自适应）：优先 hook 任意签名的 loadCurrentLauncherMode；
 *     版本漂移找不到时兜底 hook enableAndResetCache() 改写 sCurrentMode
 *  3. Launcher.setupViews()                           after  → 挂载/同步自定义视图
 *  4. Launcher.onLauncherModeChanged()                after  → 原生模式切换链同步 UI
 *  5. Launcher.onResume()                             after  → 回桌面校准 + 通知应用变更
 *  9. SuperDraglayer.findActiveController             before → 手势隔离闸门：
 *     自定义模式激活时宿主手势控制器（下拉搜索/上滑抽屉/横滑负一屏/下拉通知栏）
 *     永不认领手势流，消除与挂载视图的手势互相捣乱；负一屏可按选项放行，
 *     另含 isSupportShowHomeFeed 使能闸放行（hook/GestureIsolator.java）
 *  6-8. DesktopModeInjector（实验性）：设置页"桌面模式"卡片注入第四个选项
 *  10. Launcher.onMinusOneScreenViewScroll            after  → 负一屏关闭同步还原
 *  11. Launcher.onNewIntent                           after  → Home 键回首页态回调
 *
 * 工程守则（两道闸）：每个 hook 独立 try/catch —— 单点失败只降级该功能并记录日志，
 * 绝不因一个 hook 未命中而放弃其余 hook（真机 5.39.40 之外的宿主版本方法签名可能漂移）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "HomeMode4Miui";
    private static final String HOME_PKG = "com.miui.home";
    public static final String ACTION_TOGGLE = "com.homemode4miui.ACTION_TOGGLE_MODE";
    public static final String EXTRA_ON = "custom_mode_on";
    /** 手势选项：设置负一屏手势拦截（GestureIsolator 实时读取宿主 SP） */
    public static final String ACTION_SET_FEED_INTERCEPT = "com.homemode4miui.ACTION_SET_FEED_INTERCEPT";
    public static final String EXTRA_FEED_INTERCEPT = "custom_feed_intercept";
    /** gate1：root 保活就绪标志（模块首页 su 探测成功后写入宿主 SP） */
    public static final String ACTION_SET_ROOT_READY = "com.homemode4miui.ACTION_SET_ROOT_READY";
    public static final String EXTRA_READY = "root_ready";
    /** gate6：MIUI 自启动就绪标志（模块首页 AppOps 10008 探测 GRANTED 后写入宿主 SP） */
    public static final String ACTION_SET_AUTOSTART_READY = "com.homemode4miui.ACTION_SET_AUTOSTART_READY";
    /** gate4：开启被权限门槛拒绝的回执（宿主 → 首页，让"开关开着却没生效"可见） */
    public static final String ACTION_TOGGLE_REJECTED = "com.homemode4miui.ACTION_TOGGLE_REJECTED";
    public static final String EXTRA_GATE_REASON = "gate_reason";
    /** gate1：hook 存活探测（模块首页 ping → 宿主回 live，供首页前置条件区块回显） */
    public static final String ACTION_HOOK_PING = "com.homemode4miui.ACTION_HOOK_PING";
    public static final String ACTION_HOOK_LIVE = "com.homemode4miui.ACTION_HOOK_LIVE";
    /** gate2：pong 附带宿主版本（首页回显，顺带定位"宿主还是旧代码"类问题） */
    public static final String EXTRA_HOST_VERSION = "host_version";

    /** 当前 Launcher 实例（setupViews 时缓存，供广播切换用） */
    private static volatile Activity sLauncher;
    private static boolean sReceiverRegistered;
    /** 裁决锚点是否已安装（未安装时 isCustomModeActive 回退为直读 SP 标志） */
    private static volatile boolean sAdjudicationInstalled;

    /** 单个 hook 任务（允许抛 Throwable，由 tryHook 统一隔离降级） */
    private interface HookTask {
        void run() throws Throwable;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!HOME_PKG.equals(lpp.packageName)) return;
        log("loaded into " + lpp.packageName + " / " + lpp.processName
                + " | host " + hostVersion(lpp));
        final ClassLoader cl = lpp.classLoader;

        // 每个 hook 独立降级：一处失败不影响其余（真机版本漂移防护）
        tryHook("1.getAllModes", new HookTask() {
            @Override public void run() { hookGetAllModes(); }
        });
        tryHook("2.modeAdjudication", new HookTask() {
            @Override public void run() { hookModeAdjudication(cl); }
        });
        tryHook("3.Launcher.setupViews", new HookTask() {
            @Override public void run() { hookLauncher(cl, "setupViews", mLauncherUiSync); }
        });
        tryHook("4.Launcher.onLauncherModeChanged", new HookTask() {
            @Override public void run() { hookLauncher(cl, "onLauncherModeChanged", mLauncherUiSync); }
        });
        tryHook("5.Launcher.onResume", new HookTask() {
            @Override public void run() {
                XposedHelpers.findAndHookMethod(LauncherClsHolder.get(cl), "onResume", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity launcher = (Activity) param.thisObject;
                            HomeController.sync(launcher, isCustomModeActive());
                            HomeController.notifyPackagesChanged();
                        } catch (Throwable t) {
                            log("onResume callback failed: " + t);
                        }
                    }
                });
            }
        });
        tryHook("9.SuperDraglayer.findActiveController (手势隔离)", new HookTask() {
            @Override public void run() { GestureIsolator.inject(cl); }
        });
        tryHook("10.Launcher.onMinusOneScreenViewScroll (负一屏关闭同步)", new HookTask() {
            @Override public void run() { HomeController.installMinusCloseSync(cl); }
        });
        tryHook("11.Launcher.onNewIntent (Home 键回首页态)", new HookTask() {
            @Override public void run() { HomeController.installHomeIntentSync(cl); }
        });
        tryHook("6-8.DesktopModeInjector", new HookTask() {
            @Override public void run() { DesktopModeInjector.inject(cl); } // 实验性：失败自动跳过
        });

        // gate1：hook 全部安装完成 → 广播存活（模块进程若活着即收到；
        // 进程不在时由模块首页 ping/pong 兜底探测）
        try {
            Context app = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl), "currentApplication");
            if (app != null) {
                app.sendBroadcast(new Intent(ACTION_HOOK_LIVE).setPackage("com.homemode4miui")
                        .putExtra(EXTRA_READY, true));
            }
        } catch (Throwable t) {
            log("hook live broadcast failed: " + t);
        }
    }

    /** 单 hook 隔离执行：成功/失败均记日志，失败只降级该功能 */
    private static void tryHook(String name, HookTask task) {
        try {
            task.run();
            log("hook installed: " + name);
        } catch (Throwable t) {
            log("hook FAILED (degraded): " + name + " -> " + t);
        }
    }

    /**
     * 当前模式是否为自定义模式：
     *   - 裁决锚点已装 → 读宿主 getCurrentMode()（保持极简优先级语义）；
     *   - 锚点全部缺失（宿主大版本重构）→ 回退直读模块写入宿主 SP 的标志位。
     */
    public static boolean isCustomModeActive() {
        if (sAdjudicationInstalled) {
            try {
                return LauncherModeController.getCurrentMode() == CustomLauncherMode.getInstance();
            } catch (Throwable ignored) {
            }
        }
        Activity launcher = sLauncher;
        // gate1：回退分支同样过权限门槛（权限丢失时即使 SP 开关在也回落原生）
        return launcher != null && ModePrefs.isModeEnabled(launcher)
                && PermissionGate.customModeAllowed(launcher);
    }

    /* ================================================================
     * ① 模式注册：getAllModes 追加自定义模式实例
     * ================================================================ */

    private void hookGetAllModes() {
        XposedHelpers.findAndHookMethod(LauncherModeController.class, "getAllModes",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            LauncherMode[] orig = (LauncherMode[]) param.getResult();
                            if (contains(orig, CustomLauncherMode.getInstance())) return;
                            LauncherMode[] out = java.util.Arrays.copyOf(orig, orig.length + 1);
                            out[orig.length] = CustomLauncherMode.getInstance();
                            param.setResult(out);
                        } catch (Throwable t) {
                            log("getAllModes callback failed: " + t);
                        }
                    }
                });
    }

    /* ================================================================
     * ② 模式裁决（三级锚点自适应，版本漂移防护）
     *    a) loadCurrentLauncher*（任意签名，替换返回值）
     *    b) *resetcache*（任意返回类型，改写当前模式字段）
     *    c) getcurrentmode() 访问器（替换返回值）
     *    全部落空 → dump 控制器全部成员到日志，供下一轮精准适配
     * ================================================================ */

    private void hookModeAdjudication(final ClassLoader cl) {
        final Class<?> ctrl = LauncherModeController.class;
        String n;

        // a) loadCurrentLauncher*：原生裁决主入口（替换返回值，ELDERLYMAN 放行）
        for (Method m : ctrl.getDeclaredMethods()) {
            n = m.getName().toLowerCase();
            if (!n.contains("loadcurrentlauncher")) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!LauncherMode.class.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, mAdjudicationResultHook);
            sAdjudicationInstalled = true;
            log("adjudication via " + m.getName() + java.util.Arrays.toString(m.getParameterTypes()));
            return;
        }

        // b) *resetcache*：写缓存入口（改写当前模式静态字段）
        for (Method m : ctrl.getDeclaredMethods()) {
            n = m.getName().toLowerCase();
            if (!n.contains("resetcache")) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = contextOf(param, null);
                        if (ctx == null || !ModePrefs.isModeEnabled(ctx) || !notElderlyMan()) return;
                        java.lang.reflect.Field f = sCurrentModeField();
                        if (f != null) f.set(null, CustomLauncherMode.getInstance());
                    } catch (Throwable t) {
                        log("resetcache callback failed: " + t);
                    }
                }
            });
            sAdjudicationInstalled = true;
            log("adjudication via " + m.getName() + " (field rewrite)");
            return;
        }

        // c) getcurrentmode()：全局访问器替换（自定义模式关闭时完全透明）
        for (Method m : ctrl.getDeclaredMethods()) {
            n = m.getName().toLowerCase();
            if (!n.contains("getcurrentmode")) continue;
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterTypes().length != 0) continue;
            if (!LauncherMode.class.isAssignableFrom(m.getReturnType())) continue;
            m.setAccessible(true);
            XposedBridge.hookMethod(m, mAdjudicationResultHook);
            sAdjudicationInstalled = true;
            log("adjudication via " + m.getName() + " (accessor replace)");
            return;
        }

        dumpMembers(ctrl);
        throw new NoSuchMethodError(
                "no known adjudication anchor in host LauncherModeController (members dumped to log)");
    }

    /** 裁决回调（替换返回值）：极简优先级保持原生语义，自定义模式关闭时透明。
     *  gate1：权限不齐（通知授权/root 标志丢失）→ 替换结果回落原生命中项 ——
     *  用户在系统设置撤销通知权限后，桌面无需重启自动回原生。 */
    private final XC_MethodHook mAdjudicationResultHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Context ctx = contextOf(param, null);
                if (ctx == null) return;
                LauncherMode nativeResult = (LauncherMode) param.getResult();
                if (nativeResult != LauncherModeController.ELDERLYMAN
                        && ModePrefs.isModeEnabled(ctx)
                        && PermissionGate.customModeAllowed(ctx)) {
                    param.setResult(CustomLauncherMode.getInstance());
                }
            } catch (Throwable t) {
                log("adjudication callback failed: " + t);
            }
        }
    };

    /** 极简（ELDERLYMAN）守卫：读取失败时放行并降级（真机无对应 API 时的取舍） */
    private static boolean notElderlyMan() {
        try {
            return LauncherModeController.getCurrentMode() != LauncherModeController.ELDERLYMAN;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 定位宿主当前模式字段（版本漂移防护）：
     * 扫描"静态 + LauncherMode 类型 + 名称含 CurrentMode"的字段。
     */
    private static java.lang.reflect.Field sCurrentModeField() throws Throwable {
        for (java.lang.reflect.Field f : LauncherModeController.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!LauncherMode.class.isAssignableFrom(f.getType())) continue;
            if (f.getName().toLowerCase().contains("currentmode")) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    /** 从 hook 上下文取宿主 Context：优先方法参数，否则宿主 Application.getInstance() */
    private static Context contextOf(XC_MethodHook.MethodHookParam param, ClassLoader cl) {
        try {
            if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                return (Context) param.args[0];
            }
            if (cl == null) cl = LauncherModeController.class.getClassLoader();
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.miui.home.launcher.Application", cl), "getInstance");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 诊断：把宿主控制器全部成员 dump 进 LSPosed 日志（版本漂移时下一轮精准适配用） */
    private static void dumpMembers(Class<?> ctrl) {
        try {
            StringBuilder sb = new StringBuilder("host LauncherModeController members:\n");
            for (Method m : ctrl.getDeclaredMethods()) {
                sb.append("  M ").append(m.getName())
                        .append(java.util.Arrays.toString(m.getParameterTypes()))
                        .append(" -> ").append(m.getReturnType().getName()).append('\n');
            }
            for (java.lang.reflect.Field f : ctrl.getDeclaredFields()) {
                sb.append("  F ").append(f.getName()).append(" : ")
                        .append(f.getType().getName()).append('\n');
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("dumpMembers failed: " + t);
        }
    }

    /** 宿主桌面版本号（用于日志比对签名漂移来源） */
    private static String hostVersion(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", lpp.classLoader),
                    "currentApplication");
            if (ctx == null) return "version-unknown(app-not-ready)";
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Throwable t) {
            return "version-unknown(" + t + ")";
        }
    }

    /* ================================================================
     * ③④ Launcher UI 同步（共用回调；onResume 在 ⑤ 单独处理带刷新）
     * ================================================================ */

    private final XC_MethodHook mLauncherUiSync = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                Activity launcher = (Activity) param.thisObject;
                if (launcher == null) return;
                sLauncher = launcher;
                registerToggleReceiver(launcher);
                HomeController.sync(launcher, isCustomModeActive());
            } catch (Throwable t) {
                log("launcher ui sync callback failed: " + t);
            }
        }
    };

    private void hookLauncher(ClassLoader cl, String method, XC_MethodHook hook) {
        XposedHelpers.findAndHookMethod(LauncherClsHolder.get(cl), method, hook);
    }

    /** Launcher 类惰性缓存（findClass 失败即抛给 tryHook 降级） */
    private static final class LauncherClsHolder {
        static volatile Class<?> sCls;

        static Class<?> get(ClassLoader cl) {
            Class<?> c = sCls;
            if (c == null) {
                synchronized (LauncherClsHolder.class) {
                    if (sCls == null) {
                        sCls = XposedHelpers.findClass("com.miui.home.launcher.Launcher", cl);
                    }
                    c = sCls;
                }
            }
            return c;
        }
    }

    /* ================================================================
     * 模块 App 的实时切换广播
     * ================================================================ */

    private void registerToggleReceiver(Context ctx) {
        if (sReceiverRegistered) return; // Launcher recreate 时防重复注册
        sReceiverRegistered = true;
        try {
            IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
            filter.addAction(ACTION_SET_FEED_INTERCEPT);
            filter.addAction(ACTION_SET_ROOT_READY);
            filter.addAction(ACTION_SET_AUTOSTART_READY);
            filter.addAction(ACTION_HOOK_PING);
            // targetSdk 34 要求：非系统广播必须显式声明导出标志
            // （发送方是模块 App，故需 RECEIVER_EXPORTED 才能收到显式包名广播）
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    // keep1：首页任何交互都顺带确认模块进程活着（模块死了广播
                    // 本就收不到，此调用只在宿主侧执行 —— 60s 防抖在 poke 内部）
                    com.homemode4miui.bridge.GraceKeepAliveService.pokeFromHost(c);
                    String action = i.getAction();
                    // gate1：首页 hook 存活探测 → 立即回 pong（带宿主版本供回显诊断）
                    if (ACTION_HOOK_PING.equals(action)) {
                        Intent pong = new Intent(ACTION_HOOK_LIVE).setPackage("com.homemode4miui")
                                .putExtra(EXTRA_READY, true)
                                .putExtra(EXTRA_HOST_VERSION, hostVersionSafe());
                        try {
                            c.sendBroadcast(pong);
                            log("hook ping -> live pong sent (host " + pong.getStringExtra(EXTRA_HOST_VERSION) + ")");
                        } catch (Throwable t) {
                            log("hook pong failed: " + t);
                        }
                        return;
                    }
                    // gate1：root 保活就绪标志落宿主 SP
                    if (ACTION_SET_ROOT_READY.equals(action)) {
                        boolean ready = i.getBooleanExtra(EXTRA_READY, false);
                        log("broadcast root-ready -> " + ready);
                        ModePrefs.setRootKeepAliveReady(c, ready);
                        return;
                    }
                    // gate6：MIUI 自启动就绪标志落宿主 SP
                    if (ACTION_SET_AUTOSTART_READY.equals(action)) {
                        boolean ready = i.getBooleanExtra(EXTRA_READY, false);
                        log("broadcast autostart-ready -> " + ready);
                        ModePrefs.setAutoStartReady(c, ready);
                        return;
                    }
                    if (ACTION_SET_FEED_INTERCEPT.equals(action)) {
                        boolean intercept = i.getBooleanExtra(EXTRA_FEED_INTERCEPT, true);
                        log("broadcast feed-intercept -> " + intercept);
                        ModePrefs.setFeedGestureIntercept(c, intercept); // 即时生效，无需切换链
                        return;
                    }
                    boolean on = i.getBooleanExtra(EXTRA_ON, false);
                    // gate1：开启前校验权限 —— 前置条件不齐拒绝开启（模块首页 UI 同步拦截，
                    // 此处兜底防伪造/竞态直开）。gate4：拒绝原因回执给首页，无声失败可见。
                    if (on && !PermissionGate.customModeAllowed(c)) {
                        boolean notifOk = PermissionGate.notificationAccessReady(c);
                        String reason = !notifOk ? "notification_access"
                                : !PermissionGate.rootKeepAliveReady(c)
                                  ? "root_flag_missing（首页重新检测 root 或回首页自动补发标志）"
                                  : "autostart_off（MIUI 自启动未开启，首页第④项引导开启）";
                        log("broadcast toggle ON rejected: permission gate not satisfied" +
                                " (notif=" + notifOk +
                                " root=" + PermissionGate.rootKeepAliveReady(c) + ")");
                        try {
                            c.sendBroadcast(new Intent(ACTION_TOGGLE_REJECTED)
                                    .setPackage("com.homemode4miui")
                                    .putExtra(EXTRA_GATE_REASON, reason));
                        } catch (Throwable t) {
                            log("toggle-rejected reply failed: " + t);
                        }
                        return;
                    }
                    log("broadcast toggle -> " + on);
                    ModePrefs.setModeEnable(c, on);
                    // 兜底直刷：不依赖原生 onLauncherModeChanged 事件链（宿主重构时仍可切换）
                    Activity launcher = sLauncher;
                    if (launcher != null) {
                        HomeController.sync(launcher, on && PermissionGate.customModeAllowed(c));
                    }
                }
            }, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            log("registerToggleReceiver failed: " + t);
        }
    }

    /** 宿主桌面版本号（gate2：pong 回显；与 hostVersion 不同的独立安全包装） */
    private static String hostVersionSafe() {
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", LauncherModeController.class.getClassLoader()),
                    "currentApplication");
            if (ctx == null) return "unknown";
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static boolean contains(LauncherMode[] arr, LauncherMode m) {
        for (LauncherMode x : arr) {
            if (x == m) return true;
        }
        return false;
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
