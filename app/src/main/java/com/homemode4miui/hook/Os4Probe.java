package com.homemode4miui.hook;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.util.HashSet;
import java.util.Set;

/**
 * OS4 运行时结构探针（只读，零行为改动）：
 *
 * 背景：OS4 宿主 APK 无 dex（hasCode=false），桌面 UI 是 Flutter（libapp.so）+
 * Rust 核心（libapp_launcher.so），Java Activity 壳由系统共享库
 * hyperos.rustruntime.v5（uses-library 型 JAR，v2.9.62 实锤）提供。
 * uiautomator 对 Flutter 桌面返回 null root，无法从外部看 UI 结构。
 *
 * 派发路径（v2.9.78 起）：OS4 launcher 主进程不派发 legacy loadPackage（装机实锤），
 * Modern API（java_init.list → ModernEntry）才进得去 —— ModernEntry hook 框架类
 * Instrumentation 后回调本类的 probeActivity。
 *
 * 本探针回答三个架构问题：
 *   1. 桌面 Activity 壳是什么类（rustruntime 提供的 shell 类名）；
 *   2. Flutter 渲染面是什么（FlutterView / FlutterSurfaceView / TextureView），
 *      挂在 DecorView 树的哪个位置；
 *   3. 宿主 classloader 里有哪些可用类（io.flutter.* / 宿主旧类是否真的消失）。
 *
 * 注意：本类同时被 legacy（未用）与 modern 两条路径触达，日志一律直写 logcat
 * （tag HomeMode4Miui），不经过 ModLog —— modern 模块禁止访问 legacy API 包，
 * ModLog 的 XposedBridge 引用在 modern classloader 下不可靠。
 */
public final class Os4Probe {

    private static final String TAG = "HomeMode4Miui";
    /** OS4 家族 versionCode 下界（RELEASE-8.01.02.7748 = 801027748） */
    private static final long OS4_MIN_VERSION_CODE = 800_000_000L;

    /** 只 dump 每个活动类名的第一棵树（Launcher 可能重建，防日志爆炸） */
    private static final Set<String> sDumpedActivities = new HashSet<>();
    private static volatile boolean sClassLoaderProbed = false;

    private Os4Probe() {
    }

    /** 供 ModernEntry 在 Activity 创建后调用；内部做 OS4 家族门禁 */
    public static void probeActivity(Activity act) {
        if (act == null) return;
        final long vc;
        try {
            vc = act.getPackageManager()
                    .getPackageInfo(act.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Throwable t) {
            Log.w(TAG, "Os4Probe: versionCode read failed", t);
            return;
        }
        if (vc < OS4_MIN_VERSION_CODE) {
            Log.i(TAG, "Os4Probe: not OS4 family (vc=" + vc + "), skip");
            return;
        }

        final String cls = act.getClass().getName();
        Log.i(TAG, "Os4Probe: activity created: " + cls + " (vc=" + vc + ")");
        probeClassLoader(act);
        if (!sDumpedActivities.add(cls)) {
            return; // 每个活动类只 dump 第一棵树
        }
        final Window window = act.getWindow();
        if (window == null || window.getDecorView() == null) {
            Log.i(TAG, "Os4Probe: no window/decor for " + cls);
            return;
        }
        final View decor = window.getDecorView();
        // Flutter 首帧渲染在 onCreate 之后，延迟遍历确保视图已挂载
        decor.postDelayed(() -> {
            try {
                Log.i(TAG, "Os4Probe: ==== view tree of " + cls + " ====");
                dumpTree(decor, act, 0);
                Log.i(TAG, "Os4Probe: ==== end of view tree ====");
            } catch (Throwable t) {
                Log.e(TAG, "Os4Probe: tree dump failed", t);
            }
        }, 2000L);
    }

    /** classloader 类探测（不初始化）：宿主旧类 / Flutter 嵌入层可用性 */
    private static void probeClassLoader(Activity act) {
        if (sClassLoaderProbed) return;
        sClassLoaderProbed = true;
        final ClassLoader cl = act.getClassLoader();
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
                Log.i(TAG, "Os4Probe: classloader HAS " + name);
            } catch (Throwable notFound) {
                Log.i(TAG, "Os4Probe: classloader MISSING " + name);
            }
        }
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
        Log.i(TAG, "Os4Probe: " + sb);

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpTree(group.getChildAt(i), act, depth + 1);
            }
        }
    }
}
