package com.galaxyrio.gracelauncher.data.media

import android.app.Notification
import android.app.NotificationManager
import android.media.session.MediaSession
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import com.galaxyrio.gracelauncher.data.notifications.appNotifications
import com.galaxyrio.gracelauncher.data.notifications.AppNotificationRanking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keep only media-notification identity, not notification text, artwork or actions. */
internal data class MediaNotificationRef(val key: String, val packageName: String, val token: MediaSession.Token)

internal data class MediaNotificationSnapshot(
    val connected: Boolean = false,
    val notifications: List<MediaNotificationRef> = emptyList(),
) {
    fun contains(packageName: String, token: MediaSession.Token): Boolean = connected &&
        notifications.any { it.packageName == packageName && it.token == token }
}

@MainThread
internal class MediaNotificationStore {
    private val references = linkedMapOf<String, MediaNotificationRef>()
    private val _state = MutableStateFlow(MediaNotificationSnapshot())
    val state = _state.asStateFlow()

    fun connected(notifications: List<StatusBarNotification>) {
        references.clear()
        notifications.mapNotNull(::mediaNotificationRef).forEach { references[it.key] = it }
        publish()
    }

    fun posted(notification: StatusBarNotification) {
        if (!_state.value.connected) return
        val media = mediaNotificationRef(notification)
        // A notification can be updated in-place from MediaStyle to an ordinary notification.
        val changed = if (media == null) references.remove(notification.key) != null
            else references.put(media.key, media) != media
        if (changed) publish()
    }

    fun removed(key: String) {
        // Removal callbacks may carry a light notification with no media extras.
        if (references.remove(key) != null) publish()
    }

    fun disconnected() {
        references.clear()
        _state.value = MediaNotificationSnapshot()
    }

    private fun publish() {
        _state.value = MediaNotificationSnapshot(connected = true, notifications = references.values.toList())
    }
}

internal val mediaNotifications = MediaNotificationStore()

/** One system binding feeds app messages and media-notification visibility independently.
 *
 * P6 宿主适配（唯一改动点）：原版 listener 与 UI 同进程，单例 state 直接被 ViewModel
 * collect；本模块 listener 落在模块 App 进程而 UI 在宿主进程 —— onListenerConnected
 * 时拉起 GraceBridgeEngine（快照广播桥），解绑时清指纹。数据面（两个单例 store）
 * 与原版逐字一致。 */
class MediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        // P6：桥引擎启动放最前（hotfix4）——后面任何一句抛 RuntimeException 都会
        // 跳过末尾的 start，导致引擎永不启动（无快照、媒体行不出现的候选断点）。
        // 引擎自身全 runCatching 且幂等（started 标志），先启动零副作用。
        com.homemode4miui.bridge.GraceBridgeEngine.start(applicationContext)
        // notif7：重连登记 —— 清 rebind 升级计数（重新授权 / rebind / toggle 成功后走这里）
        com.homemode4miui.bridge.GraceBridgeEngine.noteListenerConnected()
        // Bootstrap only after binding, so existing paused notifications survive a launcher restart.
        try {
            val notifications = activeNotifications.orEmpty().toList()
            // notif6：seed 数量打点 —— 区分"listener 未连"与"连了但 seed 空"
            android.util.Log.i("HomeMode4Miui",
                "listener connected, seeded=${notifications.size} active notifications")
            mediaNotifications.connected(notifications)
            appNotifications.connected(notifications) { key -> runCatching { cancelNotification(key) }.isSuccess }
            updateAppRanking(currentRanking)
        } catch (_: RuntimeException) {
            mediaNotifications.disconnected()
            appNotifications.disconnected()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        updateAppRanking(currentRanking)
        mediaNotifications.posted(sbn)
        appNotifications.posted(sbn)
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        mediaNotifications.removed(sbn.key)
        appNotifications.removed(sbn.key)
    }
    override fun onNotificationRankingUpdate(rankingMap: RankingMap) { updateAppRanking(rankingMap) }
    // 上游同步 v1.1.8：ranking 回调改返回 AppNotificationRanking（新增 silent 维度，
    // 供 ShortcutsFoldersSettings 静默/普通通知显示策略消费）。桥挂点不变。
    private fun updateAppRanking(rankingMap: RankingMap?) {
        appNotifications.ranking { key ->
            val ranking = Ranking()
            if (rankingMap == null || !rankingMap.getRanking(key, ranking)) AppNotificationRanking()
            else AppNotificationRanking(
                visible = !ranking.isSuspended && ranking.importance != NotificationManager.IMPORTANCE_NONE,
                // Use the system's per-notification importance, not sound/vibration or DND:
                // muting the phone does not turn ordinary messages into silent channels.
                silent = ranking.importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW,
            )
        }
    }
    override fun onListenerDisconnected() {
        mediaNotifications.disconnected(); appNotifications.disconnected()
        com.homemode4miui.bridge.GraceBridgeEngine.onListenerDisconnected()
    }
    override fun onDestroy() {
        mediaNotifications.disconnected()
        appNotifications.disconnected()
        super.onDestroy()
    }
}

@Suppress("DEPRECATION")
private fun mediaNotificationRef(sbn: StatusBarNotification): MediaNotificationRef? = runCatching {
    val extras = sbn.notification.extras ?: return null
    val token = if (Build.VERSION.SDK_INT >= 33) {
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
    } else {
        extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
    } ?: return null
    MediaNotificationRef(sbn.key, sbn.packageName, token)
}.getOrNull()
