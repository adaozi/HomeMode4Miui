package com.homemode4miui.hook;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.homemode4miui.ModePrefs;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 【实验性】把自定义模式作为第四个选项注入原生"桌面模式"设置卡片
 * （com.miui.home.settings.preference.DesktopModePreference，miuix VisualCheckGroup 三选一）。
 *
 * 做法：
 *  - onBindViewHolder 后：向 mVisualCheckGroup 追加一个 VisualCheckBox("Custom")；
 *  - onCheckedChanged 前：命中我们追加的索引时拦截 → 写宿主 SP + 走原生切换链；
 *    大于我们索引的原生选项做索引左移重映射（保持 desktop/drawer/jeejen 语义不变）；
 *  - calModeVisualWidth 后：按 childCount 修正每项宽度，避免布局挤压。
 *
 * miuix.visual.check.* 未做静态逆向，全部运行时反射 + 全程 try/catch：
 * 任一步骤失败只影响"设置页第四选项"这一入口，模块 App 广播入口不受影响。
 * 建议实机验证后按需微调（见 README"已知限制"）。
 */
public final class DesktopModeInjector {

    private static final String TAG = "HomeMode4Miui";
    private static final String PREF_CLS =
            "com.miui.home.settings.preference.DesktopModePreference";

    private static volatile Object sInjectedBox;   // 追加的 VisualCheckBox 实例
    private static volatile int sInjectedIndex = -1; // 在 VisualCheckGroup 中的索引

    private DesktopModeInjector() {
    }

    public static void inject(ClassLoader cl) {
        try {
            final Class<?> prefCls = XposedHelpers.findClass(PREF_CLS, cl);

            XposedHelpers.findAndHookMethod(prefCls, "onBindViewHolder",
                    XposedHelpers.findClass("androidx.preference.PreferenceViewHolder", cl),
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            tryInject(param.thisObject);
                        }
                    });

            XposedHelpers.findAndHookMethod(prefCls, "onCheckedChanged",
                    XposedHelpers.findClass("miuix.visual.check.VisualCheckGroup", cl),
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            remapOrIntercept(param);
                        }
                    });

            XposedHelpers.findAndHookMethod(prefCls, "calModeVisualWidth", View.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            widenForFourthOption(param);
                        }
                    });

            log("DesktopModeInjector installed");
        } catch (Throwable t) {
            log("inject failed (设置页入口不可用，模块App入口不受影响): " + t);
        }
    }

    /* ---------------- 实现 ---------------- */

    private static void tryInject(Object preference) {
        try {
            if (sInjectedBox != null) return;
            Object groupObj = XposedHelpers.getObjectField(preference, "mVisualCheckGroup");
            if (!(groupObj instanceof ViewGroup)) return;
            ViewGroup group = (ViewGroup) groupObj;
            if (sInjectedBox instanceof View && ((View) sInjectedBox).getParent() == group) return;

            Object box = createCheckbox(group.getContext());
            if (box == null) return;
            try {
                XposedHelpers.callMethod(box, "setText", "Custom");
            } catch (Throwable ignored) {
            }
            group.addView((View) box);
            sInjectedBox = box;
            sInjectedIndex = group.getChildCount() - 1;
            log("4th option injected at index " + sInjectedIndex);
        } catch (Throwable t) {
            log("tryInject failed: " + t);
        }
    }

    private static Object createCheckbox(Context ctx) {
        try {
            Class<?> cbCls = XposedHelpers.findClass("miuix.visual.check.VisualCheckBox",
                    ctx.getClassLoader());
            for (java.lang.reflect.Constructor<?> c : cbCls.getConstructors()) {
                Class<?>[] ps = c.getParameterTypes();
                if (ps.length == 1 && ps[0] == Context.class) {
                    c.setAccessible(true);
                    return c.newInstance(ctx);
                }
                if (ps.length == 2 && ps[0] == Context.class
                        && android.util.AttributeSet.class.isAssignableFrom(ps[1])) {
                    c.setAccessible(true);
                    return c.newInstance(ctx, null);
                }
            }
        } catch (Throwable t) {
            log("createCheckbox failed: " + t);
        }
        return null;
    }

    private static void remapOrIntercept(XC_MethodHook.MethodHookParam param) {
        try {
            int index = (Integer) param.args[1];
            if (sInjectedIndex < 0 || sInjectedBox == null) return;

            if (index == sInjectedIndex) {
                // 选择自定义模式：拦截原生逻辑，走我们自己的原生链路切换
                Context ctx = ((View) param.thisObject).getContext();
                ModePrefs.setModeEnable(ctx, true);
                try {
                    XposedHelpers.callMethod(sInjectedBox, "setChecked", true);
                } catch (Throwable ignored) {
                }
                param.setResult(null); // 吞掉原生 desktop/drawer/jeejen 分支处理
            } else if (index > sInjectedIndex) {
                // 追加项把原生选项整体右移了一位，重映射回原生索引
                param.args[1] = index - 1;
                // 原生选项被选中即离开自定义模式（幂等）
                Context ctx = ((View) param.thisObject).getContext();
                if (ModePrefs.isModeEnabled(ctx)) {
                    ModePrefs.setModeEnable(ctx, false);
                }
            }
        } catch (Throwable t) {
            log("remapOrIntercept failed: " + t);
        }
    }

    private static void widenForFourthOption(XC_MethodHook.MethodHookParam param) {
        try {
            if (sInjectedIndex < 0 || sInjectedBox == null) return;
            Object pref = param.thisObject;
            Object groupObj = XposedHelpers.getObjectField(pref, "mVisualCheckGroup");
            if (!(groupObj instanceof ViewGroup)) return;
            int childCount = ((ViewGroup) groupObj).getChildCount();
            if (childCount < 4) return; // 折叠屏固定宽度分支不处理

            boolean supportJeejen = XposedHelpers.getStaticBooleanField(
                    XposedHelpers.findClass(PREF_CLS, pref.getClass().getClassLoader()),
                    "mSupportJeejen");
            int divisor = supportJeejen ? 3 : 2; // 原生均分数（逆向确认）
            int original = (Integer) param.getResult();
            param.setResult(original * divisor / (divisor + 1)); // 再均分给第四项
        } catch (Throwable ignored) {
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
