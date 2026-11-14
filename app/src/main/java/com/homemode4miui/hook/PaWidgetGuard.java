package com.homemode4miui.hook;

import android.content.Context;
import android.os.Process;

import com.homemode4miui.util.ModLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * PA Widget 删除拦截（v5，负一屏自愈链最后防线）。
 *
 * 1. 双层删除拦截（v4 起**条件拦截**）：判死流程（loadWidget 查 invalid → remove）
 *    的删除动作有两条路径 —— AppWidgetHost.deleteAppWidgetId（host 层）与
 *    IAppWidgetService$Stub$Proxy.deleteAppWidgetId（binder 直调，此前漏拦）。
 *
 *    v3 及以前是无条件 no-op，2026-11-13 装机实锤它有致命副作用：
 *    PA 早于 provider 注册恢复 widget → bind 失败 → AMS 侧该 id 的 provider 为空
 *    → PA 判定 invalid 想删掉重来 → 拦截把删除挡下 → **留下一个永无 provider 的
 *    僵尸 id**，PA 只能一直渲染"无法添加微件"（dumpsys appwidget 里该 widget
 *    只有 host 行、没有 provider 行，即为此态）。
 *
 *    v4 规则：只有"该 id 仍有可用 provider"（getAppWidgetInfo != null）时才拦 ——
 *    这种 id 的 provider 还在，拦下来保住第三方按 appWidgetId 键控的内容映射
 *    （笔记绑定等）；provider 已空的 id 本来也渲染不出任何内容，放行让 PA 正常
 *    清理 + 重新绑定，避免僵尸占位。
 *
 * 2. v5 **移除**"就绪后自杀重载"看门狗：PA 进程刚起时 AppWidgetManager 读数恒为 0
 *    （进程 init 中，索引未建），1s 后才变成 24 —— 每秒轮询必然判"缩水 → 就绪 →
 *    自杀"，实测连杀 4 轮，最后把 launcher 侧绑定拖成 CR IMP DEAD，PA 不再被系统
 *    拉起，负一屏直接空掉。现在只记录启动快照，不杀进程。
 *
 * 3. v6 **startListening 时序闸**：微件恢复发生在 AppWidgetHost 开始监听那一刻。
 *    开机后立刻滑出负一屏，等于让 PA 在 CE 区 provider 未注册完时就 bindAppWidgetId
 *    → 失败 → id 落成"无 provider"僵尸。v6 对这种情形把 startListening 挂起重试
 *    （500ms × 40 = 20s 上限，就绪或超时立刻放行），从源头避免造出僵尸 id。
 *    （2026-11-13 用户实测：开机即开负一屏必坏，等一会儿再开则正常。）
 *
 * 副作用（v4 收敛后）：用户手动删除小部件时，若 provider 仍在则删除被拦 → 系统
 *    id 残留孤儿（PA 库 entity 正常删除、界面正确消失），孤儿由 MIUI 自身周期清理
 *    兜底；provider 已空的不再拦。全程 ModLog 可审计。
 *
 * 装载：MainHook.handleLoadPackage 对 com.miui.personalassistant 的分支
 * （LSPosed 静态作用域见 assets/scope.list + META-INF/xposed/scope.list）。
 */
public final class PaWidgetGuard {

    /** startListening 推迟参数：500ms × 40 = 20s 上限，超时强制放行。 */
    private static final long DEFER_INTERVAL_MS = 500L;
    private static final int DEFER_MAX_ATTEMPTS = 40;

    private static volatile boolean sReadyLatched = false;
    private static volatile int sDeferTotal = 0;
    /** 已挂起重试的 host（弱引用，避免持有 PA 的 host 实例）。 */
    private static final java.util.WeakHashMap<Object, Boolean> sPendingHosts =
            new java.util.WeakHashMap<>();

    private PaWidgetGuard() {
    }

    /** MainHook 挂载入口（PA 进程）。 */
    public static void install(ClassLoader cl) {
        // ---- 第一层：host 层删除拦截（条件拦截，见类注释 v4） ----
        try {
            XposedHelpers.findAndHookMethod(
                    "android.appwidget.AppWidgetHost", cl, "deleteAppWidgetId",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            final int id = (Integer) param.args[0];
                            if (!hasProvider(id)) {
                                ModLog.i("PaWidgetGuard[host]: deleteAppWidgetId(" + id
                                        + ") allowed (provider already gone -> zombie cleanup)");
                                return;
                            }
                            ModLog.i("PaWidgetGuard[host]: deleteAppWidgetId(" + id
                                    + ") blocked (provider alive, id preserved)");
                            param.setResult(null);
                        }
                    });
            ModLog.i("PaWidgetGuard: host-layer installed (conditional)");
        } catch (Throwable t) {
            ModLog.e("PaWidgetGuard: host-layer install failed", t);
        }
        // ---- 第二层：binder proxy 层删除拦截（此前漏拦的直调路径，同样条件化） ----
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.internal.appwidget.IAppWidgetService$Stub$Proxy",
                    null, "deleteAppWidgetId", String.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            final int id = (Integer) param.args[1];
                            if (!hasProvider(id)) {
                                ModLog.i("PaWidgetGuard[binder]: deleteAppWidgetId(" + id
                                        + ") allowed (provider already gone -> zombie cleanup)");
                                return;
                            }
                            ModLog.i("PaWidgetGuard[binder]: deleteAppWidgetId(" + id
                                    + ") blocked (provider alive, id preserved)");
                            param.setResult(null);
                        }
                    });
            ModLog.i("PaWidgetGuard: binder-layer installed (conditional)");
        } catch (Throwable t) {
            ModLog.e("PaWidgetGuard: binder-layer install failed", t);
        }
        // ---- 第四层：startListening 时序闸（provider 未注册完不让 PA 恢复 widget）----
        installListeningGate(cl);
        // ---- 启动快照（观测用）----
        installCleanReloadWatch();
    }

    /**
     * 该 widget id 是否仍有可用 provider。
     * getAppWidgetInfo 对"provider 已空 / id 不存在"一律返回 null —— 正是僵尸态特征。
     * 探测失败按"仍有 provider"处理（保守：继续拦，宁可留孤儿也不丢内容映射）。
     */
    private static boolean hasProvider(int id) {
        try {
            Context app = currentApp();
            if (app == null) return true;
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(app);
            return mgr.getAppWidgetInfo(id) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * v6：推迟 PA 的 `AppWidgetHost.startListening()`，直到已知 widget provider 注册完成。
     *
     * 这是"微件无法添加"的最后一环：PA 的 widget 恢复发生在 host 开始监听的那一刻
     * （startListening → 按自己库里的 appWidgetId 逐个 bindAppWidgetId）。开机后立刻
     * 滑出负一屏 = 把这一步提前到 CE 区 provider 还没注册完的窗口里，bind 失败 → AMS
     * 侧该 id 的 provider 落空 → 渲染"无法添加微件"（2026-11-13 用户实测：开机即开
     * 负一屏必坏，等一会儿开则正常）。
     *
     * 干预方式：只在"已知 provider 缺失"时把这一次 startListening 挂起，500ms 后重试，
     * 最长 20s；就绪或超时后一律放行（绝不永久卡住 PA 的微件功能）。就绪后闩死，
     * 后续 startListening 直接走原生，不再重复扫描。
     */
    private static void installListeningGate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.appwidget.AppWidgetHost", cl, "startListening",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) {
                            try {
                                if (sReadyLatched) return;
                                if (widgetsReady()) {
                                    sReadyLatched = true;
                                    ModLog.i("PaWidgetGuard: providers ready -> startListening allowed");
                                    return;
                                }
                                if (sDeferTotal >= DEFER_MAX_ATTEMPTS) {
                                    sReadyLatched = true;
                                    ModLog.i("PaWidgetGuard: provider wait timed out after "
                                            + (DEFER_MAX_ATTEMPTS * DEFER_INTERVAL_MS)
                                            + "ms -> startListening forced");
                                    return;
                                }
                                sDeferTotal++;
                                param.setResult(null);
                                ModLog.i("PaWidgetGuard: startListening deferred #" + sDeferTotal
                                        + " (known providers missing)");
                                deferAgain(param.thisObject);
                            } catch (Throwable t) {
                                ModLog.e("PaWidgetGuard: listening gate error, passthrough", t);
                            }
                        }
                    });
            ModLog.i("PaWidgetGuard: startListening gate installed");
        } catch (Throwable t) {
            ModLog.e("PaWidgetGuard: startListening gate install failed", t);
        }
    }

    /** 挂起的 startListening 逐个重投；同一 host 只保留一条重试链。 */
    private static void deferAgain(final Object host) {
        synchronized (sPendingHosts) {
            if (sPendingHosts.containsKey(host)) return;
            sPendingHosts.put(host, Boolean.TRUE);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (sPendingHosts) {
                    sPendingHosts.remove(host);
                }
                try {
                    XposedHelpers.callMethod(host, "startListening");
                } catch (Throwable t) {
                    ModLog.e("PaWidgetGuard: deferred startListening retry failed", t);
                }
            }
        }, DEFER_INTERVAL_MS);
    }

    /** 已知 provider（misettings / notes）是否都已可见；就绪结果闩死。 */
    private static boolean widgetsReady() {
        try {
            Context app = currentApp();
            if (app == null) return false;
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(app);
            java.util.List<?> l = mgr.getInstalledProvidersForProfile(Process.myUserHandle());
            if (l == null) return false;
            boolean misettings = false;
            boolean notes = false;
            for (Object o : l) {
                if (o instanceof android.appwidget.AppWidgetProviderInfo) {
                    final String pkg = ((android.appwidget.AppWidgetProviderInfo) o)
                            .provider.getPackageName();
                    if ("com.xiaomi.misettings".equals(pkg)) misettings = true;
                    else if ("com.miui.notes".equals(pkg)) notes = true;
                }
            }
            return misettings && notes;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void installCleanReloadWatch() {
        // v5（2026-11-13 装机实锤）：**取消自杀重载**。原实现在 PA 进程起来的瞬间读
        // `getInstalledProvidersForProfile` 恒为 0（进程刚 init，AppWidgetManager 还没
        // 建索引），1s 后才变成 24 —— 于是每次启动都判"缩水 → 就绪 → 自杀重载"，
        // 形成死循环（实测 4 轮），最终把 launcher 侧的绑定拖成 CR IMP DEAD，
        // PA 再也不被系统拉起，负一屏直接空掉。
        //
        // 现在的防线改为"不杀进程"：
        //   ① 条件删除拦截（见类注释）—— 僵尸 id 由 PA 自己清理，不再被我保住；
        //   ② MinusOneGate 延迟 connect —— 尽量让 PA 晚于 CE provider 注册启动；
        //   ③ 这里只留观测：把启动时的注册表快照记进日志，供排障判断是否真的缩水。
        final int n = countProviders();
        ModLog.i("PaWidgetGuard: registry snapshot at PA start = " + n
                + " providers (reload-by-kill disabled since v5; "
                + "zombie ids are cleaned by PA itself)");
    }

    private static int countProviders() {
        try {
            Context app = currentApp();
            if (app == null) return 0;
            android.appwidget.AppWidgetManager mgr =
                    android.appwidget.AppWidgetManager.getInstance(app);
            java.util.List<?> l = mgr.getInstalledProvidersForProfile(
                    Process.myUserHandle());
            return l == null ? 0 : l.size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Context currentApp() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread",
                            PaWidgetGuard.class.getClassLoader()),
                    "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }
}
