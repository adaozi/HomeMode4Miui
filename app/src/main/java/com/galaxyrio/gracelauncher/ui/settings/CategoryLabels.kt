package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.AppCategory
import com.galaxyrio.gracelauncher.data.FavoritesCategoryId
import com.galaxyrio.gracelauncher.data.MusicCategoryId
import com.galaxyrio.gracelauncher.data.PinnedCategoryId
import com.galaxyrio.gracelauncher.data.NotificationCategoryId

/**
 * 类别显示名（上游 ui/settings/AppCategorySettings.kt 内的同名 Composable）。
 * 独立成文件的原因：AlphabetRail（A-Z 类别分段）也要用，避免为此整页搬 P9 设置页。
 */
@Composable
internal fun categoryLabel(category: AppCategory): String = category.name ?: stringResource(when (category.id) {
    FavoritesCategoryId -> R.string.settings_folder_favorites
    MusicCategoryId -> R.string.app_category_music
    PinnedCategoryId -> R.string.app_category_pinned
    NotificationCategoryId -> R.string.app_category_notification
    else -> R.string.app_category_name
})
