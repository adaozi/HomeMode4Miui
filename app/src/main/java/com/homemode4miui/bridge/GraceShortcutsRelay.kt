/*
 * folders 阶段（hotfix folders2）模块进程快捷方式代查中继 —— 新建文件（非搬运）。
 *
 * 背景：快捷方式查询（LauncherApps.getShortcuts / hasShortcutHostPermission）
 * 需要默认桌面（HOME）角色 —— 权限在宿主进程 com.miui.home；模块 App 进程设置页
 * 无权限（C 组降级 DefaultLauncherRequired）。文件夹弹层编辑器的"快捷方式 → 添加"
 * 走 ShortcutPickerScreen，需要把查询跨桥代理给宿主：
 *   - 上行：GraceSettingsBridge.Cmd.SHORTCUTS_QUERY（EXTRA_JSON = [appKey..]，一次批量）；
 *   - 回包：ACTION_SHORTCUTS_RESULT（EXTRA_JSON = {appKey: [ShortcutWire..]}），
 *     宿主 GraceSettingsHostEngine.onShortcutQuery 代查后点对点回发。
 *
 * 生命周期对齐 GraceSettingsSnapshotStore：设置页存活期动态注册（RECEIVER_EXPORTED，
 * 信任面评估同 GraceSettingsBridge 头注 —— 回包仅驱动快捷方式选择列表，伪造最多
 * 污染选择列表显示，落库仍走宿主权威命令）。
 *
 * 降级登记：ShortcutWire 不含图标（ImageBitmap 不可跨 JSON 序列化）→ 选择列表用
 * 默认图标（AppIcon fallback）；label/identity 完整，落库（REMEMBER_SHORTCUT）无损失。
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
import org.json.JSONArray

object GraceShortcutsRelay {

    /** appKey → 该应用可用的快捷方式列表；null = 尚无回包。 */
    private val _results = MutableStateFlow<Map<String, List<GraceSettingsBridge.ShortcutWire>>?>(null)
    val results = _results.asStateFlow()

    private var registered = false
    private var receiver: BroadcastReceiver? = null

    fun ensureRegistered(context: Context) {
        if (registered) return
        val appContext = context.applicationContext
        val impl = object : BroadcastReceiver() {
            override fun onReceive(recvContext: Context?, intent: Intent?) {
                if (intent?.action != GraceSettingsBridge.ACTION_SHORTCUTS_RESULT) return
                val decoded = runCatching {
                    GraceSettingsBridge.decodeShortcutsResult(intent.getStringExtra(GraceBridge.EXTRA_JSON))
                }.getOrNull()
                if (decoded == null) {
                    ModLog.i("GraceShortcutsRelay: bad result dropped")
                    return
                }
                ModLog.i("GraceShortcutsRelay: result received (${decoded.size} apps)")
                _results.value = decoded
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext, impl,
                IntentFilter(GraceSettingsBridge.ACTION_SHORTCUTS_RESULT),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = impl
            registered = true
        }.onFailure { ModLog.e("GraceShortcutsRelay: register failed", it) }
    }

    fun unregister(context: Context) {
        val impl = receiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(impl) }
            .onFailure { ModLog.e("GraceShortcutsRelay: unregister failed", it) }
        receiver = null
        registered = false
        _results.value = null
    }

    /** 清空上一批结果（重新代查前调用；迟到回包不覆盖新查询语义）。 */
    fun reset() { _results.value = null }

    /** 批量代查（模块 → 宿主）。桌面未挂载时无回包：调用方超时降级。 */
    fun request(context: Context, appKeys: List<String>) {
        if (appKeys.isEmpty()) return
        runCatching {
            context.sendBroadcast(
                GraceSettingsBridge.command(GraceSettingsBridge.Cmd.SHORTCUTS_QUERY, json = JSONArray(appKeys).toString())
            )
        }.onFailure { ModLog.e("GraceShortcutsRelay: request failed", it) }
    }
}
