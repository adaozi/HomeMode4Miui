/*
 * 上游 1.2.2 CategoryItemsEditor（原在 ui/overlays/ItemSelectionScreen.kt 内）。
 * P9b 抽成独立文件以便类别编辑器复用；模块差异见函数内注释（暂无 AppGroupPickerScreen）。
 */
package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.ShortcutPickerScreen
import com.galaxyrio.gracelauncher.ui.settings.LocalOpenDigitalWellbeing
import com.galaxyrio.gracelauncher.ui.settings.categoryLabel
import com.galaxyrio.gracelauncher.data.mergeHomeOrder

@Composable
internal fun CategoryItemsEditor(category: AppCategory, uiState: LauncherUiState, actions: LauncherActions,
    onBack: () -> Unit, header: @Composable () -> Unit) {
    var choosingShortcut by rememberSaveable(category.id) { mutableStateOf(false) }
    // 模块差异（P9b）：暂无 AppGroupPickerScreen（文件夹/应用堆选择页，属 P13）。
    // 类别成员的文件夹/堆入口先隐藏，手动类别仍可选应用与快捷方式。
    if (choosingShortcut) {
        BackHandler { choosingShortcut = false }
        ShortcutPickerScreen(uiState, actions, category.itemKeys.toSet(), { choosingShortcut = false },
            onSelect = { actions.toggleCategoryItem(category.id, it) })
        return
    }
    val dynamic = category.inclusion == CategoryInclusion.Dynamic
    // 模块差异：暂无「收藏增删」桥命令 → 收藏类别只读展示，指引去桌面编辑收藏
    val isFavorites = category.id == com.galaxyrio.gracelauncher.data.FavoritesCategoryId
    val openDigitalWellbeing = LocalOpenDigitalWellbeing.current
    val needsWellbeing = dynamic && category.dynamicRule == CategoryDynamicRule.FrequentlyOpened && !uiState.settings.digitalWellbeing.enabled
    val needsUsageAccess = dynamic && category.dynamicRule == CategoryDynamicRule.FrequentlyOpened && !needsWellbeing && !uiState.usage.hasAccess
    val needsNotifications = dynamic && category.dynamicRule == CategoryDynamicRule.Notifications && !uiState.media.hasAccess
    ItemSelectionScreen(categoryLabel(category), "category_editor",
        selected = uiState.categoryItems(category).map { key ->
            val app = uiState.findItem(key)
            SelectionEntry(key, app?.label ?: stringResource(R.string.popup_item_unavailable), app)
        }, apps = uiState.allApps, enabled = !uiState.isLoadingSettings && !uiState.settingsLoadFailed,
        onToggle = { actions.toggleCategoryItem(category.id, it) },
        onRemove = { key -> actions.updateAppCategory(category.id) { it.copy(itemKeys = it.itemKeys - key) } },
        onReorder = { keys -> actions.updateAppCategory(category.id) { it.copy(itemKeys = mergeHomeOrder(keys, it.itemKeys)) } },
        onDone = onBack, onAddShortcut = if (dynamic) null else ({ choosingShortcut = true }),
        onAddFolder = null, onAddStack = null,
        header = header, showApps = !dynamic && !isFavorites,
        readOnlySelected = dynamic || isFavorites,
        emptyMessage = stringResource(if (dynamic) R.string.search_no_results else R.string.homepage_empty),
        selectedNotice = if (isFavorites) ({
            Text(stringResource(R.string.grace_favorites_edit_hint), Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) else if (!needsWellbeing && !needsUsageAccess && !needsNotifications) null else ({
            Column(Modifier.padding(horizontal = 8.dp).testTag("category_permission_notice")) {
                Text(stringResource(when {
                    needsWellbeing -> R.string.category_dw_disabled
                    needsUsageAccess -> R.string.usage_access_required
                    else -> R.string.media_access_required
                }), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = when {
                    needsWellbeing -> openDigitalWellbeing ?: {}
                    needsUsageAccess -> actions.requestUsageAccess
                    else -> actions.requestMediaAccess
                }, enabled = !needsWellbeing || openDigitalWellbeing != null, modifier = Modifier.testTag("category_permission_action")) {
                    Text(stringResource(when {
                        needsWellbeing -> R.string.category_open_dw
                        needsUsageAccess -> R.string.usage_access
                        else -> R.string.media_open_settings
                    }))
                }
            }
        }))
}
