package com.homemode4miui.hook;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import com.homemode4miui.util.ModLog;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * OS4 运行时结构探针（只读，零行为改动）：
 *
 * 背景：OS4 宿主 APK 无 dex（hasCode=false），桌面 UI 是 Flutter（libapp.so）+
 * Rust 核心（libapp_launcher.so），Java Activity 壳由系统共享库
 * hyperos.rustruntime.v5（uses-library 型 JAR，v2.9.62 实锤）提供。
 * uiautomator 对 Flutter 桌面返回 null root，无法从外部看 UI 结构。
 *
 * phase 3 实锤 LSPosed legacy 注入在 OS4 launcher 进程内可用（宿主进程能加载
 * 模块 dex、能 hook 框架类）——因此本探针 hook 框架类 Instrumentation（任何
 * 进程都有），在 Activity 创建后遍历 DecorView 树，回答三个架构问题：
 *   1. 桌面 Activity 壳是什么类（rustruntime 提供的 shell 类名）；
 *   2. Flutter 渲染面是什么（FlutterView / FlutterSurfaceView / TextureView），
 *      挂在 DecorView 树的哪个位置；
 *   3. 宿主 classloader 里有哪些可用类（io.flutter.* / 宿主旧类是否真的消失）。
 *
 * 这些答案直接决定「模块 Compose 桌面上挂载到 OS4 窗口层」的落点设计。
 * 所有遍历均为只读；探测类名用 Class.forName(name, false) 不触发初始化。
 */
public final class Os4Probe {

    private static final String TAG = "HomeMode4Miui";

    /** 只 dump 每个活动类名的第一棵树（Launcher 可能重建，防日志爆炸） */
    private static final Set<String> sDumpedActivities = new HashSet<>();

    private Os4Probe() {
    }

    /** OS4 家族判定 + 框架类 hook 安装；非 OS4 静默退出 */
    public static void install(XC_LoadPackage.LoadPackageParam lpp, ClassLoader cl) {
        // MainHook 的 0.HostCompat.detect 先于本探针执行，家族已就绪
        if (HostCompat.family() != HostCompat.FAMILY_OS4) {
            ModLog.i("Os4Probe: family=" + HostCompat.label()
                    + " (vc=" + HostCompat.hostVersionCode() + "), skip");
            return;
        }
        ModLog.i("Os4Probe: OS4 family detected (vc="
                + HostCompat.hostVersionCode() + "), installing framework probes");

        // classloader 类探测（不初始化）：宿主旧类 / Flutter 嵌入层可用性
        String[] classProbes = {
                "com.miui.home.Launcher",
                "com.miui.home.launcher.Launcher",
                "io.flutter.embedding.android.FlutterView",
                "io.flutter.embedding.android.FlutterActivity",
                "io.flutter.embedding.android.FlutterSurfaceView",
        };
        for (String name : classProbes) {
            try {
                Class.forName(name, false, cl);
                ModLog.i("Os4Probe: classloader HAS " + name);
            } catch (Throwable notFound) {
                ModLog.i("Os4Probe: classloader MISSING " + name);
            }
        }

        // Activity 创建探针（框架类，OS4 进程内必然存在）
        try {
            XposedHelpers.findAndHookMethod("android.app.Instrumentation", cl,
                    "callActivityOnCreate", Activity.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                onActivityCreated((Activity) param.args[0]);
                            } catch (Throwable t) {
                                ModLog.e("Os4Probe: onCreate probe failed", t);
                            }
                        }
                    });
            ModLog.i("Os4Probe: Instrumentation.callActivityOnCreate hooked");
        } catch (Throwable t) {
            ModLog.e("Os4Probe: Instrumentation hook failed", t);
        }
    }

    private static void onActivityCreated(Activity act) {
        final String cls = act.getClass().getName();
        ModLog.i("Os4Probe: activity created: " + cls);
        if (!sDumpedActivities.add(cls)) {
            return; // 每个活动类只 dump 第一棵树
        }
        final Window window = act.getWindow();
        if (window == null || window.getDecorView() == null) {
            ModLog.i("Os4Probe: no window/decor for " + cls);
            return;
        }
        final View decor = window.getDecorView();
        // Flutter 首帧渲染在 onCreate 之后，延迟遍历确保视图已挂载
        decor.postDelayed(() -> {
            try {
                ModLog.i("Os4Probe: ==== view tree of " + cls + " ====");
                dumpTree(decor, act, 0);
                ModLog.i("Os4Probe: ==== end of view tree ====");
            } catch (Throwable t) {
                ModLog.e("Os4Probe: tree dump failed", t);
            }
        }, 2000L);
    }

    private static void dumpTree(View view, Activity act, int depth) {
        if (view == null || depth > 14) return;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("- ").append(view.getClass().getName());
        final int id = view.getId();
        if (id != View.NO_ID) {
            try {
                final String entry = act.getResources()
                        .getResourceEntryName(id);
                if (entry != null) sb.append(" id=").append(entry);
            } catch (Throwable ignored) {
                // 非宿主资源包的 id，跳过名称解析
            }
        }
        sb.append(" [").append(view.getWidth()).append('x')
                .append(view.getHeight()).append("] vis=").append(view.getVisibility());
        if (view.getAlpha() < 1f) sb.append(" alpha=").append(view.getAlpha());
        ModLog.i("Os4Probe: " + sb);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpTree(group.getChildAt(i), act, depth + 1);
            }
        }
    }
}
