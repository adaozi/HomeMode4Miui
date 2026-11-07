package com.homemode4miui.hook;

import com.homemode4miui.util.ModLog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * PA Widget 删除拦截 —— 负一屏自愈链的最后一环（2026-11-07 定案）。
 *
 * 背景（本文件所述自愈链的姊妹篇，见 MinusOneGate 头注）：第四桌面模式开机时
 * PA 冷启动预载 widget 恢复早于 u0 FBE unlock → CE 区 provider 未注册 →
 * PA restoreWidget bind 被拒 → PA 判死 removeInvalidWidgets —— 其伤害动作是
 * 调 AppWidgetHost.deleteAppWidgetId 删除系统侧 widget id，而第三方 provider
 * （com.miui.notes 等）以 appWidgetId 为键存储内容映射（笔记绑定、卡片配置），
 * id 被删 = 内容映射作废 = 用户必须重新绑定。
 *
 * 修复：在 PA 进程内 hook AppWidgetHost.deleteAppWidgetId(int) 直接 no-op——
 *   系统侧 id 永不丢失 → 内容映射永续 → provider 注册就绪后（MinusOneGate 的
 *   组件 toggle 补全）PA 重跑 loadWidget 时 id 与 provider 双在 → 直接有效渲染，
 *   笔记内容保留，无需重新绑定。
 *
 * 副作用评估：用户在负一屏手动删除小部件时，系统侧 id 残留为孤儿（PA 库 entity
 *   正常删除，界面正确消失）；孤儿 id 由 MIUI 自身的周期清理与 boot 重扫兜底，
 *   无累积危害。拦截全程 ModLog 打点，可审计。
 *
 * 装载：MainHook.handleLoadPackage 对 com.miui.personalassistant 的分支调用
 *   install（LSPosed 作用域需勾选智能助理）。
 */
public final class PaWidgetGuard {

    private static final String TAG = "HomeMode4Miui";

    private PaWidgetGuard() {
    }

    /** MainHook 挂载入口（PA 进程）。 */
    public static void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.appwidget.AppWidgetHost", cl, "deleteAppWidgetId",
                    int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // no-op：吞掉删除请求，保住系统侧 widget id 与内容映射
                            ModLog.i("PaWidgetGuard: deleteAppWidgetId("
                                    + param.args[0] + ") blocked (id preserved)");
                            param.setResult(null);
                        }
                    });
            ModLog.i("PaWidgetGuard: installed (AppWidgetHost.deleteAppWidgetId blocked)");
        } catch (Throwable t) {
            // 独立降级：PA 侧 hook 失败只损失"内容映射永续"，不影响宿主与其余自愈链
            ModLog.e("PaWidgetGuard: install failed", t);
        }
    }
}
