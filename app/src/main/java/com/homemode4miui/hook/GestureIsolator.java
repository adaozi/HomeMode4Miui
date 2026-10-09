package com.homemode4miui.hook;

import android.view.MotionEvent;

import com.homemode4miui.MainHook;
import com.homemode4miui.util.ModLog;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 手势隔离闸门 —— "桌面手势互相捣乱"的根修（对齐 Niagara 手势架构）。
 *
 * 逆向结论（RELEASE-5.39.40.10843 / com.miui.home.launcher.SuperDraglayer）：
 *   宿主 DragLayer 的 onInterceptTouchEvent / onTouchEvent 对【每个触摸事件】调用
 *   findActiveController(MotionEvent)，按序评估一批 TouchController，命中者认领
 *   整条手势流（认领后子 View 收 ACTION_CANCEL）：
 *     - FeedSwipeController / FeedOverlaySwipeController  看点/内容中心（NewHomeView 链路）
 *     - AllAppsSwipeController       上滑抽屉
 *     - GlobalSearchSwipeController  下拉全局搜索（isInPullDownToSearchZone 区域判定 → openSearch）
 *     - StatusBarSwipeController     下拉通知栏
 *     - OverlaySwipeController / AssistantOverlaySwipeController / SearchOverlaySwipeController
 *   这些控制器的认领逻辑【不看】LauncherMode.supportFeed/supportSearch/getPullDownGesture——
 *   动作虽被第四模式 override 成 no_action，手势流仍会被中途抢走：
 *   列表滚动/字母条拖拽进行中被打断（子 View 收 CANCEL），空白区触摸又被模块屏障吞掉，
 *   双向冲突即"手势互相捣乱"。
 *
 * 修复原则（Niagara 逆向文档 §5.7"手势局部化：每个手势只属于自己的 View"）：
 *   自定义模式激活时，findActiveController 一律返回 false —— 宿主控制器永不认领，
 *   手势全部归挂载视图体系；模式关闭时完全透明（原生手势链原样运行）。
 *
 * 负一屏（v2.9.10 语义修正）：
 *   宿主 Feed* 控制器链路揭示的是【看点/内容中心】页（NewHomeView，com.miui.newhome），
 *   不是用户要的负一屏（MinusOneScreenView + 智能助理，从左往右滑）——真机实证。
 *   故 Feed* 控制器无条件隔离；右滑开负一屏由挂载视图驱动宿主
 *   MinusOneScreenView.openMinusOne()（宿主页面指示条点击的同款入口）实现，
 *   详见 HomeController.openMinusOneScreen()。
 *
 * 防御边界：
 *   - 宿主浮层打开（AbstractFloatingView.getTopOpenView != null）时放行原生逻辑；
 *   - 回调体内任何异常都降级为"不认领"（触摸链路绝不卡死）；
 *   - 方法缺失/类缺失时本 hook 独立降级，不影响其余 hook（tryHook 体系）。
 */
public final class GestureIsolator {

    private static final String DRAGLAYER_CLS = "com.miui.home.launcher.SuperDraglayer";
    private static final String FLOATING_CLS = "com.miui.home.launcher.AbstractFloatingView";

    private GestureIsolator() {
    }

    public static void inject(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(DRAGLAYER_CLS, cl, "findActiveController",
                MotionEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!MainHook.isCustomModeActive()) return;   // 模式关闭：透明
                            if (hostFloatingViewOpen(param.thisObject)) return; // 宿主浮层在场：放行
                            param.setResult(false); // 闸门落下：无任何宿主控制器认领手势
                        } catch (Throwable t) {
                            // 闸门异常必须放行原方法，触摸链路绝不能因模块卡死
                            ModLog.e("GestureIsolator callback failed (pass-through)", t);
                        }
                    }
                });
        ModLog.i("GestureIsolator: findActiveController gated (custom mode only)");
    }

    /** 宿主浮层探测：getTopOpenView(mActivity) != null 即视为在场；探测失败按无浮层处理 */
    private static boolean hostFloatingViewOpen(Object dragLayer) {
        try {
            Object activity = XposedHelpers.getObjectField(dragLayer, "mActivity");
            if (activity == null) return false;
            Class<?> cls = XposedHelpers.findClass(
                    FLOATING_CLS, dragLayer.getClass().getClassLoader());
            Object top = XposedHelpers.callStaticMethod(cls, "getTopOpenView", activity);
            return top != null;
        } catch (Throwable t) {
            return false; // 自定义模式下宿主浮层不应存在，探测失败偏向隔离生效
        }
    }
}
