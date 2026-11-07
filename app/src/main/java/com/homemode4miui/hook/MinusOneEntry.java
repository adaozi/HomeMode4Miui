package com.homemode4miui.hook;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import com.homemode4miui.util.ModLog;

import de.robv.android.xposed.XposedHelpers;

/**
 * 负一屏唤出桥 —— 上游 2.0「主页右侧 Widget Screen」入口在本模块的落地实现。
 *
 * 产品决策（User 实锤 2026-10-21）：不移植上游自绘的 Widget Screen 网格页，
 * 入口直接复用 MIUI 原生负一屏（personalassistant 的 LauncherOverlay），
 * 小部件由负一屏自身按传统网格承载。因此本类只做一件事：把"打开/关闭负一屏"
 * 变成宿主进程内的一个静态方法调用，供 Compose 层（GraceLauncherRoute）在
 * 空白区横滑 / Grace Button 手势时触发。
 *
 * 唤出路径（OS2 RELEASE-5.39.40.10843 / OS3 RELEASE-6.01.03 逆向）：
 *   ① overlay 协议（主）：Launcher.mLauncherAssistantOverlay（LauncherOverlay 接口，实为
 *      AssistantLauncherOverlay）.mClient.showOverlay(0) → ILauncherOverlay.openOverlay(int)，
 *      由 personalassistant 自己滑出 —— 与原生左滑完全同一条链路，PA 收到完整的
 *      onOverlayScrollChanged/attach 回调，它内部的 AppWidgetHost 才会 startListening 并
 *      updateAppWidget。client 未就绪时（服务尚未连接）在主线程延迟重试若干轮。
 *   ② 官方状态机（兜底）：Launcher.getStateManager().goToState(LauncherState.FEED_STATE, true)
 *      —— FeedSwipeController 原生同路径，桌面让位/模糊/overlay 转场由状态机编排；但它是
 *      "直接把状态切过去"，PA overlay 视图可能未经由 overlay 协议 attach（装机实锤
 *      2026-10-28：负一屏里已添加的小部件显示"无法添加微件"占位，即 AppWidgetHostView
 *      拿到空 RemoteViews）。因此只在 client 长时间不可用时使用。
 *
 * 明确禁用：MinusOneScreenView.openMinusOne() —— 内部走 workspace.snapToScreen(0) →
 * setCurrentScreenInner(0) → Launcher.addMinusOneView()，会踩 ModuleManager 装载链
 * （2026-10-19 实锤：ROM 缺 miui.module.ModuleManager 时会把原生负一屏装坏）。
 *
 * 前置条件：CustomLauncherMode.supportAssistant() 必须为 true（v2.9.98 已修），
 * 否则负一屏内容链（含小部件卡片）不加载 —— 入口打得开也是空白。
 */
public final class MinusOneEntry {

    private static final String TAG = "HomeMode4Miui";
    private static final String STATE_CLS = "com.miui.home.launcher.LauncherState";
    /** client 重试节奏（ms）：服务连接通常在百毫秒级，最多等 3.87s 再落状态机。 */
    private static final long[] CLIENT_RETRY_DELAYS = {120L, 250L, 500L, 1000L, 2000L};
    /** 状态机兜底后等待 PA 上线的轮询节奏与上限（20 × 400ms ≈ 8s，覆盖重启后冷启动）。 */
    private static final long CLIENT_HEAL_INTERVAL_MS = 400L;
    private static final int CLIENT_HEAL_ATTEMPTS = 20;
    /** 同一时间只允许一条 open 重试链（避免连点/手势抖动叠加多次 showOverlay）。 */
    private static boolean sOpenInFlight = false;
    /** 本进程是否已做过一次"冷启动强制 reattach"（重启后修复 PA 微件绑定，见 showViaClient）。 */
    private static boolean sReattachDone = false;

    private MinusOneEntry() {
    }

    /** 负一屏是否正在显示（宿主 isMinusScreenShowing()；取不到按未显示处理） */
    public static boolean isShowing(Activity launcher) {
        if (launcher == null) return false;
        try {
            Object r = XposedHelpers.callMethod(launcher, "isMinusScreenShowing");
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 打开负一屏；返回是否已下发（true 不代表动画已完成） */
    public static boolean open(Activity launcher) {
        if (launcher == null) {
            ModLog.e("MinusOneEntry.open: launcher is null", null);
            return false;
        }
        // 负一屏自愈联动（2026-11-07）：gate 延迟期（解锁后 provider 注册补全中），
        // 状态机兜底会触发 connect → PA 提前起 → 预载撞未完成注册窗口 → 判死删库
        // （第三方 provider 以旧 appWidgetId 为键的内容映射随之作废，笔记需重新绑定）。
        // deferring 期间直接拒绝 open，等 gate ready 后再开即为健康路径。
        if (com.homemode4miui.hook.MinusOneGate.isDeferring()) {
            ModLog.i("MinusOneEntry.open: gate deferring (provider warming up), skip");
            return false;
        }
        if (isShowing(launcher)) {
            ModLog.i("MinusOneEntry.open: already showing");
            return true;
        }
        if (!isUseMiuiMinusScreen(launcher)) {
            ModLog.i("MinusOneEntry.open: host reports isUseMiuiMinusScreen=false, skip");
            return false;
        }
        // ① overlay 协议优先：与原生左滑同一链路（PA 生命周期完整，widget host 才会
        //    startListening）。这条路径必须排第一 —— 状态机直切会让 PA 的 widget 视图
        //    以空 RemoteViews 渲染成"无法添加微件"占位。
        if (showViaClient(launcher)) {
            ModLog.i("MinusOneEntry.open: dispatched via LauncherClient.showOverlay");
            return true;
        }
        // client 未就绪（overlay 服务还没连上）：主线程延迟重试，期间不阻塞 UI。
        ModLog.i("MinusOneEntry.open: overlay client not ready, scheduling retry chain");
        scheduleOpenRetry(launcher, 0);
        return true;
    }

    /** 关闭负一屏（回到 NORMAL）；返回是否已下发 */
    public static boolean close(Activity launcher) {
        if (launcher == null) return false;
        if (!isShowing(launcher)) return true;
        if (hideViaClient(launcher)) {
            ModLog.i("MinusOneEntry.close: dispatched via LauncherClient.hideOverlay");
            return true;
        }
        if (goToNormalState(launcher)) {
            ModLog.i("MinusOneEntry.close: dispatched via goToState(NORMAL)");
            return true;
        }
        ModLog.e("MinusOneEntry.close: no path available", null);
        return false;
    }

    /* ---------------- 内部：overlay client 就绪等待 ---------------- */

    /**
     * client 重试链（主线程 postDelayed，绝不阻塞）：
     * overlay 服务（personalassistant）在桌面冷启动时尚未连接，`mClient` 为 null；
     * 此时如果直接走状态机，PA 的 overlay 视图是被"强行摆出来"的，它内部的
     * AppWidgetHost 没有收到 attach/scroll 回调 → 已添加的小部件渲染成
     * "无法添加微件"占位（2026-10-28 装机实锤）。因此宁可等一小会儿走正规协议。
     */
    private static void scheduleOpenRetry(Activity launcher, int attempt) {
        if (sOpenInFlight) return;
        sOpenInFlight = true;
        retryStep(launcher, attempt);
    }

    private static void retryStep(Activity launcher, int attempt) {
        if (launcher == null || launcher.isFinishing() || launcher.isDestroyed()) {
            sOpenInFlight = false;
            return;
        }
        if (isShowing(launcher)) {
            sOpenInFlight = false;
            ModLog.i("MinusOneEntry.open: already showing during retry#" + attempt);
            return;
        }
        if (attempt >= CLIENT_RETRY_DELAYS.length) {
            // 最终兜底：状态机（内容可能不完整，但至少能进入负一屏）
            if (goToFeedState(launcher)) {
                ModLog.i("MinusOneEntry.open: fell back to goToState(FEED_STATE) after client retries");
                // 继续等待 PA 上线并在就绪瞬间自愈（重启手机后 PA 冷启动可能远超重试窗口，
                // 只补一次 nudge 覆盖不到 —— 这里转入最长约 8s 的低频轮询）。
                startClientAwaitHeal(launcher, CLIENT_HEAL_ATTEMPTS);
            } else {
                sOpenInFlight = false;
                ModLog.e("MinusOneEntry.open: no path available (overlay client + state machine)", null);
            }
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (showViaClient(launcher)) {
                    sOpenInFlight = false;
                    ModLog.i("MinusOneEntry.open: dispatched via showOverlay after retry#" + attempt);
                    return;
                }
                retryStep(launcher, attempt + 1);
            }
        }, CLIENT_RETRY_DELAYS[attempt]);
    }

    /**
     * 状态机兜底后的"等 PA 上线"自愈轮询。
     *
     * 装机场景（2026-10-28）：重启手机后 PA（personalassistant）进程尚未被拉起，
     * overlay client 长时间为 null → 首次进入负一屏只能走状态机 → 小部件显示
     * "无法添加微件"。PA 真正连上的时刻不确定（可能 1s，也可能 6s+），所以不走
     * 固定等待，而是低频轮询：一旦 client 就绪立刻 reattach + showOverlay，
     * 补上 PA 内部 AppWidgetHost 的 startListening，让已保存的微件重新绑定。
     */
    private static void startClientAwaitHeal(final Activity launcher, final int attemptsLeft) {
        if (attemptsLeft <= 0) {
            sOpenInFlight = false;
            ModLog.i("MinusOneEntry.heal: overlay client never ready (give up after "
                    + CLIENT_HEAL_ATTEMPTS + " polls)");
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (launcher == null || launcher.isFinishing() || launcher.isDestroyed()) {
                    sOpenInFlight = false;
                    return;
                }
                if (showViaClient(launcher)) {
                    sOpenInFlight = false;
                    ModLog.i("MinusOneEntry.heal: overlay client ready at poll#"
                            + (CLIENT_HEAL_ATTEMPTS - attemptsLeft) + " -> overlay dispatched");
                    verifyAttachLater(launcher);
                    return;
                }
                startClientAwaitHeal(launcher, attemptsLeft - 1);
            }
        }, CLIENT_HEAL_INTERVAL_MS);
    }

    /** 自愈后回读 attach 状态（诊断用：true 说明 PA 内容链已挂载，微件才会被 update）。 */
    private static void verifyAttachLater(final Activity launcher) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Object overlay = overlayOf(launcher);
                    ModLog.i("MinusOneEntry.heal: overlayAttached=" + isOverlayAttached(overlay)
                            + " showing=" + isShowing(launcher));
                } catch (Throwable ignored) {
                    // 诊断路径，失败不影响主链
                }
            }
        }, 900L);
    }

    /* ---------------- 内部：状态机 ---------------- */

    private static boolean goToFeedState(Activity launcher) {
        return goToState(launcher, "FEED_STATE");
    }

    private static boolean goToNormalState(Activity launcher) {
        return goToState(launcher, "NORMAL");
    }

    private static boolean goToState(Activity launcher, String stateField) {
        try {
            Object state = XposedHelpers.getStaticObjectField(
                    XposedHelpers.findClass(STATE_CLS, launcher.getClassLoader()), stateField);
            if (state == null) return false;
            Object sm = XposedHelpers.callMethod(launcher, "getStateManager");
            if (sm == null) return false;
            XposedHelpers.callMethod(sm, "goToState", state, true);
            return true;
        } catch (Throwable t) {
            ModLog.e("MinusOneEntry.goToState(" + stateField + ") failed", t);
            return false;
        }
    }

    /* ---------------- 内部：overlay 协议 ---------------- */

    /** BaseLauncher.mLauncherAssistantOverlay（LauncherOverlay 接口，实现 LauncherOverlayMIUI）。 */
    private static Object overlayOf(Activity launcher) {
        try {
            return XposedHelpers.getObjectField(launcher, "mLauncherAssistantOverlay");
        } catch (Throwable t) {
            ModLog.e("MinusOneEntry.overlayOf failed", t);
            return null;
        }
    }

    private static Object clientOf(Activity launcher) {
        try {
            Object overlay = overlayOf(launcher);
            if (overlay == null) return null;
            Object client = XposedHelpers.getObjectField(overlay, "mClient");
            if (client == null) {
                ModLog.i("MinusOneEntry: overlay client is null (service not connected yet)");
            }
            return client;
        } catch (Throwable t) {
            ModLog.e("MinusOneEntry.clientOf failed", t);
            return null;
        }
    }

    /** LauncherOverlay.isOverlayAttached()：PA 的 overlay 内容链是否已挂载（OS2/OS3 同签名）。 */
    private static boolean isOverlayAttached(Object overlay) {
        if (overlay == null) return false;
        try {
            Object r = XposedHelpers.callMethod(overlay, "isOverlayAttached");
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            // 方法缺失（老版本）：按已挂载处理，避免多余的 reattach
            return true;
        }
    }

    /**
     * overlay 协议唤出。
     *
     * 关键修复（2026-10-28）：「无法添加微件」的直接成因是 PA 的 overlay 从未 attach ——
     * 此时 personalassistant 内部的 AppWidgetHost 没有 startListening，已保存的
     * appWidgetId 收不到 updateAppWidget，AppWidgetHostView 就渲染默认错误文本。
     * 因此未 attach 时先 `LauncherClient.reattachOverlay()`（PA 重新挂载内容），
     * 再延迟 showOverlay(0) —— 顺序反了（先 show 后 attach）PA 会拿空内容渲染。
     */
    private static boolean showViaClient(Activity launcher) {
        Object overlay = overlayOf(launcher);
        Object client = clientOf(launcher);
        if (client == null) return false;
        try {
            boolean attached = isOverlayAttached(overlay);
            // 冷启动强制重建（2026-10-31 日志实锤）：桌面进程重启后首次打开负一屏，
            // `isOverlayAttached()` 已经是 true、showOverlay 也下发成功，但 PA 里已保存的
            // 微件仍渲染成"无法添加微件" —— 说明 attach 标记不等于 widget host 已恢复绑定。
            // 进程内首次打开时无条件 reattach 一次，让 PA 重建内容视图并重新 startListening；
            // 之后（同一进程）不再干预，避免每次滑出都重载内容。
            boolean needReattach = !attached || !sReattachDone;
            ModLog.i("MinusOneEntry.showViaClient: attached=" + attached
                    + " firstOpen=" + (!sReattachDone) + " reattach=" + needReattach);
            if (needReattach) {
                sReattachDone = true;
                try {
                    XposedHelpers.callMethod(client, "reattachOverlay");
                    ModLog.i("MinusOneEntry.showViaClient: reattachOverlay dispatched");
                } catch (Throwable t) {
                    ModLog.e("MinusOneEntry.reattachOverlay failed", t);
                }
                // attach 是异步 binder：给 PA 一点时间再滑出
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            XposedHelpers.callMethod(client, "showOverlay", 0);
                            ModLog.i("MinusOneEntry.showViaClient: showOverlay dispatched after reattach");
                        } catch (Throwable t) {
                            ModLog.e("MinusOneEntry.showOverlay(after reattach) failed", t);
                        }
                    }
                }, 300L);
                return true;
            }
            XposedHelpers.callMethod(client, "showOverlay", 0);
            return true;
        } catch (Throwable t) {
            ModLog.e("MinusOneEntry.showOverlay failed", t);
            return false;
        }
    }

    private static boolean hideViaClient(Activity launcher) {
        Object client = clientOf(launcher);
        if (client == null) return false;
        try {
            XposedHelpers.callMethod(client, "hideOverlay", 0);
            return true;
        } catch (Throwable t) {
            ModLog.e("MinusOneEntry.hideOverlay failed", t);
            return false;
        }
    }

    /* ---------------- 内部：宿主能力位 ---------------- */

    /** 宿主是否启用 MIUI 负一屏（国际版/关闭时 false；取不到按可用处理，交给后续路径判定） */
    private static boolean isUseMiuiMinusScreen(Activity launcher) {
        try {
            Object r = XposedHelpers.callMethod(launcher, "isUseMiuiMinusScreen");
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            ModLog.i("MinusOneEntry: isUseMiuiMinusScreen() absent — assume available");
            return true;
        }
    }
}
