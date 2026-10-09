package com.miui.home.launcher.allapps;

import android.content.Context;

/**
 * [HOST STUB] 宿主类编译期桩 —— 永不在运行期执行。
 *
 * 只声明宿主中 public 的静态成员（模块会直接调用/读取，运行期解析到宿主真实类）。
 * 私有方法（loadCurrentLauncherMode / onLauncherModeChanged / isMode）【故意不声明】：
 *   模块代码对它们只能通过 Xposed 反射 hook / callStaticMethod 访问，
 *   直接 invoke 会因跨类加载器访问检查抛 IllegalAccessError。
 *
 * 签名来源：apk_analysis/decompiled/LauncherModeController.java。
 */
public class LauncherModeController {

    // 宿主 <clinit> 初始化的三个模式单例（运行期读取宿主字段）
    public static LauncherMode DESKTOP;
    public static LauncherMode DRAWER;
    public static LauncherMode ELDERLYMAN;

    public static LauncherMode[] getAllModes() { throw stub(); }

    public static LauncherMode getCurrentMode() { throw stub(); }

    public static boolean isDesktopMode() { throw stub(); }

    public static boolean isDesktopMode(LauncherMode p1) { throw stub(); }

    public static boolean isDrawerMode() { throw stub(); }

    public static boolean isDrawerMode(LauncherMode p1) { throw stub(); }

    public static boolean isDrawerMode(String p1) { throw stub(); }

    public static boolean isElderlyManMode() { throw stub(); }

    public static boolean isElderlyManMode(LauncherMode p1) { throw stub(); }

    public static boolean isElderlyManMode(String p1) { throw stub(); }

    public static void setDrawerModeEnable(Context p0, boolean p1) { throw stub(); }

    public static void setElderlyManModeEnable(boolean p1) { throw stub(); }

    private static RuntimeException stub() {
        return new RuntimeException("HOST STUB — never executed at runtime");
    }
}
