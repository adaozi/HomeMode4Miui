package com.galaxyrio.gracelauncher.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.CategoryIcon

/** Shared category symbol for settings, section headings and the alphabet rail. */
@Composable
internal fun CategoryIndicator(
    icon: CategoryIcon,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val resource = icon.drawableRes() ?: return
    Icon(
        painter = painterResource(resource),
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        tint = tint,
    )
}

@DrawableRes
internal fun CategoryIcon.drawableRes(): Int? = when (this) {
    CategoryIcon.None -> null
    CategoryIcon.MusicNote -> R.drawable.ms_music_note
    CategoryIcon.Keep -> R.drawable.ms_keep
    CategoryIcon.NotificationsUnread -> R.drawable.ms_notifications_unread
    CategoryIcon.Circle -> R.drawable.ms_circle
    CategoryIcon.Info -> R.drawable.ms_info
    CategoryIcon.Schedule -> R.drawable.ms_schedule
    CategoryIcon.ClearDay -> R.drawable.ms_clear_day
    CategoryIcon.Bedtime -> R.drawable.ms_bedtime
}
