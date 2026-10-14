/*
 * G1（gadget 部件接入）—— MIUI 内置部件（gadget）反射门面。
 *
 * 需求：小组件适配不是标准安卓小组件，要和 MIUI 桌面可以添加的一样；主题应用部件暂不考虑。
 *
 * 逆向实锤（MiuiHomeOS3 RELEASE-6.01.03.1927 与 OS2 5.39.40.10843 双族签名一致）：
 *   - MIUI 桌面"添加小组件"三类来源：① 标准 AppWidget（模块已有）；② 内置 gadget
 *     （com.miui.home.launcher.gadget，非标准 AppWidget，本类承接）；③ MtzGadget/MaMl
 *     主题部件（按需求本次不接入）。
 *   - 描述构建：GadgetFactory.getNoMtzInfo(id) → GadgetInfo（span/label/icon/preview
 *     资源内聚，getTitle/getIcon/getPreviewImage(Context) 取显示信息；spanX/spanY 为
 *     父类 ItemInfo 的 public int 字段）。
 *   - 实例化：GadgetFactory.createGadget(context, info) → Gadget（内部 setTag(info)），
 *     字节码实锤 id 映射：4/6/7/8 → ClockGadgetDelegate（12h 2x1 / 24h 4x2 / 24h 4x1 /
 *     24h 4x3 四种时钟）；12 → NormalClearButton（一键清理）；21 → CommonGlobalSearch
 *     （全局搜索）；13 → GoogleSearch（GMS 机型）、14 → PowerClearButton
 *     （support_power_clean feature gate）不列入国行集合。
 *   - 生命周期（BaseLauncher.addGadget 同构）：onAdded() → onCreate() → addInScreen →
 *     onResume()；对应 onPause()/onDestroy()。
 * 全链路反射 + runCatching：宿主缺类/缺方法时安全降级（条目不显示 / 渲染占位）。
 */
package com.galaxyrio.gracelauncher.platform

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.homemode4miui.util.ModLog

object MiuiGadgets {

    /** 与国行 MIUI 桌面选择器一致的内置部件 id 集（13/14 见类头注，不列入）。 */
    val GADGET_IDS = intArrayOf(4, 6, 7, 8, 12, 21)

    /** 选择器条目：label/preview 取自宿主资源（GadgetInfo 内聚），随宿主语言走。 */
    class Entry(
        val gadgetId: Int,
        val label: String,
        val spanX: Int,
        val spanY: Int,
        val preview: Drawable?,
        val icon: Drawable?,
    )

    private val gadgetFactoryClass: Class<*>? by lazy {
        runCatching { Class.forName("com.miui.home.launcher.gadget.GadgetFactory") }.getOrNull()
    }
    private val gadgetInfoClass: Class<*>? by lazy {
        runCatching { Class.forName("com.miui.home.launcher.gadget.GadgetInfo") }.getOrNull()
    }

    /** getNoMtzInfo(id) → Entry；宿主不支持该 id 或反射失败时返回 null。 */
    fun loadEntry(context: Context, gadgetId: Int): Entry? {
        val factory = gadgetFactoryClass ?: return null
        val infoClass = gadgetInfoClass ?: return null
        return runCatching {
            val info = factory.getMethod("getNoMtzInfo", Int::class.javaPrimitiveType)
                .invoke(null, gadgetId) ?: return null
            val spanX = infoClass.getField("spanX").getInt(info)
            val spanY = infoClass.getField("spanY").getInt(info)
            val label = infoClass.getMethod("getTitle", Context::class.java)
                .invoke(info, context) as? String ?: "${HomeLayout.GADGET_PREFIX}$gadgetId"
            val preview = infoClass.getMethod("getPreviewImage", Context::class.java)
                .invoke(info, context) as? Drawable
            val icon = infoClass.getMethod("getIcon", Context::class.java)
                .invoke(info, context) as? Drawable
            Entry(gadgetId, label, spanX, spanY, preview, icon)
        }.getOrElse {
            ModLog.e("MiuiGadgets loadEntry($gadgetId) failed", it); null
        }
    }

    fun loadEntries(context: Context): List<Entry> =
        GADGET_IDS.map { loadEntry(context, it) }.filterNotNull()

    /**
     * 实例化内置部件视图（BaseLauncher.addGadget 同构：createGadget → onAdded → onCreate；
     * onResume/onPause/onDestroy 由渲染层驱动，见 HomeWidget）。必须在主线程调用；
     * 返回 null = 宿主不支持该 id（渲染层落"部件不可用"占位）。
     */
    fun createView(context: Context, gadgetId: Int): View? {
        val factory = gadgetFactoryClass ?: return null
        val infoClass = gadgetInfoClass ?: return null
        return runCatching {
            val info = factory.getMethod("getNoMtzInfo", Int::class.javaPrimitiveType)
                .invoke(null, gadgetId) ?: return null
            val gadget = factory.getMethod("createGadget", Context::class.java, infoClass)
                .invoke(null, context, info) ?: return null
            // addGadget 同构生命周期前半段：onAdded → onCreate（ClockGadgetDelegate 注册时钟 receiver 等）
            for (name in arrayOf("onAdded", "onCreate")) {
                runCatching { findLifecycleMethod(gadget.javaClass, name).invoke(gadget) }
                    .onFailure { ModLog.e("MiuiGadgets $name($gadgetId) failed", it) }
            }
            gadget as View
        }.getOrElse {
            ModLog.e("MiuiGadgets createView($gadgetId) failed", it); null
        }
    }

    /** ON_RESUME 驱动（对应宿主 mGadgets 的 onResume：时钟恢复走秒等）。 */
    fun resume(view: View) = invokeLifecycle(view, "onResume")

    /** ON_PAUSE 驱动（对应宿主 workspace 不可见的 onPause）。 */
    fun pause(view: View) = invokeLifecycle(view, "onPause")

    /** 视图弃用（离开组合/被替换）：onPause + onDestroy（ClockGadgetDelegate 注销 receiver）。 */
    fun destroy(view: View) {
        invokeLifecycle(view, "onPause")
        invokeLifecycle(view, "onDestroy")
    }

    private fun findLifecycleMethod(cls: Class<*>, name: String) =
        generateSequence(cls as Class<*>?) { it.superclass }
            .mapNotNull { c -> runCatching { c.getDeclaredMethod(name) }.getOrNull() }
            .firstOrNull() ?: throw NoSuchMethodException(name)

    private fun invokeLifecycle(view: View, name: String) {
        runCatching { findLifecycleMethod(view.javaClass, name).invoke(view) }
            .onFailure { ModLog.e("MiuiGadgets $name failed", it) }
    }
}
