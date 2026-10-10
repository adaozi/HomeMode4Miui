package com.homemode4miui.mode;

import android.content.Context;
import android.view.View;

import com.miui.home.launcher.DragObject;
import com.miui.home.launcher.ShortcutInfo;
import com.miui.home.launcher.allapps.LauncherMode;

import java.util.Collection;

/**
 * 底座模板：真正的第四种桌面模式 —— 与原生 DesktopLauncherMode / DrawerLauncherMode /
 * ElderlyManLauncherMode 平级，原生同款开发方式：
 *
 *  - 继承宿主抽象类 LauncherMode（运行期通过 LSPosed 父加载器解析到宿主真实类）；
 *  - 由 MainHook 注册进 LauncherModeController.getAllModes()；
 *  - 由 MainHook 在 loadCurrentLauncherMode 中作为普通分支参与模式裁决；
 *  - 持久化复用宿主 SP 文件 "launcher_sharedpreference"（key: is_custom_mode_enable）；
 *  - 切换走原生链路 onLauncherModeChanged() → Launcher.onLauncherModeChanged() → EventBus。
 *
 * 布局数据库隔离是"免费"的：继承的 getLauncherDatabaseDir() = ctx.getDatabasePath(modeName)，
 * 宿主 DeviceConfig.removeInvalidateDatabase / 备份 / 工作区命名全部按 getModeName() 拼路径，
 * 即 databases/custom/launcher.db —— 与原生三模式完全同机制。
 *
 * 下面的 override 是"自定义 UI 全面接管桌面"的语义模板（源自 Niagara 移植的实证结论）：
 * 让原生搜索条/负一屏内容链/拖拽/上下滑手势全部让位给挂载的 HomeView。
 * 移植新桌面时可按需调整，但改动前建议先在真机上验证宿主行为。
 */
public final class CustomLauncherMode extends LauncherMode {

    /** 模式名 —— 同时决定独立数据库目录 databases/custom/ 与备份后缀 .custom */
    public static final String MODE_NAME = "custom";

    private CustomLauncherMode() {
    }

    /** 惰性单例：首次调用才触发宿主 LauncherMode 的 <clinit>（此时宿主已完全初始化） */
    private static final class Holder {
        static final CustomLauncherMode INSTANCE = new CustomLauncherMode();
    }

    public static CustomLauncherMode getInstance() {
        return Holder.INSTANCE;
    }

    @Override
    public String getModeName() {
        return MODE_NAME;
    }

    /** 自定义 UI 无网格编辑概念，锁定网格禁用整理 */
    @Override
    public boolean isScreenCellsLocked() {
        return true;
    }

    @Override
    public boolean isAutoFillEmptyEnable() {
        return false;
    }

    /** 桌面搜索条由挂载的自定义 UI 取代 */
    @Override
    public boolean isHomeSupportSearchBar(Context context) {
        return false;
    }

    @Override
    public boolean isHomeSupportIconSearchBar(Context context) {
        return false;
    }

    /**
     * OS3（RELEASE-6.01.03）签名漂移适配：宿主把 isHomeSupportIconSearchBar
     * 从 (Context) 改为无参。OS2 上本方法不存在于宿主、永不调用；OS3 上由本
     * 覆写生效 —— 图标搜索条仍由挂载的自定义 UI 取代。
     */
    @Override
    public boolean isHomeSupportIconSearchBar() {
        return false;
    }

    /** 自定义视图自行决定搜索入口（不承载全局搜索/小爱入口） */
    @Override
    public boolean supportSearch() {
        return false;
    }

    /**
     * 看点/内容中心开关（真机实证语义）：
     *   宿主 supportFeed 链路（FeedLauncherCallbacks.createClientOptions / addHomeFeedContainer /
     *   addOrRemoveNewHome）承载的是【看点/内容中心】页（NewHomeView，com.miui.newhome）——
     *   它不是负一屏。保持 false：看点链路整体关闭。
     *   负一屏（MinusOneScreenView + 智能助理，从左往右滑）与该开关无关，
     *   由 GestureIsolator 隔离 + HomeController.openMinusOneScreen() 实现。
     */
    @Override
    public boolean supportFeed() {
        return false;
    }

    @Override
    public boolean supportAssistant() {
        return false;
    }

    @Override
    public boolean isPersonalAssistantOn(Context context) {
        return false;
    }

    /** 自定义视图不接收拖拽 */
    @Override
    public boolean canAddToWorkspace(Collection collection, ShortcutInfo shortcutInfo) {
        return false;
    }

    @Override
    public boolean canAddToFolder(Collection collection, ShortcutInfo shortcutInfo) {
        return false;
    }

    /**
     * OS3 签名适配：宿主 canAddToFolder 第二参数由 ShortcutInfo 改为
     * IShortcutInfo（RELEASE-6.01.03）。OS2 上本方法不存在于宿主、永不调用。
     */
    @Override
    public boolean canAddToFolder(Collection collection, com.miui.home.data.IShortcutInfo shortcutInfo) {
        return false;
    }

    @Override
    public boolean canShowShortcutMenu(DragObject dragObject) {
        return false;
    }

    /**
     * OS3 签名适配：宿主 canShowShortcutMenu 参数由 DragObject 改为
     * IDragObject（RELEASE-6.01.03）。OS2 上本方法不存在于宿主、永不调用。
     */
    @Override
    public boolean canShowShortcutMenu(com.miui.home.dragdrop.IDragObject dragObject) {
        return false;
    }

    /** 手势让给挂载视图：下滑/上滑均不接原生动作 */
    @Override
    public boolean defaultPullDownIsNotification() {
        return false;
    }

    @Override
    public String getPullDownGesture(Context context) {
        return "no_action";
    }

    @Override
    public String getSlideUpGesture(Context context) {
        return "no_action";
    }

    /** 视图内图标位置由自定义布局决定 */
    @Override
    public boolean isIconPositionFixed(View view) {
        return true;
    }
}
