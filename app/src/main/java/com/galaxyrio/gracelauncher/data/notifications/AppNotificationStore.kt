package com.galaxyrio.gracelauncher.data.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Process
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import com.galaxyrio.gracelauncher.data.CategoryNotificationLevel
import com.galaxyrio.gracelauncher.data.media.mediaPlayerLaunchOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ephemeral display data. Notification text, icons and intents are never persisted or logged. */
data class AppNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val revision: Long,
    val canDismiss: Boolean,
    val canOpen: Boolean,
    val icon: Icon? = null,
    val silent: Boolean = false,
)

/** Notification presence is profile-specific and does not retain any notification content. */
data class NotificationAppId(val packageName: String, val user: UserHandle)

internal data class AppNotificationRanking(val visible: Boolean = true, val silent: Boolean = false,
    val importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
    val categoryLevel: CategoryNotificationLevel get() = when {
        importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW -> CategoryNotificationLevel.Silent
        importance >= NotificationManager.IMPORTANCE_HIGH -> CategoryNotificationLevel.High
        else -> CategoryNotificationLevel.Normal
    }
}

@MainThread
internal class AppNotificationStore {
    private data class Entry(
        val display: AppNotification,
        val groupKey: String,
        val summary: Boolean,
        val contentIntent: PendingIntent?,
        val autoCancel: Boolean,
    )
    private val entries = linkedMapOf<String, Entry>()
    private val categoryEntries = linkedMapOf<String, NotificationAppId>()
    private var cancel: ((String) -> Boolean)? = null
    private var classification: (String) -> AppNotificationRanking = { AppNotificationRanking() }
    private var revision = 0L
    private val _state = MutableStateFlow<Map<String, List<AppNotification>>>(emptyMap())
    val state = _state.asStateFlow()
    private val _categoryState = MutableStateFlow<Map<NotificationAppId, Set<CategoryNotificationLevel>>>(emptyMap())
    val categoryState = _categoryState.asStateFlow()

    fun connected(notifications: List<StatusBarNotification>,
        ranking: (String) -> AppNotificationRanking = { AppNotificationRanking() }, cancelNotification: (String) -> Boolean) {
        entries.clear(); categoryEntries.clear()
        cancel = cancelNotification
        classification = ranking
        notifications.forEach { sbn ->
            categoryEntries[sbn.key] = NotificationAppId(sbn.packageName, sbn.user)
            parse(sbn)?.let { entries[sbn.key] = it }
        }
        publish()
    }

    fun posted(sbn: StatusBarNotification, ranking: (String) -> AppNotificationRanking = classification) {
        if (cancel == null) return
        classification = ranking
        categoryEntries[sbn.key] = NotificationAppId(sbn.packageName, sbn.user)
        val entry = parse(sbn)
        if (entry == null) entries.remove(sbn.key) else entries[sbn.key] = entry
        publish()
    }

    fun removed(key: String) {
        val removedDisplay = entries.remove(key) != null
        val removedCategory = categoryEntries.remove(key) != null
        if (removedDisplay || removedCategory) publish()
    }

    fun ranking(ranking: (String) -> AppNotificationRanking) { classification = ranking; publish() }

    /**
     * 模块定制（hotfix notif-churn）：解绑 ≠ 通知被清除。
     *  - keepDisplay=false（授权被撤销 / 进程销毁）：清空并发布空快照（真正失效语义）；
     *  - keepDisplay=true（授权仍在，MIUI 省电解绑，notif7 自愈链会重绑）：保留最后已知快照
     *    且不发布 —— StateFlow 值不变 → 引擎无空广播 → 桌面不清行；重绑后 connected(seed)
     *    用系统真值校正，集合未变时引擎指纹去重直接拦截，消除「解绑↔重绑」抖动。
     */
    fun disconnected(keepDisplay: Boolean = false) {
        cancel = null; classification = { AppNotificationRanking() }
        if (!keepDisplay) {
            entries.clear(); categoryEntries.clear()
            publish()
        }
    }

    fun dismiss(key: String, revision: Long): Boolean {
        val entry = entries[key]?.takeIf { it.display.revision == revision && it.display.canDismiss && classification(key).visible } ?: return false
        // Wait for onNotificationRemoved; the system owns dismissal, including deleteIntent.
        // In particular, do not cancel an app-wide group when swiping just one child.
        return cancel?.invoke(entry.display.key) == true
    }

    fun open(context: Context, key: String, revision: Long): Boolean {
        val entry = entries[key]?.takeIf { it.display.revision == revision && classification(key).visible } ?: return false
        val intent = entry.contentIntent ?: return false
        return runCatching {
            intent.send(context, 0, null, null, null, null, mediaPlayerLaunchOptions().toBundle())
            if (entry.autoCancel && entry.display.canDismiss) dismiss(key, revision)
        }.isSuccess
    }

    private fun publish() {
        // Both projections use the same ranking snapshot. Category membership deliberately
        // includes media/blank/profile notifications that are unsuitable as text previews.
        val rankings = categoryEntries.keys.associateWith(classification)
        val categories = linkedMapOf<NotificationAppId, MutableSet<CategoryNotificationLevel>>()
        categoryEntries.forEach { (key, app) ->
            val ranking = rankings.getValue(key)
            if (ranking.visible) categories.getOrPut(app) { linkedSetOf() }.add(ranking.categoryLevel)
        }
        val eligible = entries.values.mapNotNull { entry ->
            val ranking = rankings.getValue(entry.display.key)
            if (ranking.visible) entry.copy(display = entry.display.copy(silent = ranking.silent)) else null
        }
        val groupsWithChildren = eligible.filterNot { it.summary }.map { it.display.packageName to it.groupKey }.toSet()
        _state.value = eligible.filterNot { it.summary && (it.display.packageName to it.groupKey) in groupsWithChildren }
            .map { it.display }.sortedByDescending { it.postedAt }.groupBy { it.packageName }
        _categoryState.value = categories.mapValues { (_, levels) -> levels.toSet() }
    }

    private fun parse(sbn: StatusBarNotification): Entry? = runCatching {
        // Text previews remain personal-profile-only. Never put work/private notification
        // content onto a same-package personal app row.
        if (sbn.user != Process.myUserHandle()) return null
        val notification = sbn.notification
        val extras = notification.extras ?: return null
        if (extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || notification.visibility == Notification.VISIBILITY_SECRET) return null
        val messaging = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val messages = messaging?.messages.orEmpty().takeLast(8)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = when {
            messages.isNotEmpty() -> messages.joinToString("\n") { message ->
                val sender = message.person?.name?.toString()?.trim().orEmpty()
                val text = message.text?.toString().orEmpty()
                if (sender.isEmpty()) text else "$sender: $text"
            }
            !extras.getCharSequence(Notification.EXTRA_BIG_TEXT).isNullOrBlank() -> extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            !extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).isNullOrEmpty() -> extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.joinToString("\n")
            else -> extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        }.trim()
        if (title.isBlank() && body.isBlank()) return null
        Entry(
            display = AppNotification(sbn.key, sbn.packageName, title.take(256), body.take(8192),
                sbn.postTime, ++revision, sbn.isClearable, notification.contentIntent != null,
                notification.getLargeIcon()),
            groupKey = sbn.groupKey,
            summary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            contentIntent = notification.contentIntent,
            autoCancel = notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
        )
    }.getOrNull()
}

internal val appNotifications = AppNotificationStore()
