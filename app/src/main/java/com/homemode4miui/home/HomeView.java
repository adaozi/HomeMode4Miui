package com.homemode4miui.home;

import android.app.Activity;
import android.widget.FrameLayout;

/**
 * 自定义桌面根视图基类 —— 底座与 UI 的唯一接缝。
 *
 * 分工：
 *   底座负责（hook/ 包 + MainHook，移植任何桌面都零改动）：
 *     · 第四模式注册进 LauncherModeController + 模式裁决（自适应锚点）
 *     · 原生桌面表面隐藏（容器子 View 记账式 GONE）/ 还原
 *     · DragLayer 挂载/卸载本视图、Launcher 生命周期同步（setupViews / onLauncherModeChanged / onResume）
 *     · 手势隔离闸门（SuperDraglayer.findActiveController）
 *     · 负一屏打开/关闭同步（HomeController.openMinusOneScreen）
 *     · 应用增删广播聚合、Home 键回桌面回调、模块 App 广播切换
 *
 *   你的桌面 UI 负责：继承本类实现全部视觉与交互，经 {@link HomeViewFactory} 提供即可。
 *
 * 生命周期（与原生模式视图同机制）：
 *   构造（每次 attach 新建）→ onHomeAttached → [onPackagesChanged / onHomeIntent]*
 *   → onHomeDetached（切回原生模式或 Launcher 重建）。
 */
public abstract class HomeView extends FrameLayout {

    /** 宿主桌面 Activity（Launcher 实例，挂载时由底座传入） */
    protected final Activity mLauncher;

    public HomeView(Activity launcher) {
        super(launcher);
        mLauncher = launcher;
    }

    /** 已挂载到 DragLayer：在此做 UI 初始化与数据装载（应用列表等） */
    public abstract void onHomeAttached(Activity launcher);

    /** 即将卸载：释放资源、反注册回调。切回原生模式或 Launcher 重建时必调 */
    public void onHomeDetached() {
    }

    /** 系统应用增删/变更（ACTION_PACKAGE_ADDED/REMOVED/CHANGED/REPLACED 聚合入口） */
    public void onPackagesChanged() {
    }

    /**
     * Launcher 已退后台（Launcher.onPause，perf3 省电同步）：桌面 UI 在此暂停
     * 常驻刷新（Compose 时钟循环 / widget 宿主监听等）。回前台由 onResume 路径的
     * onHomeAttached 复用分支自动恢复。默认空实现（不支持的 UI 无感）。
     */
    public void onHomePaused() {
    }

    /** Home 键回桌面（Launcher.onNewIntent 且为主意图）：两态 UI 在此复位回首页态 */
    public void onHomeIntent() {
    }
}
