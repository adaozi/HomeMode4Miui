/*
 * 部件域（GraceHomeViewModel 拆分第 5 步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：_widgetProviders / _widgetPickerBusy / _widgetSetupResults / widgetController /
 *        _moveWidgetRequests / _addWidgetRequests / _addPopupWidgetRequests
 *   方法：loadWidgetProviders / addHomeWidget / removeHomeWidget / addPopupWidgetTo /
 *        configureHomeWidget / updateHomeLayoutTopOffset / updateHomeLayoutWidgetHeight /
 *        updateHomeOrder / updatePopupWidget
 *
 * 跨域：homeLayout 的持久化走 Settings 域的 updateSettings（VM 以 lambda 注入）；
 * 弹层 widget 布局回写只落 app_popups 表，不直接写 uiState（由 foldersJob 的
 * Room Flow 回流），因此不构成跨域写入。
 *
 * Route 通过 VM 的同名转发方法访问（Route 零改动），后续若要让 UI 直接用
 * delegate，属于另一次「改 UI」的提交，不与本批搬移混在一起。
 */
package com.homemode4miui.grace

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Process
import com.galaxyrio.gracelauncher.data.GraceHomeSettings
import com.galaxyrio.gracelauncher.data.GraceSettingsRepository
import com.galaxyrio.gracelauncher.data.HomeLayout
import com.galaxyrio.gracelauncher.data.LauncherDatabase
import com.galaxyrio.gracelauncher.data.LauncherItemsRepository
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class WidgetSlot(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
    settingsRepository: GraceSettingsRepository,
    /** 设置写通道（Settings 域）。homeLayout 变更即走这里落 Room + 刷 uiState。 */
    private val updateSettings: ((GraceHomeSettings) -> GraceHomeSettings) -> Unit,
) {
    /** null = 加载中；空列表 = 无可用 provider。宿主进程单 profile（原版多 profile 过滤裁剪）。 */
    private val _widgetProviders = MutableStateFlow<List<AppWidgetProviderInfo>?>(null)
    val widgetProviders = _widgetProviders.asStateFlow()
    private val _widgetPickerBusy = MutableStateFlow(true)
    val widgetPickerBusy = _widgetPickerBusy.asStateFlow()
    /** P4：widget 添加链路结果（false = bind/写库失败，UI 侧 Toast）。 */
    private val _widgetSetupResults = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val widgetSetupResults: SharedFlow<Boolean> = _widgetSetupResults

    /** hotfix2：设置页"移动小部件"命令 → 桌面进入 widget 编辑模式。 */
    private val _moveWidgetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val moveWidgetRequests: SharedFlow<Unit> = _moveWidgetRequests

    /** 上游同步补漏：设置页"添加自定义小组件"命令 → 桌面开 WidgetPicker。 */
    private val _addWidgetRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val addWidgetRequests: SharedFlow<Unit> = _addWidgetRequests

    /** folders 阶段：设置页"添加新的小组件"命令 → 桌面开 WidgetPicker 弹层模式。 */
    private val _addPopupWidgetRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val addPopupWidgetRequests: SharedFlow<String> = _addPopupWidgetRequests

    /** refactor2（v2.9.44）：widget 管理抽 WidgetController（host/落库/配置页全链路）。 */
    private val controller = WidgetController(
        application, scope, settingsRepository,
        onSetupResult = { _widgetSetupResults.tryEmit(it) },
        onHomeLayoutSaved = { saved -> uiState.update { it.copy(settings = saved) } },
    )

    // ---- 设置页命令入口（GraceSettingsHostEngine 回调）----

    fun onMoveWidget() { _moveWidgetRequests.tryEmit(Unit) }
    fun onAddWidget() { _addWidgetRequests.tryEmit(Unit) }
    fun onAddPopupWidget(ownerKey: String) { _addPopupWidgetRequests.tryEmit(ownerKey) }

    /**
     * widget picker 数据加载（进入 picker 时调用）。
     * 原版多 profile/private profile 过滤裁剪：宿主进程单 myUserHandle。
     */
    fun loadProviders() {
        if (_widgetProviders.value != null) { _widgetPickerBusy.value = false; return }
        _widgetPickerBusy.value = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                val manager = AppWidgetManager.getInstance(application)
                manager.getInstalledProvidersForProfile(Process.myUserHandle())
                    .sortedBy { provider ->
                        runCatching { provider.loadLabel(application.packageManager) }
                            .getOrDefault(provider.provider.className).lowercase()
                    }
            }.onFailure { ModLog.e("WidgetSlot loadProviders failed", it) }.getOrNull()
            _widgetProviders.value = result
            _widgetPickerBusy.value = false
        }
    }

    /**
     * 添加 widget：allocate → bindAppWidgetIdIfAllowed（宿主持 BIND_APPWIDGET，
     * 免系统确认对话框）→ 写 Room homeLayout → 有配置页则 NEW_TASK 启动
     * （fire-and-forget：配置返回前 widget 已落库生效）。全链路在 WidgetController 内。
     */
    fun addHome(info: AppWidgetProviderInfo) = controller.addHome(info)

    fun removeHome() = controller.removeHome(uiState.value.settings.homeLayout)

    fun addPopupTo(ownerKey: String, info: AppWidgetProviderInfo) =
        controller.addPopupTo(ownerKey, info)

    /** 原版 WidgetSetupActivity.configureWidget 语义：对现有 widget 启动其配置页。 */
    fun configure() {
        val current = uiState.value.settings.homeLayout
        if (!current.hasWidget) return
        runCatching {
            val manager = AppWidgetManager.getInstance(application)
            val info = manager.getAppWidgetInfo(current.widgetId) ?: return
            if (info.configure == null) return
            application.startActivity(android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setClassName(info.configure.packageName, info.configure.className)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, current.widgetId)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { ModLog.e("WidgetSlot configure failed", it) }
    }

    /** HomeScreen 编辑把手：首页内容区垂直偏移持久化（Room homeLayoutJson）。 */
    fun updateTopOffset(offset: Float) =
        updateSettings { it.copy(homeLayout = it.homeLayout.copy(topOffsetDp = offset)) }

    /** HomeScreen 编辑把手：widget 高度持久化（0 = provider 默认）。 */
    fun updateWidgetHeight(height: Int) =
        updateSettings { it.copy(homeLayout = it.homeLayout.copy(widgetHeightDp = height)) }

    /** 上游同步 1.2.0（S6d）：主页区块拖动排序结果落库。 */
    fun updateHomeOrder(order: List<String>) =
        updateSettings { it.copy(homeLayout = it.homeLayout.copy(homeOrder = order.distinct())) }

    /**
     * 上游同步 1.2.0（S5b）：弹层内 widget 布局（宽度/全宽/底色/缩放/高度）回写。
     * 落 app_popups.itemsJson —— 对应 PopupItem.widget，按 widgetId 定位替换。
     * save=false（拖动中）由 UI 本地 drafts 承担，这里只在落盘时机调用。
     */
    fun updatePopupWidget(ownerKey: String, layout: HomeLayout, save: Boolean) {
        if (!save) return
        scope.launch {
            runCatching {
                val database = LauncherDatabase.getInstance(application)
                LauncherItemsRepository(database)
                    .updatePopup(ownerKey, emptyList()) { previous ->
                        previous.map { item ->
                            if (item.widget?.widgetId == layout.widgetId) item.copy(widget = layout) else item
                        }
                    }
            }.onFailure { ModLog.e("WidgetSlot updatePopupWidget failed", it) }
        }
    }
}
