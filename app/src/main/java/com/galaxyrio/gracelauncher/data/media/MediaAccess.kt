package com.galaxyrio.gracelauncher.data.media

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

object MediaAccess {
    fun component(context: Context) = ComponentName(context, MediaNotificationListener::class.java)

    fun isGranted(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))
    }.getOrDefault(false)

    fun settingsIntent(context: Context): Intent {
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            if (detail.resolveActivity(context.packageManager) != null) return detail
        }
        return Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
