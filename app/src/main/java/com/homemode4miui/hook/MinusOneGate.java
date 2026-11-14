package com.homemode4miui.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserManager;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

import com.homemode4miui.util.ModLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 负一屏时序对齐 —— 第四桌面模式复用原生的负一屏初始化方法（v7，2026-11-12）。
 *
 * 原生桌面模式开机为什么一切正常：
 *   u0 unlock 完成 → CE 区 widget provider 注册就绪 → 宿主 connect PA →
 *   PA 预载 → bindAppWidgetId 成功 → widget 恢复（含 notes 内容映射）。
 *
 * 第四桌面模式此前为什么失败（v1~v5 的教训，补丁链全部回退）：
 *   宿主启动开销使 connect 早于 provider 就绪 → PA 预载判死。此前在 PA 恢复
 *   流程下游打的补丁（延迟重放时机/kill 重载/删除拦截）与 PA 内部状态机反复
 *   冲突，越修越糟。
 *
 * v6（对齐原生时序）：u0 未解锁 → defer connect；USER_UNLOCKED → toggle 补注册 →
 *   轮询到就绪 → 重放 connect。
 *
 * v7（2026-11-12，RE_MINUS_ONE_KEEPALIVE §6.2 —— 与保活方案配套）：
 *   v6 的 gate 有"无限期吞 connect"的副作用：只要 sReady 未置位，四条 connect 通道
 *   （queueIdle / setClientOptions / AssistantConnectMessage / PackageDataClear）
 *   全被吞，PA 永远起不来 —— 与"PA 常驻"的目标直接冲突。v7 把 gate 降级为
 *   **一次性启动门**：
 *     1. 只有"解锁未完成或注册未就绪"的启动窗口内才 defer；
 *     2. 无论是否就绪，**最多等 25s（v8 由 8s 上调，见 READY_TIMEOUT_MS 注释）** 就放行（原为 60s + 逐秒轮询）；
 *     3. 放行后 sOpen 永久为 true，此后不再拦截任何 connect（含 reconnect 与
 *        事件通道）—— gate 彻底退出，PA 的生命周期交还原生。
 *   toggle 也改为"仅在已知 provider 不可见时才做"（已就绪的普通重启不再白等）。
 *
 * 干预面：只在 connect 的发起时机上做一次 gate，PA 进程零干预，MIUI 恢复流程原样运行。
 */
public final class MinusOneGate {

    private static final String CLIENT_CLS = "com.miui.launcher.overlay.client.LauncherClient";
    /** 就绪阈值：本机全量 522，缩水态 9~17，toggle 后 25 → 边界取 20。 */
    private static final int READY_MIN_PROVIDERS = 20;
    private static final long READY_POLL_INTERVAL_MS = 500L;
    /**
     * 就绪兜底上限（v8）：8s → 25s。8s 在慢开机上会**早于** CE 区 provider 注册完成就放行
     * PA，PA 恢复 widget 时 bind 失败 → AMS 侧 provider 为空 → 渲染"无法添加微件"
     * （2026-11-13 装机实锤：dumpsys appwidget 里 PA 的 4 个 widget 只有 host、没有 provider）。
     * 正常路径仍以 targetedReady() 为准、就绪即放行，此值只是"绝不永久卡死"的上限。
     */
    private static final long READY_TIMEOUT_MS = 45_000L;
    /** 诊断采样：每 5s 打一次注册表读数，确认 launcher 侧到底能不能看到 provider。 */
    private static final int DIAG_EVERY_POLLS = 10;

    private static final List<Object> sPending = new ArrayList<>();
    private static volatile Member sConnectMethod;
    private static volatile boolean sReceiverRegistered = false;
    private static volatile boolean sPreparing = false;
    private static volatile boolean sPolling = false;
    /** 唯一放行标志：置位即永久放行（gate 退出，不再拦截任何 connect）。 */
    private static volatile boolean sOpen = false;

    private MinusOneGate() {
    }

    /** 供 MinusOneEntry 查询：false 时 open 应等待（PA 尚未放行）。 */
    public static boolean isReady() {
        return sOpen;
    }

    /** 供 MinusOneEntry 在 gate 未放行时提前预热（等同一次 connect 触发的准备流程）。 */
    public static void poke() {
        prepareAsync();
    }

    /** MainHook 挂载入口。 */
    public static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLIENT_CLS, cl, "connect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (sOpen) return; // 已放行：永久交还原生（含重连与事件通道）
                    // 启动窗口内：吞掉本次 connect，挂起等解锁 + 注册确认
                    synchronized (sPending) {
                        if (!sPending.contains(param.thisObject)) {
                            sPending.add(param.thisObject);
                        }
                    }
                    if (sConnectMethod == null) {
                        sConnectMethod = param.method;
                    }
                    param.setResult(null);
                    ModLog.i("MinusOneGate: connect deferred (startup window), pending="
                            + sPending.size());
                    prepareAsync();
                } catch (Throwable t) {
                    // gate 自身故障：降级放行并停用，绝不阻断宿主
                    sOpen = true;
                    ModLog.e("MinusOneGate: gate error, passthrough + disabled", t);
                }
            }
        });
        ModLog.i("MinusOneGate: installed (v7 one-shot startup gate, cap "
                + READY_TIMEOUT_MS + "ms)");
    }

    /** 准备流程（一次性）：已解锁 → 补 provider 后开轮询；未解锁 → 等 USER_UNLOCKED。 */
    private static void prepareAsync() {
        synchronized (MinusOneGate.class) {
            if (sPreparing || sOpen) return;
            sPreparing = true;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isUserUnlocked()) {
                        ModLog.i("MinusOneGate: already unlocked -> preparing providers");
                        prepareProviders();
                        startPoll();
                    } else {
                        ModLog.i("MinusOneGate: locked -> waiting USER_UNLOCKED");
                        registerUnlockReceiver();
                    }
                } catch (Throwable t) {
                    ModLog.e("MinusOneGate: prepare error, passthrough", t);
                    release("prepare-error");
                }
            }
        });
    }

    private static Context currentApplication() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread",
                            MinusOneGate.class.getClassLoader()),
                    "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }

    /** u0 是否已 FBE 解锁（探测异常视为已解锁，降级放行）。 */
    private static boolean isUserUnlocked() {
        try {
            Context app = currentApplication();
            if (app == null) return true;
            UserManager um = (UserManager) app.getSystemService(Context.USER_SERVICE);
            return um == null || um.isUserUnlocked(Process.myUserHandle());
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 定向就绪：PA 实际要绑的 provider 包（负一屏第三方 widget 的来源）在注册表
     * 可见即视为就绪 —— 比"全量注册"快得多（全量重扫 ~16s，定向逐包先行）。
     * 包清单与 toggleKnownProviders 的已知负一屏 widget 来源一致。
     */
    private static boolean targetedReady() {
        try {
            Context app = currentApplication();
            if (app == null) return false;
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(app);
            List<?> l = mgr.getInstalledProvidersForProfile(Process.myUserHandle());
            if (l == null) return false;
            int hit = 0;
            for (Object o : l) {
                if (o instanceof android.appwidget.AppWidgetProviderInfo) {
                    final String pkg = ((android.appwidget.AppWidgetProviderInfo) o)
                            .provider.getPackageName();
                    if ("com.xiaomi.misettings".equals(pkg)
                            || "com.miui.notes".equals(pkg)) {
                        hit++;
                    }
                }
            }
            return hit >= 2; // 两个已知来源包各至少一个 provider 可见
        } catch (Throwable t) {
            return false;
        }
    }

    /** 宿主进程内统计注册表规模（缩水态 9~17，toggle 后 ~25，全量 522）。 */
    private static int countProviders() {
        try {
            Context app = currentApplication();
            if (app == null) return 0;
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(app);
            List<?> l = mgr.getInstalledProvidersForProfile(Process.myUserHandle());
            return l == null ? 0 : l.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void registerUnlockReceiver() {
        if (sReceiverRegistered) return;
        synchronized (MinusOneGate.class) {
            if (sReceiverRegistered) return;
            Context app = currentApplication();
            if (app == null) return;
            try {
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onUserUnlocked();
                    }
                }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
                sReceiverRegistered = true;
                ModLog.i("MinusOneGate: USER_UNLOCKED receiver registered");
            } catch (Throwable t) {
                ModLog.e("MinusOneGate: register receiver failed", t);
            }
        }
    }

    /** 解锁：补注册（模拟系统 CE 扫描）后开轮询；放行前 connect 持续 defer。 */
    private static void onUserUnlocked() {
        ModLog.i("MinusOneGate: USER_UNLOCKED -> preparing providers");
        prepareProviders();
        startPoll();
    }

    /** 已知 provider 齐全就不 toggle（普通重启路径不再白等 PACKAGE_CHANGED 重注册）。 */
    private static void prepareProviders() {
        if (targetedReady()) {
            ModLog.i("MinusOneGate: known providers visible, toggle skipped");
            return;
        }
        toggleKnownProviders();
    }

    /** 就绪轮询（一次性）：就绪或 25s 超时都放行，永不无限期 defer。 */
    private static void startPoll() {
        synchronized (MinusOneGate.class) {
            if (sPolling || sOpen) return;
            sPolling = true;
        }
        final Handler h = new Handler(Looper.getMainLooper());
        final long start = SystemClock.elapsedRealtime();
        final Runnable[] poll = new Runnable[1];
        final int[] polls = new int[1];
        poll[0] = new Runnable() {
            @Override
            public void run() {
                if (sOpen) return;
                final int n = countProviders();
                final long elapsed = SystemClock.elapsedRealtime() - start;
                // 诊断采样（v9）：每 5s 一条，用来判定"launcher 侧读不到 provider"
                // 到底是永久缺陷还是启动早段的窗口 —— 决定后续是等信号还是换方案。
                polls[0]++;
                if (polls[0] % DIAG_EVERY_POLLS == 0) {
                    ModLog.i("MinusOneGate diag: providers=" + n
                            + " targeted=" + targetedReady()
                            + " elapsed=" + elapsed + "ms");
                }
                // 定向就绪优先：PA 实际绑的 provider（misettings/notes）可见即放行，
                // 不等全量注册（原生即开即用的关键）。全量阈值作为兜底信号。
                if (targetedReady() || n >= READY_MIN_PROVIDERS) {
                    ModLog.i("MinusOneGate: registry ready (providers=" + n
                            + ", waited=" + elapsed + "ms)");
                    release("ready");
                    return;
                }
                if (elapsed >= READY_TIMEOUT_MS) {
                    // 降级：超时放行（回退旧竞态，绝不长期卡住 PA）
                    ModLog.i("MinusOneGate: ready timeout (" + n + " after "
                            + elapsed + "ms) -> passthrough");
                    release("timeout");
                    return;
                }
                h.postDelayed(poll[0], READY_POLL_INTERVAL_MS);
            }
        };
        h.postDelayed(poll[0], READY_POLL_INTERVAL_MS);
    }

    /** 永久放行 + 按序重放挂起的 connect()（主线程，与原生调用线程一致）。 */
    private static void release(String reason) {
        sOpen = true;
        final List<Object> pending;
        synchronized (sPending) {
            pending = new ArrayList<>(sPending);
            sPending.clear();
        }
        ModLog.i("MinusOneGate: gate OPEN (" + reason + "), replaying "
                + pending.size() + " connect()");
        final Member connect = sConnectMethod;
        for (final Object client : pending) {
            try {
                Object destroyed = XposedHelpers.callMethod(client, "isDestroyed");
                if (destroyed instanceof Boolean && (Boolean) destroyed) {
                    ModLog.i("MinusOneGate: skip destroyed client");
                    continue;
                }
                if (connect != null) {
                    XposedBridge.invokeOriginalMethod(connect, client, new Object[0]);
                } else {
                    XposedHelpers.callMethod(client, "connect");
                }
                ModLog.i("MinusOneGate: replayed connect on "
                        + client.getClass().getSimpleName());
            } catch (Throwable t) {
                ModLog.e("MinusOneGate: replay connect failed", t);
            }
        }
    }

    /** 逐包 disable→enable 已知 widget 组件：PACKAGE_CHANGED 触发整包重注册。 */
    private static void toggleKnownProviders() {
        final Context app = currentApplication();
        if (app == null) return;
        final android.content.pm.PackageManager pm = app.getPackageManager();
        final String[][] targets = {
                {"com.xiaomi.misettings",
                        "com.xiaomi.misettings.usagestats.widget.desktop.widget.SimpleUsageStatsWidget"},
                {"com.miui.notes", "com.miui.notes.widget.NoteWidgetProvider_4x2"},
                {"com.android.camera",
                        "com.android.camera2.compat.theme.custom.cv.widget.CameraFiltersWidgetProvider"},
                {"com.android.fileexplorer", "com.android.fileexplorer.widget.SimplePinFileWidget"},
                {"com.android.quicksearchbox",
                        "com.android.quicksearchbox.widget.Miui13SearchWidget"},
        };
        for (final String[] t : targets) {
            try {
                final android.content.ComponentName cn =
                        new android.content.ComponentName(t[0], t[1]);
                pm.setComponentEnabledSetting(cn,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                pm.setComponentEnabledSetting(cn,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
                ModLog.i("MinusOneGate: toggled " + t[0]);
            } catch (Throwable t2) {
                ModLog.e("MinusOneGate: toggle failed " + t[0]
                        + " (" + t2.getClass().getSimpleName() + ")", t2);
            }
        }
    }
}
