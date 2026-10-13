/*
 * B6.2 天气宿主引擎 —— 新建文件（非搬运）。运行在【宿主进程 com.miui.home】。
 *
 * 根因背景（2026-10-13 装机实锤）：小米天气 WeatherProvider 有调用方白名单
 * （Wth2:ToolUtils hasAccessProvider 日志），com.miui.home = true、
 * com.homemode4miui = false —— 模块 App 进程读 provider 返回 null（原
 * GraceBridgeEngine 桥读方案在小米天气源下不可用）。宿主进程本就被白名单
 * 放行（系统桌面天气部件同一取数路径），因此天气读取整体上移到本进程，
 * 消费面（HomeWeather/AgendaSheet）进程内直推，不再走 ACTION_WEATHER 广播；
 * 设置页（模块 App 进程）经 GraceSettingsBridge.Snapshot.weatherJson 回带。
 *
 * 失效链：ContentObserver(content://weather)（小米天气刷新写库即触发）+
 * 宿主挂载首次装载 + 设置页 WEATHER_REFRESH 命令 + weatherLocationId 变化。
 * 指纹去重与原桥引擎同构（encodeWeatherWire 全量比对）。
 */
package com.homemode4miui.grace

import android.content.Context
import com.galaxyrio.gracelauncher.data.weather.MiuiWeatherRepository
import com.galaxyrio.gracelauncher.data.weather.WeatherState
import com.homemode4miui.bridge.GraceBridge
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class GraceWeatherHostEngine(
    context: Context,
    private val scope: CoroutineScope,
    /** 首选城市（posID）——宿主 Room 设置流（weatherLocationId），装载协程内现取。 */
    private val preferredLocationId: suspend () -> String?,
    /** 状态出口（主线程语义由调用方保证；VM 直接写 uiState）。 */
    private val onState: (WeatherState) -> Unit,
) {
    private val appContext = context.applicationContext
    private var repository: MiuiWeatherRepository? = null
    private var observerRegistered = false
    private var lastFingerprint: String? = null

    /**
     * 重读天气。reason 仅用于日志定位（mount/observer/settings/location）。
     * observer 注册一次（引擎与宿主 VM 同生命周期）；onChange 连发由指纹去重合并。
     */
    fun refresh(reason: String) {
        val repo = repository ?: runCatching { MiuiWeatherRepository(appContext) }
            .onFailure { ModLog.e("GraceWeatherHostEngine repo init failed", it) }
            .getOrNull() ?: return
        repository = repo
        if (!observerRegistered) {
            observerRegistered = true
            runCatching { repo.observeChanges { refresh("observer") } }
                .onFailure { ModLog.e("GraceWeatherHostEngine observer register failed", it) }
        }
        scope.launch {
            runCatching { repo.load(runCatching { preferredLocationId() }.getOrNull()) }
                .onSuccess { state ->
                    // 装机诊断：每次装载结果打点（白名单/数据面问题在这里现形）
                    ModLog.i("GraceWeatherHostEngine load($reason): status=${state.status} " +
                        "locs=${state.locations.size} cur=${state.snapshot?.current != null}")
                    val fingerprint = GraceBridge.encodeWeatherWire(state)
                    if (fingerprint == lastFingerprint) return@launch
                    lastFingerprint = fingerprint
                    onState(state)
                }
                .onFailure { ModLog.e("GraceWeatherHostEngine load($reason) failed", it) }
        }
    }
}
