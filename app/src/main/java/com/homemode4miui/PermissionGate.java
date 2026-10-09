package com.homemode4miui;

import android.content.Context;
import android.os.SystemClock;

/**
 * gate1：第四桌面使用前置条件 —— 新建文件（非搬运）。
 *
 * 设计（用户定案）：权限必须在【模块首页】确认齐备后才允许使用第四桌面；
 * 权限丢失时桌面自动回落原生。判定本类为宿主/模块两侧共用：
 *   - 通知监听授权：NotificationManager 公开查询 API，任意进程可查任意
 *     component —— 宿主 hook 侧直接查（TTL 缓存防裁决高频穿透 binder）；
 *   - root 保活就绪：su 探测只能由模块 App 进程发起（Magisk 授权框 + 后台
 *     线程），结果由模块首页经广播写进宿主 SP（KEY_ROOT_READY），宿主侧
 *     零开销读标志。root 被用户在 Magisk 撤销的场景：首页重新打开时重新
 *     探测刷新标志（已登记 PORTING 降级说明）。
 *
 * 判定语义：customModeAllowed = 通知授权 && root 就绪。第四模式开关（SP
 * 标志）与 allowed 解耦 —— SP 记住用户意愿，allowed 决定是否实际生效：
 * 权限补齐后无需重新开关，自动恢复。
 */
public final class PermissionGate {

    /** 通知授权查询缓存 TTL：裁决回调可能高频触发，binder 查询不能每次都打 */
    private static final long NOTIF_TTL_MS = 5000;
    private static volatile long sNotifCheckedAt;
    private static volatile boolean sNotifGranted;

    private PermissionGate() {
    }

    /**
     * 通知监听授权（宿主/模块进程均可查；5s TTL 缓存）。
     * gate5（装机实锤）：不能用 NotificationManager.isNotificationListenerAccessGranted
     * —— 该 API 有 checkCallerIsSystemOrSameApp 守卫，只能查【自己包】的
     * component；宿主进程（com.miui.home）查模块 component 恒 SecurityException
     * → false（"宿主侧未读到通知使用权"Toast 的根因）。改用 Settings.Secure
     * 全局 "enabled_notification_listeners"（NMS 授权变更时同步维护，任意
     * 进程可读，模块首页桥自诊断同源）。
     */
    public static boolean notificationAccessReady(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        boolean cached = sNotifGranted;
        if (now - sNotifCheckedAt < NOTIF_TTL_MS) return cached;
        boolean granted;
        try {
            String listeners = android.provider.Settings.Secure.getString(
                    ctx.getContentResolver(), "enabled_notification_listeners");
            granted = listeners != null
                    && listeners.contains("com.homemode4miui/");
        } catch (Throwable t) {
            granted = false;
        }
        sNotifGranted = granted;
        sNotifCheckedAt = now;
        return granted;
    }

    /** root 保活就绪（模块首页 su 探测成功后写入的宿主 SP 标志） */
    public static boolean rootKeepAliveReady(Context ctx) {
        return ModePrefs.isRootKeepAliveReady(ctx);
    }

    /**
     * gate6：MIUI 自启动就绪（模块首页 AppOps 10008 探测 GRANTED 后写入的
     * 宿主 SP 标志）。AppOps 查询有跨包限制，宿主侧只读标志（同 root 模式）。
     */
    public static boolean autoStartReady(Context ctx) {
        return ModePrefs.isAutoStartReady(ctx);
    }

    /** 第四桌面完整可用 = 通知授权 + root 保活就绪 + MIUI 自启动开启 */
    public static boolean customModeAllowed(Context ctx) {
        return notificationAccessReady(ctx) && rootKeepAliveReady(ctx)
                && autoStartReady(ctx);
    }
}
