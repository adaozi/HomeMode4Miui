package com.homemode4miui.home;

import android.app.Activity;
import android.view.Gravity;
import android.widget.TextView;

/**
 * 占位根视图：仅用于验证底座链路（模式注册 → 裁决 → 原生表面隐藏 → 挂载）通畅。
 *
 * 桌面切到自定义模式后应看到：原生表面全部隐藏、屏幕中央一行提示文字。
 * 移植真实桌面 UI 时删除本类，并把 {@link HomeViewFactory#create} 改指你的实现。
 */
public class PlaceholderHomeView extends HomeView {

    public PlaceholderHomeView(Activity launcher) {
        super(launcher);

        TextView tip = new TextView(launcher);
        tip.setText("自定义模式底座已挂载\n\nHomeViewFactory.create() → 换成你的桌面 UI");
        // 占位提示文字，不属于正式 UI；正式实现请遵循语义色层（壁纸适配对比度）
        tip.setTextColor(0xFFCCCCCC);
        tip.setTextSize(16);
        tip.setGravity(Gravity.CENTER);
        tip.setOnClickListener(v -> {
            // 示例：自定义 UI 可通过底座打开负一屏（宿主 MinusOneScreenView 同款入口）
            // com.homemode4miui.hook.HomeController.openMinusOneScreen();
        });
        addView(tip, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
    }

    @Override
    public void onHomeAttached(Activity launcher) {
        // 占位无数据可装载；真实实现在此初始化 UI / 加载应用列表
    }
}
