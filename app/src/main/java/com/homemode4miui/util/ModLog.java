package com.homemode4miui.util;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志出口（两道闸守则的观测面）：
 *   - 双写：LSPosed 日志（XposedBridge.log，管理器内可见）+ logcat（tag HomeMode4Miui）；
 *   - Throwable 重载打印完整堆栈——弹层等 UI 链路兜底时的定位依据；
 *   - 全自身兜底：日志永远不可能成为二次崩溃源。
 */
public final class ModLog {

    private static final String TAG = "HomeMode4Miui";

    private ModLog() {}

    public static void i(String msg) {
        try {
            XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {
        }
        try {
            android.util.Log.i(TAG, msg);
        } catch (Throwable ignored) {
        }
    }

    public static void e(String msg, Throwable t) {
        try {
            XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {
        }
        if (t != null) {
            try {
                XposedBridge.log(t); // LSPosed 日志里的完整堆栈
            } catch (Throwable ignored) {
            }
            try {
                android.util.Log.e(TAG, msg, t);
            } catch (Throwable ignored) {
            }
        }
    }
}
