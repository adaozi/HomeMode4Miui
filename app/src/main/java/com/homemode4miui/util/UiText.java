package com.homemode4miui.util;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * 文本控件视觉修正（v2.16.4）：
 *
 * 背景（真机实证）：MIUI 桌面主题给 TextView 系默认挂了文字阴影
 * （宿主资源里可见 icon_title_text_shadow / folder_icon_title_text_shadow /
 * config_enable_icon_shadow 一族），我们全部 UI 用宿主 Context 创建，
 * TextView/TextClock/Button/EditText 继承这套默认样式——文字背后带一圈
 * glyph 状暗影，与原版 Niagara（独立 App、自有主题）观感不符。
 *
 * 修法：样式=数据、零宿主资源（规划 §八.1）——创建后显式
 * setShadowLayer(0,0,0,0) 清零；noShadowTree 供容器级递归清扫
 * （跳过 AppWidgetHostView 子树，provider 的 RemoteViews 样式不容干预）。
 */
public final class UiText {

    private UiText() {}

    /** 清零文字阴影（radius 0 即不绘制）；返回入参便于链式包裹 */
    public static <T extends TextView> T noShadow(T tv) {
        try {
            tv.setShadowLayer(0f, 0f, 0f, 0);
        } catch (Throwable ignored) {
        }
        return tv;
    }

    /**
     * 递归清扫视图树内所有 TextView 的阴影；
     * AppWidgetHostView 子树跳过——部件内容来自第三方 provider，样式不容干预。
     */
    public static void noShadowTree(View root) {
        try {
            if (root == null) return;
            if (root instanceof android.appwidget.AppWidgetHostView) return;
            if (root instanceof TextView) {
                noShadow((TextView) root);
            }
            if (root instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) root;
                for (int i = 0; i < g.getChildCount(); i++) {
                    noShadowTree(g.getChildAt(i));
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
