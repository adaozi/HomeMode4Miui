package com.galaxyrio.gracelauncher.data.notifications

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
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
)

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
    private var cancel: ((String) -> Boolean)? = null
    private var visible: (String) -> Boolean = { true }
    private var revision = 0L
    private val _state = MutableStateFlow<Map<String, List<AppNotification>>>(emptyMap())
    val state = _state.asStateFlow()

    fun connected(notifications: List<StatusBarNotification>, cancelNotification: (String) -> Boolean) {
        entries.clear()
        cancel = cancelNotification
        visible = { true }
        notifications.forEach { sbn -> parse(sbn)?.let { entries[sbn.key] = it } }
        publish()
    }

    fun posted(sbn: StatusBarNotification) {
        if (cancel == null) return
        val entry = parse(sbn)
        if (entry == null) entries.remove(sbn.key) else entries[sbn.key] = entry
        publish()
    }

    fun removed(key: String) { if (entries.remove(key) != null) publish() }

    fun ranking(isVisible: (String) -> Boolean) { visible = isVisible; publish() }

    fun disconnected() {
        cancel = null; visible = { true }; entries.clear(); _state.value = emptyMap()
    }

    fun dismiss(key: String, revision: Long): Boolean {
        val entry = entries[key]?.takeIf { it.display.revision == revision && it.display.canDismiss && visible(key) } ?: return false
        // Wait for onNotificationRemoved; the system owns dismissal, including deleteIntent.
        // In particular, do not cancel an app-wide group when swiping just one child.
        return cancel?.invoke(entry.display.key) == true
    }

    fun open(context: Context, key: String, revision: Long): Boolean {
        val entry = entries[key]?.takeIf { it.display.revision == revision && visible(key) } ?: return false
        val intent = entry.contentIntent ?: return false
        return runCatching {
            intent.send(context, 0, null, null, null, null, mediaPlayerLaunchOptions().toBundle())
            if (entry.autoCancel && entry.display.canDismiss) dismiss(key, revision)
        }.isSuccess
    }

    private fun publish() {
        val eligible = entries.values.filter { visible(it.display.key) }
        val groupsWithChildren = eligible.filterNot { it.summary }.map { it.display.packageName to it.groupKey }.toSet()
        _state.value = eligible.filterNot { it.summary && (it.display.packageName to it.groupKey) in groupsWithChildren }
            .map { it.display }.sortedByDescending { it.postedAt }.groupBy { it.packageName }
    }

    private fun parse(sbn: StatusBarNotification): Entry? = runCatching {
        // LauncherApp currently models the personal profile only. Do not leak work/private
        // profile text onto a same-package personal app row.
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
