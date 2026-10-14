package com.homemode4miui.os4;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.homemode4miui.hook.Os4Probe;

/**
 * OS4 自举 Java 入口（native → JNI 加载，不经任何容器 Java 派发）：
 *
 * native 侧（java_bridge.cpp）用 PathClassLoader 加载本类并调用 init ——
 * 此时我们已运行在 OS4 launcher 进程的 Java 世界里。
 *
 * 首里程碑（本批）：注册 ActivityLifecycleCallbacks，捕获桌面壳 Activity，
 *   ① 触发 Os4Probe 结构探针（view tree / classloader）；
 *   ② 在窗口 content 层顶部挂一个非触摸拦截的标记 View —— 证明「模块 UI 能
 *      画在 OS4 桌面上」。标记不拦截触摸，桌面功能不受影响。
 *
 * 约束：本类经独立 PathClassLoader 加载，只依赖框架 API 与本 APK 内的类，
 * 禁止引用 legacy Xposed API（de.robv.*）。
 */
public final class Os4Bootstrap {

    private static final String TAG = "HomeMode4Miui";
    /** OS4 家族 versionCode 下界（RELEASE-8.01.02.7748 = 801027748） */
    private static final long OS4_MIN_VERSION_CODE = 800_000_000L;

    private Os4Bootstrap() {
    }

    /** native 侧唯一调用点（JNI，签名勿改） */
    @SuppressWarnings("unused")
    public static void init(Context appContext, String apkPath) {
        Log.i(TAG, "Os4Bootstrap: alive, app=" + appContext.getPackageName()
                + " apk=" + apkPath);
        final long vc;
        try {
            vc = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (Throwable t) {
            Log.w(TAG, "Os4Bootstrap: versionCode read failed", t);
            return;
        }
        if (vc < OS4_MIN_VERSION_CODE) {
            Log.i(TAG, "Os4Bootstrap: not OS4 family (vc=" + vc + "), skip");
            return;
        }
        if (!(appContext instanceof Application)) {
            Log.w(TAG, "Os4Bootstrap: context is not Application, skip");
            return;
        }
        ((Application) appContext).registerActivityLifecycleCallbacks(
                new Application.ActivityLifecycleCallbacks() {
                    private boolean mMounded = false;

                    @Override
                    public void onActivityResumed(Activity activity) {
                        try {
                            Os4Probe.probeActivity(activity);
                        } catch (Throwable t) {
                            Log.e(TAG, "Os4Bootstrap: probe failed", t);
                        }
                        if (mMounded) return;
                        try {
                            mountMarker(activity);
                            mMounded = true;
                        } catch (Throwable t) {
                            Log.e(TAG, "Os4Bootstrap: marker mount failed", t);
                        }
                    }

                    // 其余生命周期回调空实现（lambda 化会引入接口适配器查找，
                    // 显式空实现更直白）
                    @Override public void onActivityCreated(Activity a, Bundle b) { }
                    @Override public void onActivityStarted(Activity a) { }
                    @Override public void onActivityPaused(Activity a) { }
                    @Override public void onActivityStopped(Activity a) { }
                    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
                    @Override public void onActivityDestroyed(Activity a) { }
                });
        Log.i(TAG, "Os4Bootstrap: lifecycle callbacks registered");
    }

    /** 里程碑标记：content 层顶部非触摸标记，证明模块 UI 可画在 OS4 桌面上 */
    private static void mountMarker(Activity activity) {
        final TextView marker = new TextView(activity);
        marker.setText("HomeMode4Miui · OS4 bridge alive");
        marker.setTextSize(11f);
        marker.setGravity(Gravity.CENTER);
        marker.setBackgroundColor(0x66000000);
        marker.setTextColor(0xFFFFFFFF);
        marker.setClickable(false);
        marker.setFocusable(false);
        // 不拦截触摸/焦点：桌面手势与点击全部穿透到原桌面
        marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (int) (24 * activity.getResources().getDisplayMetrics().density),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        activity.getWindow().addContentView(marker, lp);
        Log.i(TAG, "Os4Bootstrap: marker mounted into " + activity.getClass().getName());
    }
}
