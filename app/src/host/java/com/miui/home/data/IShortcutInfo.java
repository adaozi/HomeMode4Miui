package com.miui.home.data;

/**
 * [HOST STUB] 宿主接口编译期桩 —— 仅作类型占位，永不在运行期执行。
 *
 * 运行期规则：LSPosed 父优先委派下本桩被宿主真实接口遮蔽。
 *
 * OS3 适配（RELEASE-6.01.03.1927）：LauncherMode.canAddToFolder 的第二参数
 * 由 com.miui.home.launcher.ShortcutInfo 改为 com.miui.home.data.IShortcutInfo；
 * 本桩使 CustomLauncherMode 能在编译期声明 OS3 签名的覆写。
 * OS2 上该签名不存在于宿主，覆写为死方法，运行期无害。
 */
public interface IShortcutInfo {
}
