package com.homemode4miui.hook;

import android.content.Context;
import android.content.pm.PackageInfo;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 宿主版本兼容层 —— OS2（5.x）/ OS3（6.x）桌面双版本适配的路由中枢。
 *
 * 版本判据（逆向实证）：
 *   - OS3 宿主：RELEASE-6.01.03.1927-12221511，versionCode 601031927（≥ 6_0000_0000）
 *   - OS2 宿主：RELEASE-5.39.40.10843，versionCode 53940xxxx（< 6_0000_0000）
 *   规则：versionCode ≥ 600_000_000 → OS3 家族；否则 OS2 家族。
 *   versionCode 读取失败时用 versionName 兜底（"RELEASE-6." 前缀）。
 *
 * 关键签名漂移（OS3 vs OS2，androguard 全量比对结论）：
 *   - Launcher 大重构：com.miui.home.launcher.Launcher 在 OS3 只剩
 *     onCreate / injectMembers / injectAfterOnCreate，模块关心的
 *     setupViews / onResume / onNewIntent / onLauncherModeChanged /
 *     onMinusOneScreenViewScroll 全部【上移到父类 com.miui.home.launcher.BaseLauncher】；
 *   - LauncherModeController / SuperDraglayer.findActiveController /
 *     MinusOneScreenView.openMinusOne / DesktopModePreference 锚点两版本均在；
 *   - LauncherMode 搜索条语义改为 getSupportSearchBarType(int)，
 *     isHomeSupportIconSearchBar 从 (Context) 变无参。
 *
 * 类解析策略：launcherHookClass() 从 Launcher 沿父类链向上找【实际声明目标方法的类】——
 *   OS2 返回 Launcher 自身，OS3 返回 BaseLauncher；未来再上移也自动跟随。
 *   版本家族只用于日志标注与解析起点优化，不承担正确性（正确性由声明类解析保证）。
 */
public final class HostCompat {

    /** 大版本家族：UNKNOWN 时不猜家族，hook 走版本无关解析 */
    public static final int FAMILY_UNKNOWN = 0;
    public static final int FAMILY_OS2 = 2;
    public static final int FAMILY_OS3 = 3;

    private static final String LAUNCHER_CLS = "com.miui.home.launcher.Launcher";
    private static final String BASE_LAUNCHER_CLS = "com.miui.home.launcher.BaseLauncher";
    /** OS3 家族 versionCode 下界（OS2 5.39.40 = 53940xxxx，OS3 6.01.03 = 601031927） */
    private static final long OS3_MIN_VERSION_CODE = 600_000_000L;

    private static final String TAG = "HomeMode4Miui";
    private static volatile int sFamily = FAMILY_UNKNOWN;
    private static volatile boolean sDetected;
    /** 方法名 → 实际声明该方法的宿主类（进程内稳定，缓存避免反复扫描） */
    private static final Map<String, Class<?>> sDeclaringCache = new HashMap<>();

    private HostCompat() {
    }

    /**
     * 大版本判别（handleLoadPackage 尽早调用）。
     *
     * 版本来源三级兜底：
     *   ① ActivityThread.currentApplication() → PackageInfo（app 就绪时，最准）；
     *   ② lpp.appInfo.sourceDir → systemContext PM 的 getPackageArchiveInfo
     *      （【app 未就绪也能读】—— 设备实测 handleLoadPackage 阶段
     *      currentApplication 为 null，此前落到 OS2 兜底会把 OS3 设备误判）；
     *   ③ 全部失败 → FAMILY_UNKNOWN（不猜家族，hook 走版本无关解析）。
     *
     * @param lpp handleLoadPackage 参数（含 appInfo）
     * @param cl  宿主类加载器
     */
    public static void detect(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpp,
            ClassLoader cl) {
        if (sDetected) return;
        sDetected = true;
        long code = -1;
        String name = null;
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl), "currentApplication");
            if (ctx != null) {
                PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                code = pi.getLongVersionCode();
                name = pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        if (code < 0) {
            // app 未就绪：从 APK 文件直接解析版本（systemContext 在此阶段已可用）
            try {
                Object at = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("android.app.ActivityThread", cl),
                        "currentActivityThread");
                Object sysCtx = at == null ? null
                        : XposedHelpers.callMethod(at, "getSystemContext");
                String src = lpp != null && lpp.appInfo != null ? lpp.appInfo.sourceDir : null;
                if (sysCtx != null && src != null) {
                    Object pm = XposedHelpers.callMethod(sysCtx, "getPackageManager");
                    PackageInfo pi = (PackageInfo) XposedHelpers.callMethod(pm,
                            "getPackageArchiveInfo", src, 0);
                    if (pi != null) {
                        code = pi.getLongVersionCode();
                        name = pi.versionName;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        applyFamily(code, name);
    }

    /**
     * 家族校正（Application.onCreate 时调用：宿主 app 已就绪，读数最准）。
     * 此前 handleLoadPackage 阶段若判为 UNKNOWN，此处落定；若已有明确家族则不覆盖
     * （两个来源读的是同一个 APK，结果必然一致，避免理论上的竞态抖动）。
     */
    public static void redetect(ClassLoader cl) {
        if (sDetected && sFamily != FAMILY_UNKNOWN) return;
        long code = -1;
        String name = null;
        try {
            Context ctx = (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl), "currentApplication");
            if (ctx != null) {
                PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                code = pi.getLongVersionCode();
                name = pi.versionName;
            }
        } catch (Throwable ignored) {
        }
        if (applyFamily(code, name)) {
            log("host family corrected at Application.onCreate -> " + label());
        }
    }

    /** 应用家族判定；返回是否改变了此前结论 */
    private static boolean applyFamily(long code, String name) {
        int newFamily;
        if (code >= OS3_MIN_VERSION_CODE) {
            newFamily = FAMILY_OS3;
        } else if (code >= 0) {
            newFamily = FAMILY_OS2;
        } else if (name != null && name.contains("RELEASE-6.")) {
            newFamily = FAMILY_OS3; // versionCode 不可得时的 versionName 兜底
        } else {
            newFamily = FAMILY_UNKNOWN; // 不猜家族：hook 走版本无关解析，不误标 OS2
        }
        boolean changed = sFamily != newFamily;
        sFamily = newFamily;
        log("host family = " + label() + " (versionName=" + name + ", versionCode=" + code + ")");
        return changed && sDetected;
    }

    /** 宿主版本家族（detect 之前调用返回 UNKNOWN） */
    public static int family() {
        return sFamily;
    }

    public static boolean isOS3() {
        return sFamily == FAMILY_OS3;
    }

    /** 日志用版本标签："OS2(5.x)" / "OS3(6.x)" / "UNKNOWN" */
    public static String label() {
        switch (sFamily) {
            case FAMILY_OS3: return "OS3(6.x)";
            case FAMILY_OS2: return "OS2(5.x)";
            default: return "UNKNOWN";
        }
    }

    /**
     * 解析 Launcher hook 目标类 —— 【按桌面大版本区分】：
     *   OS3 → 从 BaseLauncher 起查（OS3 的方法声明类）；
     *   OS2 / UNKNOWN → 从 Launcher 起查（OS2 的方法声明类；UNKNOWN 向下兼容 OS2 基准）。
     * 起点未命中时沿父类链向上找【声明了目标方法的类】兜底（应对未来继续上移）。
     *
     * 为什么不用固定类名：OS3 的 Launcher 只剩 onCreate/injectMembers，
     * setupViews 等全部在 BaseLauncher——固定 hook Launcher 会在 OS3 上静默失配。
     * 为什么不固定 BaseLauncher：OS2 的方法声明在 Launcher 上，且未来版本可能继续上移。
     * Xposed 的 findAndHookMethod 只查目标类自身声明的方法，必须给"声明类"。
     *
     * @param method  目标方法名（setupViews / onResume / onNewIntent / ...）
     * @param params  方法参数类型（与 findAndHookMethod 一致；无参传空）
     * @return 声明该方法的宿主类（运行期解析到宿主真实 Class）
     * @throws NoSuchMethodError 父类链上找不到（由 tryHook 降级，不影响其余 hook）
     */
    public static synchronized Class<?> launcherHookClass(ClassLoader cl,
            String method, Class<?>... params) {
        Class<?> cached = sDeclaringCache.get(method);
        if (cached != null) return cached;

        // 大版本区分：起点类按家族选择（声明类解析结果与家族无关，家族仅影响起点优化）
        Class<?> c = isOS3()
                ? XposedHelpers.findClass(BASE_LAUNCHER_CLS, cl)
                : XposedHelpers.findClass(LAUNCHER_CLS, cl);
        while (c != null && c != Object.class) {
            if (declares(c, method, params)) {
                sDeclaringCache.put(method, c);
                log("launcherHookClass[" + method + "] -> " + c.getName()
                        + " (start=" + (isOS3() ? "BaseLauncher/OS3" : "Launcher/OS2")
                        + ", host " + label() + ")");
                return c;
            }
            c = c.getSuperclass();
        }
        throw new NoSuchMethodError(
                "no host class declares Launcher." + method
                        + " along superclass chain (host " + label() + ")");
    }

    /**
     * 惰性声明检查：getDeclaredMethod(name, params) 按名+签名【单方法】精确匹配。
     *
     * 【严禁】改用 getDeclaredMethods() 全量扫描 —— 它会急切解析宿主类所有方法的
     * 参数/返回类型，而宿主类链上存在引用"类路径缺失类"的方法（OS2 5.39.40 实锤：
     * Launcher 父类链引用 com.miui.newhome.view.gestureview.NewHomeView，看点/内容
     * 中心为独立包，宿主类路径上可能不存在）→ NoClassDefFoundError，全部 Launcher
     * hook 连带阵亡。老代码 findAndHookMethod 内部走 getDeclaredMethod 惰性查找，
     * 从不触发此雷 —— 本方法必须保持同一语义。
     *
     * 精确签名匹配与 findAndHookMethod/findMethodExact 同语义（它内部也是
     * getDeclaredMethod 精确匹配，参数类型必须完全一致）。
     */
    private static boolean declares(Class<?> c, String method, Class<?>[] params) {
        try {
            c.getDeclaredMethod(method, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
