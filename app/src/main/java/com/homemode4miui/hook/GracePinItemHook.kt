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

import android.net.Uri
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.homemode4miui.util.ModLog
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object GracePinItemHook {
    private const val TAG = "GracePinItemHook"
    /** LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT = 0（APPWIDGET = 1）。 */
    private const val TYPE_SHORTCUT = 0
    private const val TYPE_APPWIDGET = 1

    fun hook(lpp: XC_LoadPackage.LoadPackageParam, cl: ClassLoader) {
        val compat = XposedHelpers.findClass("com.miui.launcher.common.PinItemRequestCompat", cl)
        XposedBridge.hookAllMethods(compat, "accept", object : de.robv.android.xposed.XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    // 带 Bundle 的 accept 是模块自身发起的回调用（appwidget 绑定后通知系统），直接放行防递归
                    if (!param.args.isNullOrEmpty()) return
                    val compat = param.thisObject ?: return
                    val valid = XposedHelpers.callMethod(compat, "isValid") as? Boolean ?: return
                    if (!valid) return
                    val type = XposedHelpers.callMethod(compat, "getRequestType") as? Int ?: return
                    if (type != TYPE_APPWIDGET) return  // SHORTCUT 走 after 转存
                    val context = hostContext() ?: return
                    // 模块 widget 区单槽：已占用时放行 MIUI 原流程（宿主承接，系统状态一致）
                    val settingsRepo = com.galaxyrio.gracelauncher.data.LauncherSettingsRepository(
                        com.galaxyrio.gracelauncher.data.LauncherDatabase.getInstance(context))
                    // toSettings 为 Repository 文件内 private 扩展 —— 直接解码 homeLayoutJson
                    val entity = kotlinx.coroutines.runBlocking {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            com.galaxyrio.gracelauncher.data.LauncherDatabase.getInstance(context)
                                .settingsDao().readSettings()
                        }
                    }
                    val hasWidget = com.galaxyrio.gracelauncher.data.HomeLayout
                        .decode(entity?.homeLayoutJson).hasWidget
                    if (hasWidget) {
                        XposedBridge.log("[$TAG] appwidget pin: home widget slot occupied, letting host handle")
                        return
                    }
                    val info = XposedHelpers.callMethod(compat, "getAppWidgetProviderInfo", context)
                        as? android.appwidget.AppWidgetProviderInfo ?: return
                    // 模块 widget host 自承载：allocate → bind（持 BIND_APPWIDGET，直接成功）→
                    // accept(Bundle) 通知系统该 id 已被采用 → 写模块 homeLayout（上游 addWidget 语义）。
                    // 不启动 configure（上游注释：Pin requests are already configured by the requesting app）。
                    val host = com.galaxyrio.gracelauncher.platform.HomeWidgetHost(context)
                    val id = host.allocateAppWidgetId()
                    val options = android.os.Bundle().apply {
                        putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                            android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
                    }
                    val manager = android.appwidget.AppWidgetManager.getInstance(context)
                    val bound = runCatching {
                        manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, options)
                    }.onFailure { ModLog.e("GracePinItemHook bind failed", it) }.getOrDefault(false)
                    if (!bound) {
                        runCatching { host.deleteAppWidgetId(id) }
                        XposedBridge.log("[$TAG] appwidget bind rejected, letting host handle")
                        return  // 放行 MIUI 原流程兜底
                    }
                    runCatching {
                        val bundle = android.os.Bundle().apply {
                            putInt(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        }
                        XposedHelpers.callMethod(compat, "accept", bundle)
                    }
                    val label = runCatching { info.loadLabel(context.packageManager).toString() }
                        .getOrDefault(info.provider.className)
                    runBlocking {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            settingsRepo.mutateSettings { cur -> cur.copy(
                                homeLayout = cur.homeLayout.copy(
                                    widgetId = id, widgetProvider = info.provider.flattenToString(),
                                    widgetLabel = label, widgetHeightDp = 0,
                                )) }
                        }
                    }
                    param.result = true  // 拦截 MIUI 原方法（避免宿主重复 allocate）
                    XposedBridge.log("[$TAG] appwidget pin adopted: id=$id provider=${info.provider.flattenToString()}")
                } catch (t: Throwable) {
                    ModLog.e("GracePinItemHook beforeHookedMethod failed", t)
                }
            }

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
                    ModLog.e("GracePinItemHook afterHookedMethod failed", t)
                }
            }
        })
        XposedBridge.log("[$TAG] hooked PinItemRequestCompat.accept (host ${lpp.packageName})")
    }

    /** 宿主 Application（hidden 类反射；hook 回调线程安全）。 */
    private fun hostContext(): android.content.Context? = runCatching {
        (XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", GracePinItemHook::class.java.classLoader),
            "currentApplication") as? android.content.Context)?.applicationContext
    }.getOrNull()

    /** ShortcutInfo → 模块 saved_shortcuts（showInAppList=true，抽屉主列表显示）。 */
    private fun persist(info: Any) {
        runBlocking {
            withContext(Dispatchers.IO) {
                val context = hostContext() ?: return@withContext
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

}
