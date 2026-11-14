package com.homemode4miui.hook;

import android.content.ComponentName;
import android.os.Handler;
import android.os.Looper;

import com.homemode4miui.util.ModLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 负一屏保活 —— 恢复原生的 PA 绑定语义（2026-11-12，RE_MINUS_ONE_KEEPALIVE §6.1）。
 *
 * 原生桌面为什么 PA 常驻：
 *   桌面进程存活期间绑定关系永不解绑；退后台只走 setStopped(true)，而真正的
 *   unbind 只在 cleanUp() 的 `mStopped && mLauncherOverlay == null` 交集发生
 *   —— 即"桌面在后台 **且** PA 的 binder 已断"。
 *
 * 本类拦的就是这个交集（两处，OS2/OS3 类名与方法签名一致，OS3 RELEASE-6.01.03
 * 与 OS2 RELEASE-5.39.40 逐字节同构）：
 *   ① PersistentLauncherOverlayConnection.setStopped(boolean)（flags=33，Waive/Middle 策略）
 *   ② LauncherClientService.setStopped(boolean)（flags=65，HighPriority 策略）
 *   对 com.miui.personalassistant 直接把 setStopped(true) 吞掉 → mStopped 恒 false
 *   → cleanUp() 永不调 disconnect() → 绑定关系贯穿桌面进程生命周期。
 *   效果：PA 被 LMK 杀掉后系统按 binding 重启服务 → onServiceConnected →
 *   setOverlay(binder) → updateOverlay() → 内容链与 AppWidgetHost 重新挂载
 *   （不再是"滑出时冷启动 6s+ 且微件渲染成无法添加微件"）。
 *
 * 为什么不拦 onStop / cleanUp：
 *   - LauncherClient.onStop() 里 setStopped 之后还有 `mActivityState &= -2` 与
 *     onActivityStateChange() —— 桌面不可见是**通过 setActivityState 通知 PA 的**，
 *     与解不解绑无关。只吞 setStopped(true) 可保留该通知，PA 不会在后台空转渲染。
 *   - cleanUp() 是 private 且两个类各有一份，拦它等于连 onServiceDisconnected 的
 *     正常清理一并封死；拦 setStopped 更精确（只封"降级为 stopped"这一个语义）。
 *
 * 缺口二（onBindingDied）：Waive 策略下 onBindingDied 不复位 mConnected，绑定已死
 * 但 connect() 会被 "already connected" 短路，PA 再也起不来。这里 after 钩子复位
 * mConnected 后走有界补绑链（2s/6s/15s），一旦 overlay 回来立即停手。
 * onServiceDisconnected（进程被杀、binding 仍活）**不**补绑 —— 系统会按绑定自己
 * 重启服务，重复 bindService 只会加引用计数。
 *
 * 守卫：包名取不到 / 方法缺失 → 一律放行（原生行为），绝不阻断宿主。
 */
public final class OverlayKeepAlive {

    private static final String PA_PKG = "com.miui.personalassistant";
    private static final String CLS_PERSISTENT =
            "com.miui.launcher.overlay.client.PersistentLauncherOverlayConnection";
    private static final String CLS_SERVICE =
            "com.miui.launcher.overlay.client.LauncherClientService";
    /** 补绑节奏（ms）：覆盖 PA 冷启动 + provider 注册窗口，三次不成就停手。 */
    private static final long[] REBIND_DELAYS = {2000L, 6000L, 15000L};

    private static boolean sFieldMissLogged;
    private static boolean sSuppressLogged;

    private OverlayKeepAlive() {
    }

    /** MainHook 挂载入口（宿主 com.miui.home 主进程）。 */
    public static void install(ClassLoader cl) {
        hookSetStopped(cl, CLS_PERSISTENT);
        hookSetStopped(cl, CLS_SERVICE);
        hookBindingLoss(cl, CLS_PERSISTENT);
        ModLog.i("OverlayKeepAlive: installed (setStopped guard + binding-died rebind)");
    }

    /* ---------------- ① setStopped 拦截（保活主改动） ---------------- */

    private static void hookSetStopped(ClassLoader cl, final String cls) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "setStopped", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // onStart 的 setStopped(false) 必须放行（回前台恢复常态）
                                if (!Boolean.TRUE.equals(param.args[0])) return;
                                if (!isPaTarget(param.thisObject)) return;
                                param.setResult(null);
                                if (!sSuppressLogged) {
                                    sSuppressLogged = true;
                                    ModLog.i("OverlayKeepAlive: setStopped(true) suppressed on "
                                            + param.thisObject.getClass().getSimpleName()
                                            + " -> binding kept for " + PA_PKG);
                                }
                            } catch (Throwable t) {
                                ModLog.e("OverlayKeepAlive: setStopped guard error, passthrough", t);
                            }
                        }
                    });
            ModLog.i("OverlayKeepAlive: setStopped guarded on "
                    + cls.substring(cls.lastIndexOf('.') + 1));
        } catch (Throwable t) {
            ModLog.e("OverlayKeepAlive: setStopped hook miss on " + cls, t);
        }
    }

    /* ---------------- ② 绑定死亡后的有界补绑 ---------------- */

    private static void hookBindingLoss(ClassLoader cl, final String cls) {
        // binding died：绑定已彻底失效，系统不会自愈 → 复位 mConnected 后补绑
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "onBindingDied",
                    ComponentName.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isPaTarget(param.thisObject)) return;
                                // 立即复位，不能等补绑链：Waive / High 两条路径的 onBindingDied
                                // 都不复位 mConnected（全库只有 Middle 分支复位）。复位晚 2s 的话，
                                // 这期间宿主自己的 connect()（onStart / setClientOptions / 事件通道）
                                // 仍会被 "overlay service already connected" 短路，等于没修。
                                XposedHelpers.setBooleanField(param.thisObject, "mConnected", false);
                                ModLog.i("OverlayKeepAlive: binding died on " + PA_PKG
                                        + " -> mConnected reset, rebind chain armed");
                                scheduleRebind(param.thisObject, 0);
                            } catch (Throwable t) {
                                ModLog.e("OverlayKeepAlive: binding-died handler error", t);
                            }
                        }
                    });
            ModLog.i("OverlayKeepAlive: onBindingDied watched");
        } catch (Throwable t) {
            ModLog.e("OverlayKeepAlive: onBindingDied hook miss on " + cls, t);
        }

        // 进程被杀（binding 仍活）：系统自愈，这里只做观测，不重复 bindService
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "onServiceDisconnected",
                    ComponentName.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isPaTarget(param.thisObject)) return;
                                ModLog.i("OverlayKeepAlive: PA binder lost (stopped="
                                        + XposedHelpers.getBooleanField(param.thisObject, "mStopped")
                                        + ", connected=" + XposedHelpers.getBooleanField(
                                                param.thisObject, "mConnected")
                                        + ") -> binding kept, awaiting system restart");
                            } catch (Throwable ignored) {
                                // 纯观测路径
                            }
                        }
                    });
        } catch (Throwable t) {
            ModLog.e("OverlayKeepAlive: onServiceDisconnected hook miss on " + cls, t);
        }

        // 恢复观测：验证持久绑定是否真的把 PA 拉回来了（装机采证用）
        try {
            XposedHelpers.findAndHookMethod(cls, cl, "onServiceConnected",
                    ComponentName.class, android.os.IBinder.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!isPaTarget(param.thisObject)) return;
                                ModLog.i("OverlayKeepAlive: PA binder restored (overlay="
                                        + (XposedHelpers.getObjectField(
                                                param.thisObject, "mLauncherOverlay") != null)
                                        + ")");
                            } catch (Throwable ignored) {
                                // 纯观测路径
                            }
                        }
                    });
        } catch (Throwable t) {
            ModLog.e("OverlayKeepAlive: onServiceConnected hook miss on " + cls, t);
        }
    }

    /** 有界补绑链：overlay 回来即停手，三次不成记录放弃（不做无限重试）。 */
    private static void scheduleRebind(final Object conn, final int attempt) {
        if (attempt >= REBIND_DELAYS.length) {
            ModLog.i("OverlayKeepAlive: rebind chain exhausted after "
                    + REBIND_DELAYS.length + " attempts");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (overlayOf(conn) != null) {
                        ModLog.i("OverlayKeepAlive: overlay alive at rebind#" + attempt
                                + ", chain stopped");
                        return;
                    }
                    // mConnected 已在 onBindingDied 钩子里立即复位。此刻若又为 true，说明本轮
                    // bindService 已发起、正在等 onServiceConnected —— 再 bind 只会叠加引用
                    // 计数（addService 永不匹配 unbind 的泄漏），所以跳过、交给下一轮探测。
                    if (XposedHelpers.getBooleanField(conn, "mConnected")) {
                        ModLog.i("OverlayKeepAlive: rebind#" + attempt
                                + " skipped (bound, awaiting onServiceConnected)");
                        scheduleRebind(conn, attempt + 1);
                        return;
                    }
                    Object ok = XposedHelpers.callMethod(conn, "connect");
                    ModLog.i("OverlayKeepAlive: rebind#" + attempt + " -> " + ok);
                } catch (Throwable t) {
                    ModLog.e("OverlayKeepAlive: rebind#" + attempt + " failed", t);
                }
                scheduleRebind(conn, attempt + 1);
            }
        }, REBIND_DELAYS[attempt]);
    }

    /* ---------------- 工具 ---------------- */

    private static Object overlayOf(Object conn) {
        try {
            return XposedHelpers.getObjectField(conn, "mLauncherOverlay");
        } catch (Throwable t) {
            return null;
        }
    }

    /** mPackageName 声明在 LauncherOverlayConnection（父类，private final）/
     *  LauncherClientService（自有），取不到按"非目标"处理。 */
    private static boolean isPaTarget(Object conn) {
        try {
            return PA_PKG.equals(XposedHelpers.getObjectField(conn, "mPackageName"));
        } catch (Throwable t) {
            if (!sFieldMissLogged) {
                sFieldMissLogged = true;
                ModLog.e("OverlayKeepAlive: mPackageName miss -> passthrough (native)", t);
            }
            return false;
        }
    }
}
