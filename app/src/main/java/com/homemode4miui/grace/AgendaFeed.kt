/*
 * 日程域（GraceHomeViewModel 拆分第 2 步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：calendarRepository / calendarObserver / agendaRefreshJob
 *   状态：events / scheduleStatus（写 GraceHomeUiState）
 *   方法：refreshAgenda / ensureCalendarObserver / scheduleAgendaRefresh / openCalendarApp
 *
 * 为什么先拆它：依赖最少（只读 settings.calendarAgenda，不写任何其他域），
 * 用来验证「delegate + 共享 state flow」这套搬移模式，风险最低。
 *
 * 约定（全仓 delegate 统一）：只写自己域的状态字段；跨域一律走注入的 lambda，
 * 不持有其他 delegate 实例。
 */
package com.homemode4miui.grace

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.galaxyrio.gracelauncher.data.CalendarRepository
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class AgendaFeed(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
) {
    private val calendarRepository = CalendarRepository(application)

    /** CalendarProvider 变更监听（首次真正读数据时注册；dispose 注销）。 */
    private var calendarObserver: android.database.ContentObserver? = null
    private var agendaRefreshJob: Job? = null

    private companion object {
        /** 日历写库防抖：批量导入/删除会连发多次 notifyChange。 */
        const val AGENDA_DEBOUNCE_MS = 800L
    }

    /** B6.2：日程刷新（原版 refreshAgenda 语义：无权限 → PermissionRequired，查询降级 Error）。 */
    fun refresh() {
        val granted = ContextCompat.checkSelfPermission(application, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        if (!uiState.value.settings.calendarAgenda) {
            uiState.update { it.copy(events = emptyList(), scheduleStatus = GraceScheduleStatus.Ready) }
            return
        }
        if (!granted) {
            uiState.update { it.copy(events = emptyList(), scheduleStatus = GraceScheduleStatus.PermissionRequired) }
            return
        }
        // 真正要读数据时挂失效监听（无权限/开关关闭时不注册，省一次 provider 观察）
        ensureObserver()
        scope.launch {
            // 静默刷新：已有数据时不切 Loading（AgendaSheet 只在 events 空时用 Loading 占位，
            // 但日程行本身也读 status —— 未变化的刷新不该让行消失/闪烁）
            if (uiState.value.events.isEmpty()) {
                uiState.update { it.copy(scheduleStatus = GraceScheduleStatus.Loading) }
            }
            runCatching { calendarRepository.refresh(uiState.value.settings.calendar.query)?.events.orEmpty() }
                .onSuccess { events ->
                    uiState.update { it.copy(events = events, scheduleStatus = GraceScheduleStatus.Ready) }
                }
                .onFailure { error ->
                    ModLog.e("AgendaFeed load failed", error)
                    uiState.update { it.copy(events = emptyList(), scheduleStatus = GraceScheduleStatus.Error) }
                }
        }
    }

    /**
     * 日程失效监听（原注释保留：2026-10-28 补，此前 events 只在 VM 挂载 / Grace Button
     * Agenda / 开关打开时各读一次 —— 在系统日历里增删改事件、或第三方日历同步写库后，
     * 桌面那条"下一日程"始终停在旧数据）。
     *
     * CalendarProvider 的 notifyChange 经 ContentService 跨进程派发，宿主进程注册即可收到；
     * 注册 authority 根 URI + descendants 覆盖 Calendars/Events/Instances 全部表，
     * 不漏"只改 calendar 行"的场景（如日历颜色/同步开关）。
     */
    private fun ensureObserver() {
        if (calendarObserver != null) return
        val observer = object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                if (uri != null && uri.authority != android.provider.CalendarContract.AUTHORITY) return
                scheduleRefresh()
            }
        }
        val registered = runCatching {
            application.contentResolver.registerContentObserver(
                android.provider.CalendarContract.CONTENT_URI, true, observer)
        }.onFailure { ModLog.e("AgendaFeed observer register failed", it) }.isSuccess
        if (registered) {
            calendarObserver = observer
            ModLog.i("calendar observer registered (agenda live updates on)")
        }
    }

    /** 日历写库防抖（批量导入/删除连发多次 notifyChange → 合并成一次查询）。 */
    private fun scheduleRefresh() {
        agendaRefreshJob?.cancel()
        agendaRefreshJob = scope.launch {
            delay(AGENDA_DEBOUNCE_MS)
            ModLog.i("calendar changed: refresh")
            refresh()
        }
    }

    /**
     * B6.2 权限降级：宿主 manifest 不可改、无法在宿主进程内弹 READ_CALENDAR 授权
     * （授权对话框必须由声明权限的应用发起）——"Connect calendar" 降级为打开系统
     * 日历应用（用户可见日程；provider 数据待宿主获得权限后自动生效）。
     */
    fun openCalendarApp(): Boolean = runCatching {
        application.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setData(android.provider.CalendarContract.CONTENT_URI)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.onFailure { ModLog.e("AgendaFeed openCalendarApp failed", it) }.isSuccess

    /** VM shutdown 调用（手动构造的 ViewModel 不走 onCleared，dispose 即终态）。 */
    fun dispose() {
        agendaRefreshJob?.cancel()
        agendaRefreshJob = null
        calendarObserver?.let { o ->
            runCatching { application.contentResolver.unregisterContentObserver(o) }
                .onFailure { ModLog.e("AgendaFeed observer unregister failed", it) }
        }
        calendarObserver = null
    }
}
