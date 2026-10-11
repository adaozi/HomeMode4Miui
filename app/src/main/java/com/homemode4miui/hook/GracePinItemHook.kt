/*
 * 上游同步 v1.1.8 S8b —— pin item 接收链（HomeMode4Miui 专属）。
 *
 * MIUI 原生 pin 链路（逆向实锤，MiuiHomeOS3）：
 *   应用 requestPinShortcut → 系统 ACTION_CONFIRM_PIN_SHORTCUT →
 *   com.miui.home.launcher.AddItemActivity（确认 UI）→
 *   com.miui.launcher.common.PinItemRequestCompat.accept()（反射封装）。
 *
 * 本 hook 在 accept() 成功后（after），把 shortcut 转存进模块 Room saved_shortcuts
 * （showInAppList = true → 抽屉主列表显示）。MIUI 原生确认 UI/流程完全不动
 * （hook 原版桌面角色——只旁路旁听，不改变宿主行为）。
 *
 * 范围：仅 REQUEST_TYPE_SHORTCUT（PWA/应用快捷方式）。REQUEST_TYPE_APPWIDGET
 * （应用生成小组件）accept 后由宿主管理 appWidgetId，模块 widget host 不承载，
 * 登记缓后（PORTING work2 节降级登记①）。
 *
 * 崩溃面：runCatching 全兜底 + hook 回调线程非主线程（binder 线程），落库走
 * runBlocking + Dispatchers.IO，绝不上主线程（桌面零崩溃铁律）。
 */
package com.homemode4miui.hook

import android.content.pm.LauncherApps
import android.net.Uri
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object GracePinItemHook {
    private const val TAG = "GracePinItemHook"
    /** LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT = 0（APPWIDGET = 1）。 */
    private const val TYPE_SHORTCUT = 0

    fun hook(lpp: XC_LoadPackage.LoadPackageParam, cl: ClassLoader) {
        val compat = XposedHelpers.findClass("com.miui.launcher.common.PinItemRequestCompat", cl)
        XposedBridge.hookAllMethods(compat, "accept", object : de.robv.android.xposed.XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val success = (param.result as? Boolean) ?: return
                    if (!success) return
                    val compat = param.thisObject ?: return
                    val valid = XposedHelpers.callMethod(compat, "isValid") as? Boolean ?: return
                    if (!valid) return
                    val type = XposedHelpers.callMethod(compat, "getRequestType") as? Int ?: return
                    if (type != TYPE_SHORTCUT) return  // APPWIDGET 随 P5/P6 widget 链路另做
                    val info = XposedHelpers.callMethod(compat, "getShortcutInfo") ?: return
                    persist(info)
                } catch (t: Throwable) {
                    XposedBridge.log("[$TAG] afterHookedMethod failed: ${t.message}")
                }
            }
        })
        XposedBridge.log("[$TAG] hooked PinItemRequestCompat.accept (host ${lpp.packageName})")
    }

    /** ShortcutInfo → 模块 saved_shortcuts（showInAppList=true，抽屉主列表显示）。 */
    private fun persist(info: Any) {
        runBlocking {
            withContext(Dispatchers.IO) {
                // ActivityThread 为 hidden 类，编译期不可直接引用 —— 反射取宿主 Application
                val context = (XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", GracePinItemHook::class.java.classLoader),
                    "currentApplication") as? android.content.Context)?.applicationContext
                    ?: return@withContext
                val id = XposedHelpers.callMethod(info, "getId") as? String ?: return@withContext
                val pkg = XposedHelpers.callMethod(info, "getPackage") as? String ?: return@withContext
                val label = runCatching {
                    (XposedHelpers.callMethod(info, "getShortLabel") as? CharSequence)?.toString()
                }.getOrNull().orEmpty().ifBlank {
                    (XposedHelpers.callMethod(info, "getLongLabel") as? CharSequence)?.toString().orEmpty()
                }
                val activity = runCatching {
                    (XposedHelpers.callMethod(info, "getActivity") as? android.content.ComponentName)
                        ?.flattenToString().orEmpty()
                }.getOrDefault("")
                val user = runCatching {
                    XposedHelpers.callMethod(info, "getUserHandle") as? android.os.UserHandle
                }.getOrNull()
                val myUser = android.os.Process.myUserHandle()
                val serial = if (user == null || user == myUser) null else runCatching {
                    context.getSystemService(android.os.UserManager::class.java)
                        ?.getSerialNumberForUser(user)
                }.getOrNull()?.takeIf { it >= 0 }
                // key 与本仓 LauncherShortcut 逐字一致：profile:<serial>:shortcut:<pkg>/<id>
                val itemKey = (serial?.let { "profile:$it:" } ?: "") + "shortcut:$pkg/${Uri.encode(id)}"
                val entity = com.galaxyrio.gracelauncher.data.SavedShortcutEntity(
                    itemKey = itemKey,
                    packageName = pkg,
                    shortcutId = id,
                    activity = activity,
                    label = label,
                    showInAppList = true,
                )
                val database = com.galaxyrio.gracelauncher.data.LauncherDatabase.getInstance(context)
                database.itemsDao().saveShortcut(entity)
                XposedBridge.log("[$TAG] pinned shortcut saved: $itemKey ($label)")
            }
        }
    }

    /** LauncherApps 兜底引用（保持 classloader 上 LauncherApps 可达性；供后续 appwidget pin 用）。 */
    @Suppress("unused")
    private fun launcherApps(context: android.content.Context): LauncherApps? =
        context.getSystemService(LauncherApps::class.java)
}
