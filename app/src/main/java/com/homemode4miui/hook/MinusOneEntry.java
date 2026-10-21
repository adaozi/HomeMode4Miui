package com.homemode4miui.hook;

import android.app.Activity;

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
 *   ① 官方状态机（主）：Launcher.getStateManager().goToState(LauncherState.FEED_STATE, true)
 *      —— FeedSwipeController 原生同路径，桌面让位/模糊/overlay 转场全部由状态机编排；
 *      OS2 的 LauncherState 声明于 Launcher 侧、OS3 上移 BaseLauncher，反射按实例取证。
 *   ② overlay 协议（备）：Launcher.mLauncherAssistantOverlay（LauncherOverlay 接口，实为
 *      AssistantLauncherOverlay）.mClient.showOverlay(0) → ILauncherOverlay.openOverlay(int)，
 *      由 personalassistant 自己滑出，零视图干预。状态机路径失效时兜底。
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
        if (isShowing(launcher)) {
            ModLog.i("MinusOneEntry.open: already showing");
            return true;
        }
        if (!isUseMiuiMinusScreen(launcher)) {
            ModLog.i("MinusOneEntry.open: host reports isUseMiuiMinusScreen=false, skip");
            return false;
        }
        // ① 状态机主路径
        if (goToFeedState(launcher)) {
            ModLog.i("MinusOneEntry.open: dispatched via goToState(FEED_STATE)");
            return true;
        }
        // ② overlay 协议兜底
        if (showViaClient(launcher)) {
            ModLog.i("MinusOneEntry.open: dispatched via LauncherClient.showOverlay");
            return true;
        }
        ModLog.e("MinusOneEntry.open: no path available (state machine + overlay client both failed)", null);
        return false;
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

    private static Object clientOf(Activity launcher) {
        try {
            Object overlay = XposedHelpers.getObjectField(launcher, "mLauncherAssistantOverlay");
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

    private static boolean showViaClient(Activity launcher) {
        Object client = clientOf(launcher);
        if (client == null) return false;
        try {
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
