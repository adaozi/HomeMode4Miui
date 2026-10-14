package com.homemode4miui;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

/**
 * Modern API 入口（META-INF/xposed/java_init.list 派发）—— OS4 桌面的 Java 触达路径。
 *
 * 装机实锤（v2.9.77 探针轮）：7854 fork 对 OS4 launcher 主进程只派发 Modern API
 * 模块（Eta 同款路径），legacy loadPackage 永不进 com.miui.home 主进程 —— 本入口
 * 补上 Modern 派发，与 legacy（assets/xposed_init，OS2/OS3 用）双轨并存。
 *
 * 边界：
 *   - modern 模块禁止访问 legacy API 包（XposedBridge/XposedHelpers），本类与
 *     其触达的 Os4Probe 一律不引用 de.robv.*；
 *   - 版本门禁在 Os4Probe.probeActivity 内做（vc ≥ 800M 才动作），OS2/OS3 上
 *     本入口即使被派发也零行为；
 *   - 日志直写 logcat（tag HomeMode4Miui），导出管线已覆盖。
 */
public class ModernEntry extends XposedModule {

    private static final String TAG = "HomeMode4Miui";
    private static final String HOME_PKG = "com.miui.home";
    private static volatile boolean sHookInstalled = false;

    public ModernEntry() {
        super();
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!HOME_PKG.equals(param.getPackageName())) return;
        if (sHookInstalled) return;
        try {
            final Method onCreated = Instrumentation.class.getDeclaredMethod(
                    "callActivityOnCreate", Activity.class, Bundle.class);
            hook(onCreated).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain)
                        throws Throwable {
                    final Object result = chain.proceed();
                    try {
                        com.homemode4miui.hook.Os4Probe.probeActivity(
                                (Activity) chain.getThisObject());
                    } catch (Throwable t) {
                        Log.e(TAG, "ModernEntry: probe failed", t);
                    }
                    return result;
                }
            });
            sHookInstalled = true;
            Log.i(TAG, "ModernEntry: Instrumentation hook installed"
                    + " (modern dispatch, framework=" + getFrameworkName()
                    + " " + getFrameworkVersionCode() + ")");
        } catch (Throwable t) {
            Log.e(TAG, "ModernEntry: hook install failed", t);
        }
    }
}
