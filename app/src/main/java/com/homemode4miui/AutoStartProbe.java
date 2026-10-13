/*
 * gate6：MIUI 自启动授权探测 —— 新建文件（非搬运）。运行在【模块 App 进程】。
 *
 * 背景（用户定案）：自启动是模块后台保活的前提（进程被杀后系统能否拉起、
 * listener 能否重绑），与通知授权/root 并列纳入第四桌面前置条件。
 *
 * 查询通道：MIUI/HyperOS 私有 AppOps op code 10008（AUTO_START）——
 * SecurityCenter「自启动」开关的底层落点，社区已验证多年的读法。
 * checkOpNoThrow 是普通公开方法（反射调，兼容 SDK 变更），且查的是
 * 【本模块自己包】的 op —— 无 gate5 实锤的 checkCallerIsSystemOrSameApp
 * 跨包限制（宿主进程查模块包才会撞上，所以宿主侧判定走 SP 标志，与
 * root 标志同模式：模块探测 → 广播 → 宿主 SP）。
 *
 * 三态语义（防 gate3 式误锁）：
 *   GRANTED  → 绿，前置满足；
 *   DENIED   → 红，阻断 gate，引导跳 MIUI 自启动设置页；
 *   UNKNOWN  → 黄（op 不存在/反射失败/机型差异），【不阻断】gate ——
 *              HyperOS 版本漂移时宁可放行，不把用户锁死在前置条件上。
 */
package com.homemode4miui;

import android.content.Context;
import android.content.Intent;

public final class AutoStartProbe {

    public static final int GRANTED = 1;
    public static final int DENIED = 2;
    public static final int UNKNOWN = 3;

    /** MIUI 私有 op：AUTO_START（SecurityCenter 自启动开关） */
    private static final int OP_AUTO_START = 10008;

    /** checkOpNoThrow 反射结果缓存（每次 onResume 全量反射开销；非 MIUI 环境保持 null 反复小代价探测） */
    private static volatile java.lang.reflect.Method sCheckOpNoThrow;

    private AutoStartProbe() {
    }

    /** 探测本模块 MIUI 自启动授权（本进程查自己包，无跨包限制）。 */
    public static int probe(Context ctx) {
        try {
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return UNKNOWN;
            java.lang.reflect.Method m = sCheckOpNoThrow;
            if (m == null) {
                m = ops.getClass().getMethod("checkOpNoThrow", int.class, int.class, String.class);
                sCheckOpNoThrow = m;
            }
            Object result = m.invoke(ops, OP_AUTO_START,
                    android.os.Process.myUid(), ctx.getPackageName());
            int mode = (Integer) result;
            if (mode == android.app.AppOpsManager.MODE_ALLOWED) return GRANTED;
            if (mode == android.app.AppOpsManager.MODE_ERRORED) return UNKNOWN;
            return DENIED; // MODE_IGNORED / MODE_DEFAULT / 其余按未开启处理
        } catch (Throwable t) {
            // 反射失败（方法签名变更/op 不存在/非 MIUI 环境）→ 不阻断
            com.homemode4miui.util.ModLog.i("AutoStartProbe unknown (non-MIUI or op drift): " + t);
            return UNKNOWN;
        }
    }

    /** MIUI 自启动设置页（SecurityCenter；HyperOS 上路径可能漂移，调用方 catch）。 */
    public static Intent settingsIntent() {
        Intent i = new Intent();
        i.setClassName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
