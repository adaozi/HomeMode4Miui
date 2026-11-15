/*
 * 桥快照摄入域（GraceHomeViewModel 拆分第 4 步，2026-11-15）。
 *
 * 从 GraceHomeViewModel 搬出的成员：
 *   字段：hostForeground / pendingNotifications / pendingMedia / snapshotFlushScheduled /
 *        graceUntilUptime / graceFlushJob / lastSnapshotPullUptime
 *   状态：homeMedia / notifications / hasMediaAccess（写 GraceHomeUiState）
 *   方法：refreshMediaAccess / onBridgeMedia / onBridgeNotifications / applyMedia /
 *        applyPendingSnapshots / scheduleSnapshotFlush / sameNotifications / inResumeGrace /
 *        onHostPaused / onHostResumed / sendBridgeCommand / controlMedia / dismissMedia /
 *        openNotification / dismissNotification
 *   常量：SNAPSHOT_COALESCE_MS / SNAPSHOT_GRACE_MS / SNAPSHOT_PULL_MIN_INTERVAL_MS
 *
 * 这一域的核心不是"存快照"，而是**抑制回放**：桌面后台期间模块进程逐条投递的通知
 * 变化，若每条都写 uiState，解冻后会看到历史整串闪一遍。三段时间窗（合并窗口 /
 * 后台 pending / 回前台宽限期）全部在类内，勿拆散到别处。
 *
 * 跨域：回前台顺带补读日程（后台期间日历可能被改），由 VM 注入 onReturnedToForeground。
 */
package com.homemode4miui.grace

import android.app.Application
import android.app.NotificationManager
import com.galaxyrio.gracelauncher.data.media.MediaAccess
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.data.notifications.AppNotification
import com.homemode4miui.bridge.GraceBridge
import com.homemode4miui.util.ModLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class BridgeInbox(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<GraceHomeUiState>,
    /** 从后台回前台时执行的跨域补充动作（VM 传 refreshAgenda）。 */
    private val onReturnedToForeground: () -> Unit = {},
) {
    /**
     * 宿主桌面是否在前台（GraceHomeView.onHomeAttached / onHomePaused 同步）。
     *
     * 装机实锤症状：长时间不打开桌面后突然回桌面，通知预览会把这期间的历史整批
     * 逐条回放一遍。根因：模块进程在桌面后台期间每有一条通知变化就发一次广播
     * （指纹去重只挡内容相同的重复），宿主侧 onReceive 又逐条直写 uiState ——
     * 桌面进程被 MIUI 冻结/限流时这批广播在主线程排队，解冻（回桌面）后逐条
     * deliver，每条都触发一次重组/布局 → 视觉上就是"回放"。
     * 修复：后台只把最新一份滚动进 pending（不产生任何 state 变更），回前台一次性
     * 应用；前台则做 80ms 合并窗口，把同一批投递压成一次更新。
     * hook 5b 未命中的宿主版本上本标志恒 true，行为等同旧版（安全降级）。
     */
    private var hostForeground = true
    /** 后台期间/合并窗口内到达的最新通知快照（滚动覆盖，只保留最后一份）。 */
    private var pendingNotifications: Map<String, List<AppNotification>>? = null
    /** 同上，媒体快照（含 artChanged，应用时仍要按 revision 沿用旧封面）。 */
    private var pendingMedia: Pair<NowPlaying?, Boolean>? = null
    private var snapshotFlushScheduled = false
    /** 回前台宽限期截止时刻（uptimeMillis）：系统可能正在把冻结期间积压的广播逐条投递。 */
    private var graceUntilUptime = 0L
    private var graceFlushJob: Job? = null
    /** 上次向模块进程拉全量的时刻（uptimeMillis），防可见性抖动反复拉取。 */
    private var lastSnapshotPullUptime = 0L

    private companion object {
        /** 桥快照合并窗口：同批广播（回桌面后逐条 deliver / 通知风暴）压成一次更新。 */
        const val SNAPSHOT_COALESCE_MS = 80L
        /** 回桌面宽限期：冻结期积压的投递通常在这段时间内排空，期间只应用最后一份。 */
        const val SNAPSHOT_GRACE_MS = 700L
        /** 回前台拉全量的最小间隔（可见性抖动/连续 onResume 去重）。 */
        const val SNAPSHOT_PULL_MIN_INTERVAL_MS = 3_000L
    }

    /** B6.1：通知监听权限探测（模块 App 的 listener 组件；公开 API 任意进程可查）。 */
    fun refreshMediaAccess() {
        val granted = runCatching {
            application.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(MediaAccess.component(application))
        }.getOrDefault(false)
        uiState.update { it.copy(hasMediaAccess = granted) }
    }

    // ---- 桥快照摄入（GraceBridgeReceiver 转发）----

    fun onMedia(media: NowPlaying?, artChanged: Boolean) {
        pendingMedia = media to artChanged
        if (!hostForeground) return          // 后台：只滚动 pending，零重组
        if (inResumeGrace()) return          // 回桌面宽限期：由宽限收尾统一应用最后一份
        scheduleSnapshotFlush()
    }

    fun onNotifications(notifications: Map<String, List<AppNotification>>) {
        // 与"目标状态"（后台/合并窗口内以 pending 为准，否则以当前 uiState 为准）相同
        // 说明没有新信息，直接丢弃。不能比 Map equals：AppNotification.icon 是 Icon 对象，
        // 每次解码都是新实例，引用不等会让"内容相同"判定失效 —— 用 key + revision 指纹。
        val target = pendingNotifications ?: uiState.value.notifications
        if (sameNotifications(target, notifications)) return
        pendingNotifications = notifications
        if (!hostForeground) return          // 后台：只滚动 pending，零重组
        if (inResumeGrace()) return          // 回桌面宽限期：同上
        scheduleSnapshotFlush()
    }

    /** 通知快照语义相等（key + revision 已能唯一确定一条通知的内容版本）。 */
    private fun sameNotifications(
        a: Map<String, List<AppNotification>>,
        b: Map<String, List<AppNotification>>,
    ): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for ((pkg, left) in a) {
            val right = b[pkg] ?: return false
            if (left.size != right.size) return false
            for (i in left.indices) {
                if (left[i].key != right[i].key || left[i].revision != right[i].revision) return false
            }
        }
        return true
    }

    /** 是否处于"刚回桌面"的宽限期（见 SNAPSHOT_GRACE_MS 注释）。 */
    private fun inResumeGrace(): Boolean =
        graceUntilUptime > 0L && android.os.SystemClock.uptimeMillis() < graceUntilUptime

    /**
     * 快照合并落状态（前台路径）。
     *
     * 同一批广播（回桌面后主线程序列 deliver、或通知风暴）在 [SNAPSHOT_COALESCE_MS]
     * 窗口内只应用最后一份 —— 逐条应用会让通知预览把历史整串闪一遍。80ms 对通知
     * 预览的观感不可感知（远低于一次通知切换的人类可辨阈值），但足以把一整批投递
     * 压成一次 uiState 变更。
     */
    private fun scheduleSnapshotFlush() {
        if (snapshotFlushScheduled) return
        snapshotFlushScheduled = true
        scope.launch {
            delay(SNAPSHOT_COALESCE_MS)
            snapshotFlushScheduled = false
            applyPendingSnapshots()
        }
    }

    /** 立即应用 pending（回前台路径：不等待合并窗口，首帧就要最新内容）。 */
    private fun applyPendingSnapshots() {
        pendingNotifications?.let { next ->
            pendingNotifications = null
            uiState.update {
                if (sameNotifications(it.notifications, next)) it else it.copy(notifications = next)
            }
        }
        pendingMedia?.let { (media, artChanged) ->
            pendingMedia = null
            applyMedia(media, artChanged)
        }
    }

    /** 媒体快照应用（原 onBridgeMedia 主体：封面未随包变化时沿用旧封面）。 */
    private fun applyMedia(media: NowPlaying?, artChanged: Boolean) {
        uiState.update { current ->
            // 封面未随包变化时沿用旧封面（artKey = sessionId+revision 的引擎侧去重约定）
            val merged = if (media != null && !artChanged && media.artwork == null) {
                val previous = current.homeMedia
                if (previous?.sessionId == media.sessionId && previous.revision == media.revision) {
                    media.copy(artwork = previous.artwork)
                } else media
            } else media
            current.copy(homeMedia = merged)
        }
    }

    /**
     * 宿主桌面退后台（GraceHomeView.onHomePaused → Launcher.onPause，perf3 同链）。
     * 之后到达的桥快照只进 pending：后台不产生重组，也避免冻结解冻后整批回放。
     */
    fun onHostPaused() {
        if (!hostForeground) return          // 幂等（visibility 与 onPause 双通道都会打进来）
        hostForeground = false
        ModLog.i("host paused: bridge snapshots now coalesced (no recompose)")
    }

    /**
     * 宿主桌面回前台（GraceHomeView.onHomeAttached 复用分支 / onVisibilityAggregated）。
     *
     * 处理顺序：
     * ① 立即应用后台期间已滚动到 pending 的最新一份（冻结前收到的最后状态）；
     * ② 开一个 [SNAPSHOT_GRACE_MS] 宽限期 —— 冻结期间积压的广播通常在解冻后紧接着
     *    逐条 deliver，宽限期内只滚动 pending 不落 state，收尾时一次性应用最后一份，
     *    视觉上直接停在最新通知，不再"整段回放"；
     * ③ 向模块进程拉一次全量（长时间后台可能被省电策略冻结甚至杀进程，期间的通知
     *    变化根本没送达，只靠 pending 会停在旧内容）。引擎侧 refreshSnapshots 清指纹
     *    重发，内容相同则被相等短路，无 UI 抖动。
     */
    fun onHostResumed() {
        val wasBackground = !hostForeground
        hostForeground = true
        applyPendingSnapshots()
        if (!wasBackground) return
        ModLog.i("host resumed: grace=${SNAPSHOT_GRACE_MS}ms (stale burst suppressed)")
        // 回桌面补读日程：后台期间日历可能被改（ContentObserver 之外的兜底 ——
        // 宿主进程被冻结时 observer 回调同样会排队/丢失）。静默刷新不闪。
        onReturnedToForeground()
        graceUntilUptime = android.os.SystemClock.uptimeMillis() + SNAPSHOT_GRACE_MS
        graceFlushJob?.cancel()
        graceFlushJob = scope.launch {
            delay(SNAPSHOT_GRACE_MS)
            graceUntilUptime = 0L
            applyPendingSnapshots()
            ModLog.i("host resume grace flush: apps=" +
                uiState.value.notifications.size + " entries=" +
                uiState.value.notifications.values.sumOf(List<*>::size))
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastSnapshotPullUptime >= SNAPSHOT_PULL_MIN_INTERVAL_MS) {
                lastSnapshotPullUptime = now
                requestSnapshot()
            }
        }
    }

    // ---- 命令上行（宿主 → 模块 App 进程；命令在持有 token 的进程内执行）----

    /** 向模块进程拉全量快照（挂载 / 回前台拉取）。 */
    fun requestSnapshot() = sendBridgeCommand(GraceBridge.Cmd.SNAPSHOT_REFRESH)

    private fun sendBridgeCommand(cmd: String, arg1: String? = null, arg2: String? = null) {
        runCatching {
            // 2026-11-15：命令附带能力令牌（GraceCommandReceiver 校验，见 BridgeToken）
            application.sendBroadcast(GraceBridge.command(application, cmd, arg1, arg2))
        }.onFailure { ModLog.e("BridgeInbox sendBridgeCommand($cmd) failed", it) }
    }

    /** B6.1：媒体控制（上一首/播放暂停/下一首/打开播放器）。 */
    fun controlMedia(sessionId: String, command: MediaCommand) {
        sendBridgeCommand(GraceBridge.Cmd.MEDIA_COMMAND, sessionId, GraceBridge.mediaCommandName(command))
    }

    /** B6.1：本地划走（原版 dismiss 语义：只影响本桌面显示，不取消系统通知）。 */
    fun dismissMedia(sessionId: String, revision: Long): Boolean {
        sendBridgeCommand(GraceBridge.Cmd.MEDIA_DISMISS, sessionId, revision.toString())
        // 原版立即本地消失由 store 状态驱动；跨进程简化为命令发出后 UI 等快照更新
        return true
    }

    /** B6.3：打开通知（contentIntent 由模块 App 进程以 BAL 选项发送）。 */
    fun openNotification(key: String, revision: Long): Boolean {
        sendBridgeCommand(GraceBridge.Cmd.NOTIFICATION_OPEN, key, revision.toString())
        return true
    }

    /** B6.3：清除通知（系统侧 cancel，原版"等 onNotificationRemoved"语义经快照回流）。 */
    fun dismissNotification(key: String, revision: Long): Boolean {
        sendBridgeCommand(GraceBridge.Cmd.NOTIFICATION_DISMISS, key, revision.toString())
        return true
    }

    /** VM shutdown 调用：取消宽限期收尾 job（VM 每次 attach 新建，dispose 即终态）。 */
    fun dispose() {
        graceFlushJob?.cancel()
        graceFlushJob = null
        pendingNotifications = null
        pendingMedia = null
        snapshotFlushScheduled = false
    }
}
