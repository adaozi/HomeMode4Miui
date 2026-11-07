package com.homemode4miui.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Process;
import android.os.UserManager;

import java.lang.reflect.Member;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import com.homemode4miui.util.ModLog;
import de.robv.android.xposed.XposedHelpers;

/**
 * 负一屏 Widget 自愈 —— u0 FBE 解锁前延迟 PA overlay 连接（2026-11-07 定案）。
 *
 * 根因（双模式对照实验实锤，fuxi OS2 RELEASE-5.39.40.10843）：
 *   模块桌面模式开机时宿主启动开销（hook + GraceHomeView + Room）改变开机竞态——
 *   PA（personalassistant）冷启动预载 widget 恢复（开机后 ~11s，宿主 connect PA 触发）
 *   早于 u0 FBE unlock 完成（~18-20s）→ CE 区 widget provider 未注册
 *   （AppWidgetService 注册表仅 9 个 DE 区残留，正常 522）→ PA restoreWidget 的
 *   bindAppWidgetId 被拒（"No widget provider ... for profile 0"）→ PA 判死
 *   removeInvalidWidgets（删系统 widget id + 删 PA 库记录）→ 负一屏
 *   "无法添加微件"/空白且永久失效。原生桌面模式开机（启动轻）unlock 恰先于预载
 *   → bind 成功 → widget 自动恢复（这就是"切原生重启就好了"的原因）。
 *
 * 修复：gate LauncherClient.connect()（com.miui.launcher.overlay.client.LauncherClient
 *   基类明文方法；PersistentLauncherClient（PA 主链）/FeedLauncherClient/
 *   SearchLauncherClient/AssistantLauncherClient 全部子类汇聚此入口，OS2/OS3 同构）——
 *   u0 未 FBE 解锁时不发起连接：PA 不被 bind、不被拉起、预载判死无从发生；
 *   同时注册 ACTION_USER_UNLOCKED 动态接收器，解锁后按序重放全部挂起的 connect()。
 *   重放即原生健康路径：unlock 后 provider 注册完成（实测 unlock 后 ~5s 内 bind 成功）
 *   → widget 自动恢复，无需用户重加。
 *
 * 感知性：锁屏期间负一屏本就 lockToHideOverlay 隐藏（AssistantOverlayWindow），
 *   gate 期间用户无法交互负一屏，延迟连接零感知。
 *
 * 降级：OS3 类名/方法名漂移时 findAndHookMethod 抛错，由 MainHook.tryHook 隔离，
 *   仅损失自愈能力不破坏宿主。isUserUnlocked 探测异常时放行（宁可回退旧竞态也不
 *   阻断宿主连接）。
 */
public final class MinusOneGate {

    private static final String TAG = "HomeMode4Miui";
    private static final String CLIENT_CLS = "com.miui.launcher.overlay.client.LauncherClient";

    /** 已 defer、待解锁后重放的 client 实例（宿主主线程串行访问为主，仍加锁兜底）。 */
    private static final List<Object> sPending = new ArrayList<>();
    /** defer 时捕获的 connect Method（重放用）。 */
    private static volatile Member sConnectMethod;
    private static volatile boolean sReceiverRegistered = false;
    private static volatile boolean sUnlockSeen = false;

    /** 重放就绪判定：provider 注册表阈值（本机全量 522，缩水态 9~17，边界取 50）。 */
    private static final int READY_MIN_PROVIDERS = 50;
    /** 就绪轮询节奏与超时（超时强制重放 = 回退旧竞态行为，绝不永久卡死）。 */
    private static final long READY_POLL_INTERVAL_MS = 1000L;
    private static final long READY_TIMEOUT_MS = 120_000L;
    private static final android.os.Handler sHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile long sReadyPollStart = 0L;
    private static volatile boolean sToggleTried = false;

    /**
     * 已知负一屏 widget 包（每包 toggle 一个组件即可触发整包 provider 重注册，
     * 2026-11-07 15:38 实测：misettings/notes toggle 后 9→17 全量回归）。
     * 组件名来自 exp4 原生模式注册表 dumpsys 精确记录。
     */
    private static final String[][] TOGGLE_TARGETS = {
            {"com.xiaomi.misettings",
                    "com.xiaomi.misettings.usagestats.widget.desktop.widget.SimpleUsageStatsWidget"},
            {"com.miui.notes", "com.miui.notes.widget.NoteWidgetProvider_4x2"},
            {"com.android.camera",
                    "com.android.camera2.compat.theme.custom.cv.widget.CameraFiltersWidgetProvider"},
            {"com.android.fileexplorer", "com.android.fileexplorer.widget.SimplePinFileWidget"},
            {"com.android.quicksearchbox",
                    "com.android.quicksearchbox.widget.Miui13SearchWidget"},
    };

    private MinusOneGate() {
    }

    /** MainHook 挂载入口。 */
    public static void install(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(CLIENT_CLS, cl, "connect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (sUnlockSeen) return; // 解锁后：所有 connect 直接放行
                    if (isUserUnlocked()) {
                        sUnlockSeen = true;
                        ModLog.i("MinusOneGate: unlocked already, connect passthrough");
                        return;
                    }
                    // 未解锁：吞掉本次 connect，挂起实例等待解锁重放
                    synchronized (sPending) {
                        if (!sPending.contains(param.thisObject)) {
                            sPending.add(param.thisObject);
                        }
                    }
                    if (sConnectMethod == null) {
                        sConnectMethod = param.method;
                    }
                    param.setResult(null);
                    ModLog.i("MinusOneGate: connect deferred (u0 locked), pending="
                            + sPending.size());
                    registerUnlockReceiver();
                } catch (Throwable t) {
                    // 自愈组件自身故障绝不阻断宿主连接：放行并永久停用 gate
                    sUnlockSeen = true;
                    ModLog.e("MinusOneGate: gate error, passthrough + disabled", t);
                }
            }
        });
        ModLog.i("MinusOneGate: installed (connect gate on " + CLIENT_CLS + ")");
    }

    private static boolean isUserUnlocked() {
        try {
            Context app = currentApplication();
            if (app == null) return true; // Application 未就绪（不应发生）：放行
            UserManager um = (UserManager) app.getSystemService(Context.USER_SERVICE);
            if (um == null) return true;
            boolean unlocked = um.isUserUnlocked(Process.myUserHandle());
            return unlocked;
        } catch (Throwable t) {
            ModLog.e("MinusOneGate: isUserUnlocked probe failed, passthrough", t);
            return true;
        }
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

    /** 第一次 defer 时注册；USER_UNLOCKED 到达后重放全部挂起的 connect()。 */
    private static void registerUnlockReceiver() {
        if (sReceiverRegistered) return;
        synchronized (MinusOneGate.class) {
            if (sReceiverRegistered) return;
            Context app = currentApplication();
            if (app == null) return; // 下次 defer 再试
            try {
                app.registerReceiver(new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onUnlocked();
                    }
                }, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
                sReceiverRegistered = true;
                ModLog.i("MinusOneGate: USER_UNLOCKED receiver registered");
            } catch (Throwable t) {
                ModLog.e("MinusOneGate: register receiver failed", t);
            }
        }
    }

    /**
     * 解锁：不立即重放 —— v1 实测（2026-11-07 verify-gate）unlock 后 provider 注册
     * 本身还需数秒，PA 起来+预载快于注册完成 → 竞态窗口仍在。v2 改为轮询注册表
     * 就绪（>= READY_MIN_PROVIDERS）后再重放；超时强制重放兜底（绝不永久卡死）。
     */
    private static void onUnlocked() {
        sUnlockSeen = true;
        synchronized (sPending) {
            if (sPending.isEmpty()) {
                ModLog.i("MinusOneGate: unlocked, no pending connect");
                return;
            }
        }
        sReadyPollStart = android.os.SystemClock.elapsedRealtime();
        ModLog.i("MinusOneGate: unlocked, polling provider registry readiness (pending="
                + sPending.size() + ")");
        sHandler.postDelayed(MinusOneGate::pollReady, READY_POLL_INTERVAL_MS);
    }

    /** 就绪轮询：注册表 < 阈值时先做一轮组件 toggle（重注册触发器），再继续等。 */
    private static void pollReady() {
        if (sPending.isEmpty()) return;
        final int n = countInstalledProviders();
        final long elapsed = android.os.SystemClock.elapsedRealtime() - sReadyPollStart;
        if (n >= READY_MIN_PROVIDERS || elapsed >= READY_TIMEOUT_MS) {
            ModLog.i("MinusOneGate: ready (providers=" + n + ", waited=" + elapsed
                    + "ms, timeout=" + (n < READY_MIN_PROVIDERS) + ") -> replaying "
                    + sPending.size() + " connect()");
            replayAll();
            return;
        }
        // 缩水态（模块桌面模式开机的 CE 扫描缺失）：做一轮组件 toggle 触发重注册。
        // 15:38 实测有效（9→17，目标 provider 全回）；权限不足（SecurityException）
        // 时置位 sToggleTried 不再重复，退化为纯轮询 + 超时重放兜底。
        if (!sToggleTried) {
            sToggleTried = true;
            toggleKnownProviders();
        }
        sHandler.postDelayed(MinusOneGate::pollReady, READY_POLL_INTERVAL_MS);
    }

    /** 逐包 disable→enable 已知 widget 组件：PACKAGE_CHANGED 触发整包重注册。 */
    private static void toggleKnownProviders() {
        final Context app = currentApplication();
        if (app == null) return;
        final android.content.pm.PackageManager pm = app.getPackageManager();
        for (final String[] t : TOGGLE_TARGETS) {
            try {
                final android.content.ComponentName cn = new android.content.ComponentName(t[0], t[1]);
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

    /** 宿主进程内统计当前 AppWidgetService 注册表规模（缩水态 9~17，全量 522）。 */
    private static int countInstalledProviders() {
        try {
            Context app = currentApplication();
            if (app == null) return 0;
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(app);
            java.util.List<?> l = mgr.getInstalledProvidersForProfile(Process.myUserHandle());
            return l == null ? 0 : l.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 重放全部挂起的 connect()（原生调用线程即主线程，此处同为 Handler 主线程）。 */
    private static void replayAll() {
        final List<Object> pending;
        synchronized (sPending) {
            if (sPending.isEmpty()) return;
            pending = new ArrayList<>(sPending);
            sPending.clear();
        }
        final Member connect = sConnectMethod;
        for (final Object client : pending) {
            try {
                // 已销毁的 client 不重放（feed/search 等短生命周期实例可能在等待期被释放）
                Object destroyed = XposedHelpers.callMethod(client, "isDestroyed");
                if (destroyed instanceof Boolean && (Boolean) destroyed) {
                    ModLog.i("MinusOneGate: skip destroyed client "
                            + client.getClass().getSimpleName());
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
                ModLog.e("MinusOneGate: replay connect failed on "
                        + client.getClass().getSimpleName(), t);
            }
        }
    }
}
