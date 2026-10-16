/*
 * C 组（C3）模块进程设置快照仓 —— 新建文件（非搬运）。
 *
 * 进程级单例：设置页 Activity 生命周期内动态注册 receiver（RECEIVER_EXPORTED，
 * 快照方向仅驱动设置 UI 显示，信任面评估见 GraceSettingsBridge 头注），收集宿主
 * 回推的设置快照；SettingsViewModel 把它作为 seed 与"宿主写回"通道。
 *
 * 为什么不用 GraceCommandReceiver（manifest 静态注册）：快照只在设置页存活期
 * 有消费方，动态注册随 Activity 注销，避免模块常驻 receiver 处理大 JSON。
 */
package com.homemode4miui.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object GraceSettingsSnapshotStore {

    private val _snapshot = MutableStateFlow<GraceSettingsBridge.Snapshot?>(null)
    val snapshot = _snapshot.asStateFlow()
    // 审计修缮（2026-10-16）：删除 lastKnownSettings —— 只写不读（头注声称的
    // "时钟样式等模块桥引擎消费面" 实际直读宿主 Room，模块进程内无任何消费方）。

    private var registered = false
    private var receiver: BroadcastReceiver? = null

    fun ensureRegistered(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val impl = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent?.action != GraceSettingsBridge.ACTION_SETTINGS_SNAPSHOT) return
                val decoded = runCatching {
                    GraceSettingsBridge.decodeSnapshot(intent.getStringExtra(GraceBridge.EXTRA_JSON))
                }.getOrNull()
                if (decoded == null) {
                    ModLog.i("GraceSettingsSnapshotStore: bad snapshot dropped")
                    return
                }
                ModLog.i("GraceSettingsSnapshotStore: snapshot received (ok=${decoded.ok})")
                _snapshot.value = decoded
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext, impl,
                IntentFilter(GraceSettingsBridge.ACTION_SETTINGS_SNAPSHOT),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = impl
            registered = true
        }.onFailure { ModLog.e("GraceSettingsSnapshotStore: register failed", it) }
    }

    fun unregister(context: Context) {
        val impl = receiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(impl) }
            .onFailure { ModLog.e("GraceSettingsSnapshotStore: unregister failed", it) }
        receiver = null
        registered = false
        // hotfix：同步清残留快照 —— 否则设置页二次打开时 4s 超时判断（value==null）
        // 恒 false，桌面未挂载场景下显示陈旧数据且可编辑（写命令进黑洞无提示）。
        // 对齐 GraceShortcutsRelay.unregister 清 _results 的既有语义。
        _snapshot.value = null
    }

    /** 快照请求（模块 → 宿主）。桌面未挂载时无回包：VM 侧超时置 settingsLoadFailed。 */
    fun requestSnapshot(context: Context) {
        runCatching { context.sendBroadcast(GraceSettingsBridge.requestSnapshot()) }
            .onFailure { ModLog.e("GraceSettingsSnapshotStore: request failed", it) }
    }
}
