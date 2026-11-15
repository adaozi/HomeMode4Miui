/*
 * 天气域（GraceHomeViewModel 拆分第 3 步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：weatherHostEngine（懒加载）/ weather（写 GraceHomeUiState）
 *   方法：refresh / 状态摄入 / dispose
 *
 * 依赖：宿主 Room 的首选城市（preferredLocationId，由 VM 注入 —— 它属于 Settings 域），
 * 以及"天气变了要让设置页快照回带新数据"的通知（onSnapshotInvalidated）。
 *
 * 为什么薄：天气读取（小米天气 provider 白名单）本来就在 GraceWeatherHostEngine 里，
 * 这里只负责把结果落到 uiState 并触发快照回推。
 */
package com.homemode4miui.grace

import android.app.Application
import com.galaxyrio.gracelauncher.data.weather.WeatherState
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class WeatherFeed(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
    /** 首选城市（posID）；null = 列表首位。VM 注入（读宿主 Room 设置行）。 */
    private val preferredLocationId: suspend () -> String?,
    /** 天气变化后通知设置页快照回带（VM 注入 settingsHostEngine.pushSnapshot）。 */
    private val onSnapshotInvalidated: () -> Unit,
) {
    /** 与 VM 原实现同构：懒加载 —— 首次 refresh/dispose 时创建仓储与 ContentObserver。 */
    private val engine: GraceWeatherHostEngine by lazy {
        GraceWeatherHostEngine(application, scope, preferredLocationId, ::apply)
    }

    /** reason 仅用于日志定位（mount/observer/settings/location/manual）。 */
    fun refresh(reason: String) = engine.refresh(reason)

    /**
     * B6.2 天气状态入口：宿主引擎直推（原桥广播路径随天气读取上移废止）。
     * 更新 uiState 后回推设置快照 —— 设置页天气经快照回带，保证地点选择器/
     * 状态行与桌面同源。
     */
    private fun apply(state: WeatherState) {
        if (uiState.value.weather == state) return
        uiState.update { it.copy(weather = state) }
        onSnapshotInvalidated()
    }

    /** VM shutdown 调用：反注册 ContentObserver（VM 每次 attach 新建，dispose 即终态）。 */
    fun dispose() {
        runCatching { engine.dispose() }
            .onFailure { ModLog.e("WeatherFeed dispose failed", it) }
    }
}
