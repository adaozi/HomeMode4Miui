/*
 * perf3 公共组件（v2.9.41-perf 批）：60s 对齐时钟节拍统一出口。
 *
 * 原三处各自手写 60s 循环（HomeScreen / ClockStyleSettings / AgendaSheet），
 * 仅 HomeScreen 做过 repeatOnLifecycle(STARTED) 改造，其余退后台仍每分钟醒主线程
 * 重组。本组件统一语义：
 *   - repeatOnLifecycle(STARTED) 包裹：生命周期 ≤ CREATED 挂起整个循环（零唤醒），
 *     回前台自动重启；宿主 lifecycle 未同步（hook 未命中）时恒为 RESUMED，行为同旧版
 *     （安全降级，与 perf3 定案一致）。
 *   - 整分对齐（delay 到下一分钟边界），与原各处节奏一致。
 * TimeText.kt 的按 postedAt 键控"通知年龄"节拍器语义不同（新通知到达需立即归零，
 * 整分对齐会让"刚刚"残留最长 59s），不收敛到本组件（其 perf3 改造已就位）。
 */
package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun rememberMinuteTick(): Instant {
    val owner = LocalLifecycleOwner.current
    val tick = produceState(initialValue = Instant.now()) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = Instant.now()
                delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
    }
    return tick.value
}
