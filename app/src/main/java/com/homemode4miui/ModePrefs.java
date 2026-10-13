package com.homemode4miui;

import android.content.Context;

import com.homemode4miui.util.ModLog;

/**
 * 自定义模式持久化 —— 完全复用宿主原生存储：
 * 与原生抽屉模式开关（PreferenceUtils → BaseSharePreference）同机制，
 * 写入宿主自己的 SP 文件 "launcher_sharedpreference"。
 * 好处：运行在宿主进程内，读写无任何跨进程/权限问题，重启桌面后状态天然保持。
 */
public final class ModePrefs {

    /** 与宿主 PreferenceUtils/BaseSharePreference 一致的 SP 文件名（逆向确认） */
    public static final String HOST_SP_FILE = "launcher_sharedpreference";
    /** 模块 App 本地 UI 状态 SP（仅存开关回显；真正切换以广播 → 宿主写 HOST_SP_FILE 为准） */
    public static final String PREF_FILE = "base_prefs";
    /** 本模式的持久化 key，命名风格与原生 "is_all_apps_drawer_mode_enable" 一致 */
    public static final String KEY_MODE_ENABLE = "is_custom_mode_enable";
    /**
     * gate1：root 保活就绪标志（宿主 SP）。模块首页 su 探测成功后经广播写入 ——
     * root 探测必须由模块 App 进程发起（Magisk 授权框 + 后台线程），宿主进程
     * 不能做；探测结果落宿主 SP 后裁决/挂载链零开销读取。
     */
    public static final String KEY_ROOT_READY = "custom_root_keepalive_ready";
    /**
     * gate6：MIUI 自启动就绪标志（宿主 SP）。模块首页 AppOps(10008) 探测
     * GRANTED 后经广播写入（探测只能模块进程查自己包；宿主查跨包受限），
     * 语义与 KEY_ROOT_READY 同模式。
     */
    public static final String KEY_AUTOSTART_READY = "custom_autostart_ready";

    /* ---- Grace Compose 熔断（防桌面崩溃循环，跨进程持久化在宿主 SP） ----
     * 挂载前置 pending=true；首帧心跳或正常卸载清除。
     * 若挂载后进程在心跳前死亡（帧阶段未捕获异常，任何 hook 都接不住），
     * pending 跨重启存活 → 计一次失败；连续 MAX 次 → dead=true 永久降级占位视图。 */
    public static final String KEY_GRACE_FAILURES = "custom_grace_compose_failures";
    public static final String KEY_GRACE_PENDING = "custom_grace_compose_pending";
    public static final String KEY_GRACE_DEAD = "custom_grace_compose_dead";
    public static final String KEY_GRACE_VERSION = "custom_grace_version_code";
    /** 连续挂载崩溃达到该次数后熔断降级 */
    public static final int GRACE_MAX_FAILURES = 2;

    private ModePrefs() {
    }

    private static android.content.SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(HOST_SP_FILE, Context.MODE_PRIVATE);
    }

    /** 读取开关（宿主进程内任意时机可用） */
    public static boolean isModeEnabled(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_MODE_ENABLE, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 【已固化，无开关】负一屏手势拦截语义：自定义模式激活时 GestureIsolator
     * 恒定隔离全部宿主手势控制器（含负一屏/看点链），用户不可选择。
     * v2.9.42：预留的主动打开入口因零调用方删除，本模式下负一屏无任何入口。
     */

    /** gate1：root 保活就绪（宿主进程读；模块首页经广播写） */
    public static boolean isRootKeepAliveReady(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_ROOT_READY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** gate1：root 保活就绪写入（宿主进程；收到模块广播时调用） */
    public static void setRootKeepAliveReady(Context ctx, boolean ready) {
        try {
            sp(ctx).edit().putBoolean(KEY_ROOT_READY, ready).commit();
        } catch (Throwable t) {
            ModLog.e("ModePrefs setRootKeepAliveReady failed", t);
        }
    }

    /** gate6：MIUI 自启动就绪（宿主进程读；模块首页探测后经广播写） */
    public static boolean isAutoStartReady(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_AUTOSTART_READY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** gate6：MIUI 自启动就绪写入（宿主进程；收到模块广播时调用） */
    public static void setAutoStartReady(Context ctx, boolean ready) {
        try {
            sp(ctx).edit().putBoolean(KEY_AUTOSTART_READY, ready).commit();
        } catch (Throwable t) {
            ModLog.e("ModePrefs setAutoStartReady failed", t);
        }
    }

    /**
     * 切换开关并触发【原生切换链路】：
     * LauncherModeController.onLauncherModeChanged()（private static，反射调用）
     *   → enableAndResetCache()（重读 loadCurrentLauncherMode，命中我们的 after-hook）
     *   → Launcher.onLauncherModeChanged()（原生 UI 重建广播）
     *   → EventBus LauncherModeChangedMessage
     * 与用户在系统设置里切抽屉/极简走的链路完全相同。
     */
    public static void setModeEnable(Context ctx, boolean on) {
        boolean changed = sp(ctx).getBoolean(KEY_MODE_ENABLE, false) != on;
        if (!changed) return;
        sp(ctx).edit().putBoolean(KEY_MODE_ENABLE, on).commit();
        callNativeOnLauncherModeChanged(ctx);
    }

    /** 反射触发宿主私有静态方法（跨类加载器必须走 Xposed 反射）；
     *  按名扫描无参静态方法，兼容宿主版本签名漂移 */
    public static void callNativeOnLauncherModeChanged(Context ctx) {
        try {
            Class<?> controller = Class.forName(
                    "com.miui.home.launcher.allapps.LauncherModeController",
                    true, ctx.getClassLoader());
            for (java.lang.reflect.Method m : controller.getDeclaredMethods()) {
                if (!"onLauncherModeChanged".equals(m.getName())) continue;
                if (m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                m.invoke(null);
                return;
            }
            android.util.Log.e("HomeMode4Miui",
                    "onLauncherModeChanged() not found in host LauncherModeController");
        } catch (Throwable t) {
            android.util.Log.e("HomeMode4Miui", "onLauncherModeChanged invoke failed", t);
        }
    }

    /* ================= Grace Compose 熔断 ================= */

    public static boolean isGraceComposeDead(Context ctx) {
        try {
            return sp(ctx).getBoolean(KEY_GRACE_DEAD, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void markGraceComposeDead(Context ctx) {
        try {
            sp(ctx).edit().putBoolean(KEY_GRACE_DEAD, true).commit();
        } catch (Throwable t) {
            ModLog.e("ModePrefs markGraceComposeDead failed", t);
        }
    }

    /**
     * 挂载尝试记账：APK 版本变更（新包修复）→ 熔断清零重新放开；
     * 上次 pending 仍在（上次挂载后进程在心跳前死亡）→ 失败计数 +1。
     * 返回当前失败计数，调用方据此决定是否降级。
     */
    public static int onGraceComposeMountAttempt(Context ctx, int versionCode) {
        try {
            android.content.SharedPreferences sp = sp(ctx);
            android.content.SharedPreferences.Editor e = sp.edit();
            if (sp.getInt(KEY_GRACE_VERSION, -1) != versionCode) {
                e.putInt(KEY_GRACE_VERSION, versionCode)
                        .putInt(KEY_GRACE_FAILURES, 0)
                        .putBoolean(KEY_GRACE_PENDING, false)
                        .putBoolean(KEY_GRACE_DEAD, false)
                        .commit();
                return 0;
            }
            if (sp.getBoolean(KEY_GRACE_PENDING, false)) {
                int failures = sp.getInt(KEY_GRACE_FAILURES, 0) + 1;
                e.putInt(KEY_GRACE_FAILURES, failures)
                        .putBoolean(KEY_GRACE_PENDING, false)
                        .commit();
                if (failures > GRACE_MAX_FAILURES) {
                    markGraceComposeDead(ctx);
                    android.util.Log.e("HomeMode4Miui",
                            "grace compose killed the desktop " + failures
                                    + "x without heartbeat -> permanent degrade to placeholder");
                }
                return failures;
            }
            return sp.getInt(KEY_GRACE_FAILURES, 0);
        } catch (Throwable t) {
            android.util.Log.e("HomeMode4Miui", "onGraceComposeMountAttempt failed", t);
            return 0;
        }
    }

    public static void markGraceComposePending(Context ctx) {
        try {
            // perf2（冷启动专项）：挂载路径主线程写盘，commit() 改 apply()。
            // 崩溃安全性评估：pending 需要跨进程死亡存活，但进程死亡发生在首帧
            // traversal（挂载数百 ms 后），此时 apply 的异步落盘早已完成 ——
            // 熔断语义不受影响，主线程磁盘 IO 从关键路径移除。
            sp(ctx).edit().putBoolean(KEY_GRACE_PENDING, true).apply();
        } catch (Throwable t) {
            ModLog.e("ModePrefs markGraceComposePending failed", t);
        }
    }

    /** 首帧心跳：组合+应用已成功，清 pending 与失败计数 */
    public static void onGraceComposeHeartbeat(Context ctx) {
        try {
            sp(ctx).edit()
                    .putBoolean(KEY_GRACE_PENDING, false)
                    .putInt(KEY_GRACE_FAILURES, 0)
                    .apply();
        } catch (Throwable t) {
            ModLog.e("ModePrefs onGraceComposeHeartbeat failed", t);
        }
    }

    /** 挂载链路内捕获到的失败：清 pending（进程还活着，别让下次重复计数）并 +1 */
    public static void onGraceComposeMountFailure(Context ctx) {
        try {
            android.content.SharedPreferences sp = sp(ctx);
            int failures = sp.getInt(KEY_GRACE_FAILURES, 0) + 1;
            android.content.SharedPreferences.Editor e = sp.edit()
                    .putBoolean(KEY_GRACE_PENDING, false)
                    .putInt(KEY_GRACE_FAILURES, failures);
            if (failures > GRACE_MAX_FAILURES) {
                e.putBoolean(KEY_GRACE_DEAD, true);
                android.util.Log.e("HomeMode4Miui",
                        "grace compose mount failed " + failures
                                + "x -> permanent degrade to placeholder");
            }
            e.apply();
        } catch (Throwable t) {
            ModLog.e("ModePrefs onGraceComposeMountFailure failed", t);
        }
    }

    /** 正常卸载（模式切换等，进程未死）：清 pending 防误计 */
    public static void clearGraceComposePending(Context ctx) {
        try {
            sp(ctx).edit().putBoolean(KEY_GRACE_PENDING, false).apply();
        } catch (Throwable t) {
            ModLog.e("ModePrefs clearGraceComposePending failed", t);
        }
    }
}
