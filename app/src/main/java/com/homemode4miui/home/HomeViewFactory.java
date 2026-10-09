package com.homemode4miui.home;

import android.app.Activity;

/**
 * 根视图工厂 —— 移植新桌面时【唯一必须修改的文件】。
 *
 * 把返回值换成你的 {@link HomeView} 实现即可，其余底座代码零改动：
 *
 * <pre>{@code
 *   public static HomeView create(Activity launcher) {
 *       return new MyLauncherRootView(launcher);   // ← 你的桌面根视图
 *   }
 * }</pre>
 */
public final class HomeViewFactory {

    private HomeViewFactory() {
    }

    /** 每次挂载都会调用（模式切入 / Launcher 重建），无需自己缓存单例 */
    public static HomeView create(Activity launcher) {
        // P0：GraceLauncher Compose 宿主接桩（原占位实现 PlaceholderHomeView 保留备用）
        return new GraceHomeView(launcher);
    }
}
