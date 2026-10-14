package com.homemode4miui;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import com.miui.home.launcher.allapps.LauncherMode;
import com.miui.home.launcher.allapps.LauncherModeController;
import com.homemode4miui.hook.GestureIsolator;
import com.homemode4miui.util.ModLog;
import com.homemode4miui.hook.HomeController;
import com.homemode4miui.hook.HostCompat;
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
 * Hook 点清单（OS2/OS3 双版本经 HostCompat 路由）：
 *  0. Application.onCreate            after  → gate1 根修：ping 接收器注册 + 存活广播
 *     的最早锚点，与 Launcher 生命周期解耦（OS2/OS3 通吃；此前接收器只在
 *     mLauncherUiSync 注册，Launcher hook 未命中即"hook 上了桌面但检测不到"）
 *  1. LauncherModeController.getAllModes()            after  → 注册第四模式
 *  2. 模式裁决（自适应）：优先 hook 任意签名的 loadCurrentLauncherMode；
 *     版本漂移找不到时兜底 hook enableAndResetCache() 改写 sCurrentMode
 *  3. Launcher.setupViews()                           after  → 挂载/同步自定义视图
 *     （OS2 声明于 Launcher，OS3 上移至 BaseLauncher —— HostCompat 解析声明类）
 *  3b. Launcher.onCreate(Bundle)                      after  → 挂载兜底锚点：
 *     两版本都必经的生命周期方法，不依赖"OS3 setupViews 是否仍被调用"假设
 *  4. Launcher.onLauncherModeChanged()                after  → 原生模式切换链同步 UI（同上路由）
 *  5. Launcher.onResume()                             after  → 回桌面校准 + 通知应用变更（同上路由）
 *  5b. Launcher.onPause()                             after  → 桌面退后台省电同步：
 *     HomeView.onHomePaused 推 Compose 生命周期到 STOPPED（时钟循环挂起/widget
 *     宿主停听）—— perf3 功耗专项；回前台由 5.onResume 自动恢复（同上路由）
 *  9. SuperDraglayer.findActiveController             before → 手势隔离闸门：
 *     自定义模式激活时宿主手势控制器（下拉搜索/上滑抽屉/横滑负一屏/下拉通知栏）
 *     永不认领手势流，消除与挂载视图的手势互相捣乱；负一屏恒定隔离（已固化，无开关），
 *     另含 isSupportShowHomeFeed 使能闸放行（hook/GestureIsolator.java）
 *  10. Launcher.onNewIntent                           after  → Home 键回首页态回调（同上路由）；
 *     OS3 另挂 onNewIntentWhenAlreadyOnHome（"已在桌面回首页"独立路径，OS2 无则跳过）
 *
 * 版本适配（HostCompat）：
 *   - versionCode ≥ 600_000_000 → OS3 家族（RELEASE-6.01.03 实测），否则 OS2（5.39.40 基准）；
 *   - OS3 的 Launcher 大重构：setupViews/onResume/onNewIntent/onLauncherModeChanged/
 *   - launcherHookClass() 沿 Launcher→父类链解析实际声明类，两版本通吃；
 *   - 原设置页第四选项注入（DesktopModeInjector）已按产品决策移除。
 *
 * 工程守则（两道闸）：每个 hook 独立 try/catch —— 单点失败只降级该功能并记录日志，
 * 绝不因一个 hook 未命中而放弃其余 hook（真机 5.39.40/6.01.03 之外的宿主版本方法签名可能漂移）。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "HomeMode4Miui";
    private static final String HOME_PKG = "com.miui.home";
    public static final String ACTION_TOGGLE = "com.homemode4miui.ACTION_TOGGLE_MODE";
    public static final String EXTRA_ON = "custom_mode_on";
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
    /** gate1：pong/live 附带宿主大版本家族标签（OS2(5.x)/OS3(6.x)/UNKNOWN） */
    public static final String EXTRA_HOST_FAMILY = "host_family";
    /** gate1：pong/live 附带未生效 hook 清单（首页分级提示"无法 hook/部分生效"） */
    public static final String EXTRA_HOOK_FAILED = "hook_failed_list";

    /** 当前 Launcher 实例（setupViews 时缓存，供广播切换用） */
    private static volatile Activity sLauncher;
    private static boolean sReceiverRegistered;
    /** 裁决锚点是否已安装（未安装时 isCustomModeActive 回退为直读 SP 标志） */
    private static volatile boolean sAdjudicationInstalled;
    /** 未生效 hook 清单（tryHook 失败时记录；随 pong/live 广播给模块首页分级提示） */
    private static final java.util.List<String> sFailedHooks =
            new java.util.concurrent.CopyOnWriteArrayList<String>();

    /** 单个 hook 任务（允许抛 Throwable，由 tryHook 统一隔离降级） */
    private interface HookTask {
        void run() throws Throwable;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!HOME_PKG.equals(lpp.packageName)) return;
        // hotfix：仅主进程装载全套 hook —— 宿主若派生子进程（内容中心/推送等），
        // 每进程都装 hook + 注册接收器会造成多份 pong 响应与静态状态分裂
        if (!HOME_PKG.equals(lpp.processName)) {
            log("skip non-main process: " + lpp.processName);
            return;
        }
        log("loaded into " + lpp.packageName + " / " + lpp.processName
                + " | host " + hostVersion(lpp));
        final ClassLoader cl = lpp.classLoader;

        // 版本判别前置（独立降级：判别失败只影响日志标注与路由起点，不阻断任何 hook）。
        // lpp.appInfo 兜底解析使 app 未就绪阶段也能拿到 versionCode（OS3 误判修复）。
        tryHook("0.HostCompat.detect", new HookTask() {
            @Override public void run() { HostCompat.detect(lpp, cl); }
        });

        // gate1 根修：Application.onCreate —— ping 接收器注册 + 存活广播的最早锚点。
        // 与 Launcher 生命周期完全解耦：即使 setupViews/onLauncherModeChanged 等全部
        // 未命中（OS3 DI 重构下已实证存在此风险），首页 ping/pong 探测依然可用。
        // 此前接收器只在 mLauncherUiSync 里注册 —— Launcher hook 没命中即"hook 上了
        // 桌面但判定检测不到"（OS2/OS3 通病，装机实锤）。
        // 上游同步 v1.1.8 S8b：pin item 接收链 —— MIUI AddItemActivity 确认 accept 后
        // 旁路旁听转存模块 saved_shortcuts（showInAppList=true → 抽屉显示 PWA/快捷方式）。
        tryHook("0b.PinItemCompat.accept", new HookTask() {
            @Override public void run() {
                com.homemode4miui.hook.GracePinItemHook.INSTANCE.hook(lpp, cl);
            }
        });

        tryHook("0.Application.onCreate (gate1 接收器前置)", new HookTask() {
            @Override public void run() {
                XposedHelpers.findAndHookMethod("android.app.Application", cl, "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Context app = (Context) param.thisObject;
                                    HostCompat.redetect(cl); // app 就绪，校正大版本家族
                                    registerToggleReceiver(app);
                                    app.sendBroadcast(liveIntent());
                                    log("gate1: receiver registered + live broadcast sent"
                                            + " (Application.onCreate, host " + HostCompat.label() + ")");
                                } catch (Throwable t) {
                                    log("Application.onCreate gate1 failed: " + t);
                                }
                            }
                        });
            }
        });

        // 每个 hook 独立降级：一处失败不影响其余（真机版本漂移防护）
        tryHook("1.getAllModes", new HookTask() {
            @Override public void run() { hookGetAllModes(); }
        });
        tryHook("2.modeAdjudication", new HookTask() {
            @Override public void run() { hookModeAdjudication(cl); }
        });
        tryHook("3.Launcher.setupViews", new HookTask() {
            @Override public void run() { hookLauncherMethod(cl, "setupViews", mLauncherUiSync); }
        });
        // 3b. Launcher.onCreate —— 挂载兜底锚点：两版本都必经的生命周期方法，
        // 不依赖"OS3 的 setupViews 是否仍被调用"这一待真机验证假设。
        tryHook("3b.Launcher.onCreate (挂载兜底锚点)", new HookTask() {
            @Override public void run() {
                hookLauncherMethod(cl, "onCreate", mLauncherUiSync, android.os.Bundle.class);
            }
        });
        tryHook("4.Launcher.onLauncherModeChanged", new HookTask() {
            @Override public void run() { hookLauncherMethod(cl, "onLauncherModeChanged", mLauncherUiSync); }
        });
        tryHook("5.Launcher.onResume", new HookTask() {
            @Override public void run() {
                XposedHelpers.findAndHookMethod(HostCompat.launcherHookClass(cl, "onResume"),
                        "onResume", new XC_MethodHook() {
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
        // perf3（功耗专项）：Launcher.onPause —— 自定义桌面退后台同步。
        // 此前 Compose 生命周期只在 attach/detach 流转：用户打开其他 App 后组合
        // 永不暂停，时钟 60s 循环 / widget 宿主监听在后台持续活动（周期唤醒）。
        // hook 后经 HomeView.onHomePaused 推 STOP；回前台由 5.onResume →
        // onHomeAttached 复用分支自动推回 RESUMED。hook 未命中只降级省电能力，
        // 不影响功能（与全部 hook 同一隔离原则）。
        tryHook("5b.Launcher.onPause (退后台省电同步)", new HookTask() {
            @Override public void run() {
                hookLauncherMethod(cl, "onPause", mLauncherPauseSync);
            }
        });
        tryHook("9.SuperDraglayer.findActiveController (手势隔离)", new HookTask() {
            @Override public void run() { GestureIsolator.inject(cl); }
        });
        tryHook("11.Launcher.onNewIntent (Home 键回首页态)", new HookTask() {
            @Override public void run() { HomeController.installHomeIntentSync(cl); }
        });

        // gate1：hook 全部安装完成 → 广播存活（模块进程若活着即收到；
        // 进程不在时由模块首页 ping/pong 兜底探测，Application.onCreate 锚点也会主动广播）
        try {
            Context app = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl), "currentApplication");
            if (app != null) {
                app.sendBroadcast(liveIntent());
            }
        } catch (Throwable t) {
            log("hook live broadcast failed: " + t);
        }
    }

    /** 单 hook 隔离执行：成功/失败均记日志，失败只降级该功能；失败记入 sFailedHooks */
    private static void tryHook(String name, HookTask task) {
        try {
            task.run();
            log("hook installed: " + name);
        } catch (Throwable t) {
            String summary = name + " → " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : "");
            if (summary.length() > 200) summary = summary.substring(0, 200);
            sFailedHooks.add(summary);
            log("hook FAILED (degraded): " + name + " -> " + t);
        }
    }

    /** gate1 存活广播：宿主版本 + 大版本家族 + 未生效 hook 清单（首页分级提示数据源） */
    private static Intent liveIntent() {
        Intent i = new Intent(ACTION_HOOK_LIVE).setPackage("com.homemode4miui")
                .putExtra(EXTRA_READY, true)
                .putExtra(EXTRA_HOST_VERSION, hostVersionSafe())
                .putExtra(EXTRA_HOST_FAMILY, HostCompat.label());
        i.putStringArrayListExtra(EXTRA_HOOK_FAILED, new java.util.ArrayList<>(sFailedHooks));
        return i;
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
            } catch (Throwable t) {
                // 裁决链路异常最需留痕：静默回退 SP 分支会掩盖宿主签名漂移
                ModLog.e("MainHook isCustomModeActive adjudication read failed", t);
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
                        java.lang.reflect.Field f = sCurrentModeFieldCached();
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
    /** perf（v2.9.41）：字段扫描结果缓存（字段进程内恒定；null = 未命中，每次模式切换重扫代价可接受） */
    private static volatile java.lang.reflect.Field sCachedCurrentModeField;

    private static java.lang.reflect.Field sCurrentModeFieldCached() throws Throwable {
        java.lang.reflect.Field f = sCachedCurrentModeField;
        if (f == null) {
            f = sCurrentModeField();
            sCachedCurrentModeField = f;
        }
        return f;
    }

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

    /** 宿主桌面版本号（用于日志比对签名漂移来源；app 未就绪阶段 ctx 为 null 属预期） */
    private static String hostVersion(XC_LoadPackage.LoadPackageParam lpp) {
        return hostVersion(lpp.classLoader, true);
    }

    /* ================================================================
     * ③④ Launcher UI 同步（共用回调；onResume 在 ⑤ 单独处理带刷新）
     * ================================================================ */

    /** perf3：Launcher.onPause 同步回调 —— 自定义桌面退后台省电（见 hook 5b 注释） */
    private final XC_MethodHook mLauncherPauseSync = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                HomeController.notifyHomePaused();
            } catch (Throwable t) {
                log("launcher pause sync callback failed: " + t);
            }
        }
    };

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

    /**
     * 版本路由的 Launcher hook：OS2 声明于 Launcher、OS3 上移至 BaseLauncher，
     * 由 HostCompat.launcherHookClass 解析实际声明类（结果缓存，全 hook 共享）。
     * params 为方法参数类型（可空），hook 回调固定排在 findAndHookMethod 变参末位
     * （Object[] 手工拼接，避免 Class[] 被当作单个 Object 嵌套）。
     */
    private void hookLauncherMethod(ClassLoader cl, String method, XC_MethodHook hook,
            Class<?>... params) {
        Object[] args = new Object[params.length + 1];
        System.arraycopy(params, 0, args, 0, params.length);
        args[params.length] = hook;
        XposedHelpers.findAndHookMethod(HostCompat.launcherHookClass(cl, method, params),
                method, args);
    }

    /* ================================================================
     * 模块 App 的实时切换广播
     * ================================================================ */

    private void registerToggleReceiver(Context ctx) {
        if (sReceiverRegistered) return; // Launcher recreate 时防重复注册
        try {
            IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
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
                    // gate1：首页 hook 存活探测 → 立即回 pong（带宿主版本/家族/失败清单）
                    if (ACTION_HOOK_PING.equals(action)) {
                        Intent pong = liveIntent();
                        try {
                            c.sendBroadcast(pong);
                            log("hook ping -> live pong sent (host "
                                    + pong.getStringExtra(EXTRA_HOST_VERSION)
                                    + ", " + pong.getStringExtra(EXTRA_HOST_FAMILY)
                                    + ", failed=" + sFailedHooks.size() + ")");
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
            // 注册成功才置位：失败保持未注册，下次生命周期回调可重试
            // （此前先置位后注册 —— 注册一失败接收器永久缺失，ping 永远无应答）
            sReceiverRegistered = true;
            log("toggle/ping receiver registered (context=" + ctx.getClass().getName() + ")");
        } catch (Throwable t) {
            log("registerToggleReceiver failed: " + t);
        }
    }

    /** 宿主桌面版本号（gate2：pong 回显）。两入口合一：withCode 控制是否附 versionCode。 */
    private static String hostVersionSafe() {
        return hostVersion(LauncherModeController.class.getClassLoader(), false);
    }

    private static String hostVersion(ClassLoader cl, boolean withCode) {
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl),
                    "currentApplication");
            if (ctx == null) return withCode ? "version-unknown(app-not-ready)" : "unknown";
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return withCode ? pi.versionName + " (" + pi.getLongVersionCode() + ")" : pi.versionName;
        } catch (Throwable t) {
            return withCode ? "version-unknown(" + t + ")" : "unknown";
        }
    }

    private static boolean contains(LauncherMode[] arr, LauncherMode m) {
        for (LauncherMode x : arr) {
            if (x == m) return true;
        }
        return false;
    }

    private static void log(String msg) {
        ModLog.i(msg);
    }
}
