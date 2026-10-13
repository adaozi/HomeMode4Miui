/*
 * Widget 管理控制器（v2.9.44-refactor2 从 GraceHomeViewModel 抽离）。
 *
 * 职责：AppWidgetHost 生命周期 + widget 增删全链路（allocate → bind → 落库 →
 * 配置页 fire-and-forget）。写库结果经 onHomeLayoutSaved 回调给 VM 更新 uiState，
 * setup 结果经 onSetupResult 回调（VM 转发 widgetSetupResults SharedFlow → Route Toast）。
 * 对齐原版 WidgetSetupActivity 语义：home 分支落 homeLayout，popupOwner 分支落 app_popups。
 */
package com.homemode4miui.grace

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.galaxyrio.gracelauncher.data.GraceSettingsRepository
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherItemsRepository
import com.galaxyrio.gracelauncher.data.GraceHomeSettings
import com.galaxyrio.gracelauncher.data.PopupItem
import com.galaxyrio.gracelauncher.platform.HomeWidgetHost
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WidgetController(
    application: android.app.Application,
    private val scope: CoroutineScope,
    private val settingsRepository: GraceSettingsRepository,
    private val onSetupResult: (Boolean) -> Unit,
    private val onHomeLayoutSaved: (GraceHomeSettings) -> Unit,
) {
    private val host: HomeWidgetHost? = runCatching { HomeWidgetHost(application) }.getOrNull()
    private val appContext: Context = application.applicationContext

    /**
     * 添加桌面 widget（原版 WidgetSetupActivity home 分支同构）：BIND_APPWIDGET 直接
     * bind（免系统确认对话框）→ 写 Room homeLayout → 有配置页则 NEW_TASK 启动
     * （fire-and-forget：配置返回前 widget 已落库生效）。全链路 runCatching，
     * 失败回收 allocated id 并发 false（UI Toast），对齐原版 cancelSetup。
     */
    fun addHome(info: AppWidgetProviderInfo) {
        val host = host ?: run { onSetupResult(false); return }
        scope.launch(Dispatchers.Main) {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val (id, label) = bindWidgetOrCleanup(info) ?: return@runCatching false
                    settingsRepository.mutateSettings { current -> current.copy(
                        homeLayout = current.homeLayout.copy(
                            widgetId = id, widgetProvider = info.provider.flattenToString(),
                            widgetLabel = label, widgetHeightDp = 0,
                        )) }.let { saved -> onHomeLayoutSaved(saved) }
                    launchConfigureIfAny(id, "GraceHomeViewModel configure activity launch failed")
                    true
                }.getOrElse { error ->
                    ModLog.e("GraceHomeViewModel addHomeWidget failed", error); false
                }
            }
            onSetupResult(success)
        }
    }

    /** 移除桌面 widget：先删 host id（provider 收到删除通知）再清 homeLayout。 */
    fun removeHome(homeLayout: HomeLayout) {
        val host = host ?: return
        if (!homeLayout.hasWidget) return
        val id = homeLayout.widgetId
        scope.launch(Dispatchers.IO) {
            runCatching { host.deleteAppWidgetId(id) }
                .onFailure { ModLog.e("GraceHomeViewModel deleteAppWidgetId($id) failed", it) }
            val saved = settingsRepository.mutateSettings { it.copy(homeLayout = it.homeLayout.withoutWidget()) }
            onHomeLayoutSaved(saved)
        }
    }

    /**
     * 添加弹层 widget（原版 WidgetSetupActivity.finishAdding popupOwner 分支同构）：
     * allocate → bind → PopupItem("widget:<id>", layout) 落 app_popups（ownerKey）。
     * 与 addHome 差异仅在写库目标。失败回收 id 并发 false（Route Toast）。
     */
    fun addPopupTo(ownerKey: String, info: AppWidgetProviderInfo) {
        val host = host ?: run { onSetupResult(false); return }
        scope.launch(Dispatchers.Main) {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    val (id, label) = bindWidgetOrCleanup(info) ?: return@runCatching false
                    val layout = HomeLayout(
                        widgetId = id, widgetProvider = info.provider.flattenToString(),
                        widgetLabel = label, widgetHeightDp = 0,
                    )
                    val items = LauncherItemsRepository(LauncherDatabase.getInstance(appContext))
                    items.updatePopup(ownerKey, emptyList()) { entries ->
                        if (entries.any { it.widget?.widgetId == id }) entries else entries + PopupItem("widget:$id", layout)
                    }
                    launchConfigureIfAny(id, "GraceHomeViewModel popup widget configure launch failed")
                    true
                }.getOrElse { error ->
                    ModLog.e("GraceHomeViewModel addPopupWidgetTo failed", error); false
                }
            }
            onSetupResult(success)
        }
    }

    /** bind 公共段：allocate → bind（home category）→ 失败回收 → label。
     *  返回 (id, label)，bind 失败返回 null（id 已回收）。 */
    private fun bindWidgetOrCleanup(info: AppWidgetProviderInfo): Pair<Int, String>? {
        val manager = AppWidgetManager.getInstance(appContext)
        val host = host ?: return null
        val id = host.allocateAppWidgetId()
        val bound = runCatching {
            manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY,
                    AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN)
            })
        }.getOrDefault(false)
        if (!bound) {
            runCatching { host.deleteAppWidgetId(id) }
                .onFailure { ModLog.e("GraceHomeViewModel cleanup unbound widget id failed", it) }
            return null
        }
        val label = runCatching { info.loadLabel(appContext.packageManager) }
            .getOrDefault(info.provider.className)
        return id to label
    }

    /** 配置页 fire-and-forget（fresh 信息校验防 provider 在 bind 后被移除）。 */
    private fun launchConfigureIfAny(id: Int, logTag: String) {
        val manager = AppWidgetManager.getInstance(appContext)
        val fresh = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
        if (fresh?.configure != null) {
            runCatching {
                appContext.startActivity(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .setClassName(fresh.configure.packageName, fresh.configure.className)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { ModLog.e(logTag, it) }
        }
    }
}
