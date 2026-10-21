package com.homemode4miui.home;

import android.app.Activity;
import android.content.Intent;
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

    /**
     * 清理系统栏 insets 造成的 View 级 padding（blur-gap 专修，由底座 attach/延迟检查调用）。
     *
     * MIUI 的 DecorView/DragLayer 在「原生桌面 ↔ 第四模式」切换后会向子视图 dispatch
     * 一次状态栏 insets，View 默认的 fitSystemWindows 实现会直接 setPadding(top=状态栏高)
     * 并把内容高度减掉同样的值 —— 表现就是 Compose 根矮了 104px、整块桌面下移 104px，
     * 抽屉的模糊壁纸/dim 遮罩盖不住状态栏条带。默认空实现供其他 UI 复用。
     */
    public void clearSystemBarPadding() {
    }

    /**
     * Home 键回桌面（带 Intent 透传，hotfix return-anim）：返回动画需要 HOME intent
     * 里的 GestureNavContract extra（Quickstep 注入的图标回位合约）。null = 无 contract
     * 路径（OS3 onNewIntentWhenAlreadyOnHome / 已在桌面复位）。默认转调无参重载，
     * 旧 UI 实现零感知。
     */
    public void onHomeIntent(Intent intent) {
        onHomeIntent();
    }
}
