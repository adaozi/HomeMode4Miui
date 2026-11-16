/*
 * P9c（2026-11-16）：桌面「应用类别」行。
 *
 * 上游 1.2.2 把类别展开成独立的首页条目（CategoryRow），与收藏行同一套渲染管线。
 * 模块 HomeScreen 仍是「区块 + 收藏」两段式，故本文件提供等价的独立行组件：
 *   标题（类别图标 + 名称 + 成员数） + 成员图标网格（每行 4 个）。
 *
 * 设计约束：
 *  - 不持有任何状态、不订阅数据，纯展示 + 两个回调（点开 / 长按详情）
 *  - 空类别不渲染（由调用方过滤，避免桌面出现空行）
 *  - 成员上限由调用方裁剪（桌面一行类别展示 8 个，超出显示 +N）
 */
package com.galaxyrio.gracelauncher.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.galaxyrio.gracelauncher.data.AppCategory
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.ui.components.CategoryIndicator
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.homemode4miui.R

/** 每行成员图标数（与收藏区列数一致）。 */
private const val CATEGORY_COLUMNS = 4

/** 桌面类别行最多展示的成员数，其余折叠成 +N。 */
private const val CATEGORY_VISIBLE_MEMBERS = 8

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun CategoryRowBlock(
    category: AppCategory,
    members: List<LauncherApp>,
    onLaunch: (LauncherApp) -> Unit,
    onLongPress: (LauncherApp) -> Unit,
    showLabel: Boolean,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty()) return
    val appearance = LocalLauncherAppearance.current
    val visible = members.take(CATEGORY_VISIBLE_MEMBERS)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("category_row:${category.id}")) {
        // 标题行：类别图标 + 名称 + 成员数
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryIndicator(category.icon, Modifier.size(20.dp), tint = appearance.text)
            Text(
                text = categoryLabelOf(category),
                style = MaterialTheme.typography.titleSmall,
                color = appearance.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.grace_category_member_count, members.size),
                style = MaterialTheme.typography.labelSmall,
                color = appearance.text.copy(alpha = 0.7f),
            )
        }
        // 成员网格：每行 4 个
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.chunked(CATEGORY_COLUMNS).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { app ->
                        CategoryMemberCell(
                            app = app,
                            showLabel = showLabel,
                            textColor = appearance.text,
                            onClick = { onLaunch(app) },
                            onLongClick = { onLongPress(app) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 补齐空位，保持列宽一致
                    repeat(CATEGORY_COLUMNS - row.size) {
                        Box(Modifier.weight(1f))
                    }
                }
            }
        }
        if (members.size > visible.size) {
            Text(
                text = stringResource(R.string.grace_category_more, members.size - visible.size),
                style = MaterialTheme.typography.labelSmall,
                color = appearance.text.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CategoryMemberCell(
    app: LauncherApp,
    showLabel: Boolean,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 2.dp)
            .testTag("category_member:${app.key}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val icon: ImageBitmap? = app.icon
        if (icon != null) {
            androidx.compose.foundation.Image(
                bitmap = icon,
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(48.dp),
            )
        } else {
            // 图标缺失（解码失败/未装载）时的稳定占位：首字母
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text(app.label.take(1), style = MaterialTheme.typography.titleMedium, color = textColor)
            }
        }
        if (showLabel) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 类别显示名（与设置页一致的规则：自定义名优先，内置类别用本地化名）。 */
@Composable
private fun categoryLabelOf(category: AppCategory): String = category.name ?: stringResource(
    when (category.id) {
        com.galaxyrio.gracelauncher.data.FavoritesCategoryId -> R.string.settings_folder_favorites
        com.galaxyrio.gracelauncher.data.MusicCategoryId -> R.string.app_category_music
        com.galaxyrio.gracelauncher.data.PinnedCategoryId -> R.string.app_category_pinned
        com.galaxyrio.gracelauncher.data.NotificationCategoryId -> R.string.app_category_notification
        else -> R.string.app_category_name
    },
)
