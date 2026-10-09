/*
 * P4 hotfix1（2026-10-08，装机崩溃循环）—— ComposeView 资源解析桥。
 *
 * 【崩溃根因（LSPosed 日志 2026-10-08 15:55 三连崩，Resources$NotFoundException
 *  String resource ID #0x7f0b006c）】
 *   compose-ui 1.12 的无障碍代理（AndroidComposeViewAccessibilityDelegateCompat
 *   .populateAccessibilityNodeInfoProperties）对任何带 Selected/ToggleableState
 *   semantics 的节点，都会用【AndroidComposeView.getContext().getResources()】
 *   解析库内部 string（R.string.selected / not_selected / state_on / state_off /
 *   indeterminate）。本模块 ComposeView 构造于宿主 Activity（GraceHomeView P0
 *   定案），其 Resources 是宿主 MiuiResources —— 库 string 在合并 APK 里的 id
 *   （0x7f0b006c = not_selected）在宿主表中不存在/错位 → NotFoundException 从
 *   AccessibilityInteractionController（主线程 handler）冒泡 → 进程死亡。
 *   与 P1 hotfix1/hotfix2（LocalContext 覆盖）同族但更深一层：无障碍代理不读
 *   LocalContext，直接取 View context 的 Resources，只能从 View 构造参数解决。
 *   触发面：LauncherRow 的 selected semantics（P1 起）+ MIUI 常驻无障碍服务
 *   prefetch；P1–P3 为潜伏随机触发，P4 装机后稳定复现（熔断机制只覆盖帧阶段
 *   异常，心跳成功后的无障碍线程崩溃不在其列）。
 *
 * 【修复】ComposeView 的 context 换成覆写 getResources() 的 ContextWrapper，
 *   返回 DualResources：所有查询先走宿主表（宿主行为零变化），NotFoundException
 *   时降级到模块 APK 资源表（库 string id 命中）。主题/窗口 token/体系资源全部
 *   保持宿主（ContextWrapper 只覆写 getResources）。
 */
package com.homemode4miui.home

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.util.Log
import com.homemode4miui.util.ModLog

/**
 * 宿主优先、模块兜底的资源查询桥。
 *
 * 构造关键：以【宿主 assets/metrics/configuration】初始化基类 Resources ——
 * 所有未覆写方法（getDrawable/getDimension/getBoolean/getDisplayMetrics/
 * getIdentifier…）原样走宿主 AssetManager，语义与宿主 Resources 完全一致；
 * 仅覆写的 string 族具备"宿主 NotFound → 模块表兜底"能力。
 */
class DualResources(
    private val primary: Resources,
    private val fallback: Resources?,
) : Resources(primary.assets, primary.displayMetrics, primary.configuration) {

    private fun <T> resolve(id: Int, primaryRead: () -> T, fallbackRead: (Resources) -> T): T {
        return try {
            primaryRead()
        } catch (first: Resources.NotFoundException) {
            val fb = fallback ?: throw first
            try {
                fallbackRead(fb)
            } catch (second: Resources.NotFoundException) {
                // 两边都无此 id：抛原始异常，保持栈可诊断
                throw first
            }
        }
    }

    override fun getText(id: Int): CharSequence =
        resolve(id, { primary.getText(id) }, { it.getText(id) })

    override fun getText(id: Int, def: CharSequence): CharSequence =
        resolve(id, { primary.getText(id, def) }, { it.getText(id, def) })

    override fun getString(id: Int): String =
        resolve(id, { primary.getString(id) }, { it.getString(id) })

    override fun getString(id: Int, vararg formatArgs: Any?): String =
        resolve(id, { primary.getString(id, *formatArgs) }, { it.getString(id, *formatArgs) })

    override fun getQuantityText(id: Int, quantity: Int): CharSequence =
        resolve(id, { primary.getQuantityText(id, quantity) }, { it.getQuantityText(id, quantity) })

    override fun getQuantityString(id: Int, quantity: Int): String =
        resolve(id, { primary.getQuantityString(id, quantity) }, { it.getQuantityString(id, quantity) })

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        resolve(id, { primary.getQuantityString(id, quantity, *formatArgs) },
            { it.getQuantityString(id, quantity, *formatArgs) })

    override fun getTextArray(id: Int): Array<out CharSequence> =
        resolve(id, { primary.getTextArray(id) }, { it.getTextArray(id) })

    override fun getStringArray(id: Int): Array<String> =
        resolve(id, { primary.getStringArray(id) }, { it.getStringArray(id) })
}

object ModuleResourceBridge {

    /** 模块 APK 包名（= 本模块 applicationId）。宿主进程经 createPackageContext 读其资源。 */
    private const val MODULE_PACKAGE = "com.homemode4miui"

    /**
     * 包装宿主 context：getResources() 返回 DualResources（宿主优先、模块兜底）。
     * 任何失败（模块包不可达等）静默返回原 context —— 桌面零崩溃铁律。
     */
    fun wrap(host: Context): Context {
        return try {
            val moduleRes = host.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                .resources
            val dual = DualResources(host.resources, moduleRes)
            object : ContextWrapper(host) {
                override fun getResources(): Resources = dual
            }
        } catch (t: Throwable) {
            ModLog.e("ModuleResourceBridge wrap failed, accessibility resource fallback disabled", t)
            host
        }
    }
}
