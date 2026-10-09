/*
 * hotfix9 —— 宿主进程 BackHandler 支撑（新建，非搬运）。
 *
 * 根因（javap 字节码实锤）：activity-compose 1.13.0 的 BackHandler 组合期硬校验，
 * LocalNavigationEventDispatcherOwner 与 LocalOnBackPressedDispatcherOwner 双双缺省
 * 直接 IllegalStateException（"Please provide one of the two"）。宿主进程自托管
 * Compose（非 ComponentActivity）无人 provide —— P2 登记"宿主 BackHandler 不可用"
 * 的真实机制即此。编辑收藏页 ShortcutPickerScreen 是宿主第一个 BackHandler 使用点，
 * 点击"添加快捷方式"（popup_add_new）即组合崩溃 → 桌面重启。
 *
 * 语义：宿主返回 = Home 键复位（P2 定案，returnHomeRequests），不经 back dispatcher。
 * 本 owner 的 dispatcher 无系统回调源，注册其上的 BackHandler 永不触发 —— 仅保证
 * 组合期不炸，行为与"不可用"一致。将来若需真返回键，在此接系统 back 事件。
 */
package com.homemode4miui.grace

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle

internal class HostBackPressedDispatcherOwner(
    hostLifecycle: Lifecycle,
) : OnBackPressedDispatcherOwner {
    private val dispatcher = OnBackPressedDispatcher()
    private val hostLifecycle: Lifecycle = hostLifecycle

    override val lifecycle: Lifecycle
        get() = hostLifecycle

    override val onBackPressedDispatcher: OnBackPressedDispatcher
        get() = dispatcher
}
