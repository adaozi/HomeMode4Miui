package com.miui.home.launcher.allapps;

import android.content.Context;
import android.view.View;

import com.miui.home.launcher.DragObject;
import com.miui.home.launcher.ShortcutInfo;

import java.util.Collection;

/**
 * [HOST STUB] 宿主类编译期桩 —— 永不在运行期执行。
 *
 * 运行期规则：LSPosed 以"宿主类加载器为父加载器、父优先委派"加载模块类，
 * 因此本桩在宿主进程内会被 com.miui.home 的真实类遮蔽（shadowed），
 * 所有对 LauncherMode 的引用最终解析到宿主真实类。
 *
 * 签名来源：apk_analysis/decompiled/LauncherMode.java（androguard DAD 反编译，逐条核对 smali）。
 * 只声明"被模块继承/覆写/引用"的成员；未声明的方法运行期自然继承宿主实现。
 * cleanup（v2.9.39）：删除全部零引用的"编译期完整性"成员与 modeDatabasePath 字段 ——
 * 每多一个声明就多一份"宿主签名漂移后 stub 与真实类不一致却无人发现"的维护面。
 * 方法体一律 throw —— 用于误执行时的显式失败。
 */
public abstract class LauncherMode {

    // ==== 抽象方法（宿主要求子类必须实现）====

    public abstract String getModeName();

    public abstract boolean supportAssistant();

    public abstract boolean supportFeed();

    public abstract boolean supportSearch();

    // ==== 模块覆写的方法（签名必须与宿主一致）====

    public boolean isScreenCellsLocked() { throw stub(); }

    public boolean isAutoFillEmptyEnable() { throw stub(); }

    public boolean isHomeSupportSearchBar(Context p1) { throw stub(); }

    // OS2 签名（带 Context 参）；OS3（RELEASE-6.01.03）改为无参，见下方重载
    public boolean isHomeSupportIconSearchBar(Context p1) { throw stub(); }

    // OS3 签名（无参）——仅 OS3 宿主会调用；OS2 上为死方法，运行期无害
    public boolean isHomeSupportIconSearchBar() { throw stub(); }

    public boolean isPersonalAssistantOn(Context p2) { throw stub(); }

    public boolean isIconPositionFixed(View p1) { throw stub(); }

    public boolean canAddToWorkspace(Collection p4, ShortcutInfo p5) { throw stub(); }

    public boolean canAddToFolder(Collection p1, ShortcutInfo p2) { throw stub(); }

    public boolean canShowShortcutMenu(DragObject p1) { throw stub(); }

    // ==== OS3 签名重载（RELEASE-6.01.03 参数类型漂移）====
    // 仅 OS3 宿主会调用；OS2 上为死方法，运行期无害。
    // 逆向依据：OS3 LauncherMode.canAddToFolder(Collection, IShortcutInfo) /
    //           canShowShortcutMenu(IDragObject)；canAddToWorkspace 两版本均为 ShortcutInfo。

    public boolean canAddToFolder(Collection p1, com.miui.home.data.IShortcutInfo p2) { throw stub(); }

    public boolean canShowShortcutMenu(com.miui.home.dragdrop.IDragObject p1) { throw stub(); }

    public boolean defaultPullDownIsNotification() { throw stub(); }

    public String getPullDownGesture(Context p2) { throw stub(); }

    public String getSlideUpGesture(Context p2) { throw stub(); }

    private static RuntimeException stub() {
        return new RuntimeException("HOST STUB — never executed at runtime");
    }
}
