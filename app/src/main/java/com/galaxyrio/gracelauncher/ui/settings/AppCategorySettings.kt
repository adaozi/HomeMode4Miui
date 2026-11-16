@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.*
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.CategoryIndicator
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.overlays.CategoryItemsEditor
import com.galaxyrio.gracelauncher.ui.overlays.TextEntryDialog
import java.util.Calendar
import java.util.UUID


@Composable
private fun CategoryCondition.label() = stringResource(when (this) {
    CategoryCondition.Always -> R.string.app_category_always
    CategoryCondition.Headphones -> R.string.app_category_headphones
    CategoryCondition.Time -> R.string.app_category_time
})

@Composable
internal fun AppCategoriesSettings(
    uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit,
    selectedIds: Set<String>? = null, onToggle: (String) -> Unit = {},
    selectionPlacement: CategoryPlacement = CategoryPlacement.Home, onEdit: (String) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var openingId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(openingId, uiState.appCategories, uiState.settingsSaveFailed) {
        val id = openingId ?: return@LaunchedEffect
        if (uiState.appCategories.any { it.id == id }) { openingId = null; onEdit(id) }
        else if (uiState.settingsSaveFailed) openingId = null
    }
    SettingsScaffold(stringResource(R.string.app_categories_title), "app_categories", onBack) { padding ->
        SettingsList(padding) {
            item { Spacer(Modifier.height(24.dp)) }
            item {
                SettingsActionItem(stringResource(R.string.app_category_create), null, 0, 1, "category_create",
                    enabled = openingId == null, leading = { LauncherIcon(LauncherSymbol.Plus) }) { creating = true }
            }
            item { SettingsHeading(stringResource(R.string.app_categories_title)) }
            itemsIndexed(uiState.appCategories, key = { _, category -> category.id }) { index, category ->
                val checked = selectedIds?.contains(category.id)
                SettingsActionItem(categoryLabel(category),
                    pluralStringResource(R.plurals.app_category_item_count, uiState.categoryItems(category).size, uiState.categoryItems(category).size) + " · " + category.condition.label(),
                    index, uiState.appCategories.size, "category:${category.id}",
                    modifier = Modifier.semantics {
                        if (checked != null) {
                            role = Role.Checkbox
                            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                        }
                    }, leading = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (checked != null) Checkbox(checked, null, enabled = LocalSettingsStorageState.current.canEdit)
                            if (category.icon == CategoryIcon.None) LauncherIcon(LauncherSymbol.Category)
                            else CategoryIndicator(category.icon)
                        }
                    }) { if (selectedIds == null) onEdit(category.id) else onToggle(category.id) }
            }
        }
    }
    if (creating) TextEntryDialog(stringResource(R.string.app_category_create), "", { creating = false }, { name ->
        val id = UUID.randomUUID().toString()
        // 模块差异：上游新建默认 CategoryPlacement.None（创建完在桌面看不见，用户必须再进编辑器
        // 把「放置」改成桌面）。这里默认 Home —— 从「应用类别」页新建的意图就是放到桌面，
        // 选成员时仍可改回抽屉/不放置。
        actions.saveAppCategory(AppCategory(id, name,
            placement = if (selectedIds != null) selectionPlacement else CategoryPlacement.Home))
        creating = false
        openingId = id
    })
}

@Composable
internal fun AppCategoryEditorSettings(categoryId: String?, uiState: LauncherUiState, actions: LauncherActions, onBack: () -> Unit) {
    val category = uiState.appCategories.firstOrNull { it.id == categoryId }
    if (category == null) {
        SettingsScaffold(stringResource(R.string.app_categories_title), "category_unavailable", onBack) { padding ->
            Text(stringResource(R.string.app_category_unavailable), Modifier.padding(padding).padding(24.dp))
        }
        return
    }
    val name = categoryLabel(category)
    var dialog by rememberSaveable(category.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    fun formatTime(minute: Int): String = DateFormat.getTimeFormat(context).format(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, minute / 60); set(Calendar.MINUTE, minute % 60)
    }.time)
    val fields = buildList {
        add("name"); add("inclusion")
        if (category.inclusion == CategoryInclusion.Dynamic) {
            add("rule")
            if (category.dynamicRule == CategoryDynamicRule.Notifications) add("levels")
            if (category.dynamicRule == CategoryDynamicRule.FrequentlyOpened) add("limit")
        }
        add("icon"); add("placement"); add("condition")
        if (category.condition == CategoryCondition.Time) { add("start"); add("end") }
    }
    val enabled = LocalSettingsStorageState.current.canEdit
    CategoryItemsEditor(category, uiState, actions, onBack) {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            fields.forEachIndexed { index, field ->
                when (field) {
                    "name" -> SettingsActionItem(name, stringResource(R.string.app_category_name), index, fields.size, "category_name",
                        leading = { if (category.icon == CategoryIcon.None) LauncherIcon(LauncherSymbol.Category) else CategoryIndicator(category.icon) }) { dialog = field }
                    "inclusion" -> SettingsActionItem(stringResource(R.string.category_inclusion), category.inclusion.label(), index, fields.size,
                        "category_inclusion") { dialog = field }
                    "rule" -> SettingsActionItem(stringResource(R.string.category_dynamic_rule), category.dynamicRule.label(), index, fields.size,
                        "category_dynamic_rule") { dialog = field }
                    "icon" -> SettingsActionItem(stringResource(R.string.category_icon), category.icon.label(), index, fields.size,
                        "category_icon", trailing = { CategoryIndicator(category.icon) }) { dialog = field }
                    "placement" -> SettingsActionItem(stringResource(R.string.category_placement), category.placement.label(), index, fields.size,
                        "category_placement") { dialog = field }
                    "condition" -> SettingsActionItem(stringResource(R.string.app_category_condition), category.condition.label(), index, fields.size,
                        "category_condition") { dialog = field }
                    "start", "end" -> SettingsActionItem(stringResource(if (field == "start") R.string.app_category_start else R.string.app_category_end),
                        formatTime(if (field == "start") category.startMinute else category.endMinute), index, fields.size,
                        "category_$field") { dialog = field }
                    "levels", "limit" -> SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index, fields.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                        modifier = Modifier.testTag("category_$field"), content = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (field == "levels") {
                                    Text(stringResource(R.string.category_notification_levels))
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                                        CategoryNotificationLevel.entries.forEachIndexed { buttonIndex, level ->
                                            ToggleButton(checked = level in category.notificationLevels, enabled = enabled,
                                                onCheckedChange = { checked -> actions.updateAppCategory(category.id) {
                                                    it.copy(notificationLevels = if (checked) it.notificationLevels + level else it.notificationLevels - level)
                                                } }, shapes = when (buttonIndex) {
                                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                                    CategoryNotificationLevel.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                                }, modifier = Modifier.weight(1f).fillMaxHeight().testTag("category_notification_level:${level.name}"),
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)) { Text(level.label()) }
                                        }
                                    }
                                } else {
                                    Text(stringResource(R.string.category_app_count, category.appLimit))
                                    Slider(category.appLimit.toFloat(), onValueChange = { value ->
                                        actions.updateAppCategory(category.id) { it.copy(appLimit = value.toInt().coerceIn(1, 30)) }
                                    }, enabled = enabled, valueRange = 1f..30f, steps = 28, modifier = Modifier.testTag("category_app_limit"))
                                }
                            }
                        })
                }
            }
            if (!category.isBuiltIn) TextButton(onClick = { dialog = "delete" },
                enabled = enabled, modifier = Modifier.testTag("category_delete")) {
                Text(stringResource(R.string.app_category_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    when (dialog) {
        "name" -> TextEntryDialog(stringResource(R.string.app_category_name), name, { dialog = null }, { value ->
            actions.updateAppCategory(category.id) { it.copy(name = value) }; dialog = null
        })
        "inclusion" -> SettingsSelectionDialog(stringResource(R.string.category_inclusion), CategoryInclusion.entries,
            category.inclusion, "category_inclusion", { it.name }, { it.label() }, { value ->
                actions.updateAppCategory(category.id) { it.copy(inclusion = value) }; dialog = null
            }, { dialog = null })
        "rule" -> SettingsSelectionDialog(stringResource(R.string.category_dynamic_rule), CategoryDynamicRule.entries,
            category.dynamicRule, "category_rule", { it.name }, { it.label() }, { value ->
                actions.updateAppCategory(category.id) { it.copy(dynamicRule = value) }; dialog = null
            }, { dialog = null })
        "placement" -> SettingsSelectionDialog(stringResource(R.string.category_placement), CategoryPlacement.entries,
            category.placement, "category_placement", { it.name }, { it.label() }, { value ->
                actions.updateAppCategory(category.id) { it.copy(placement = value).normalized() }; dialog = null
            }, { dialog = null })
        "icon" -> SettingsSelectionDialog(stringResource(R.string.category_icon),
            CategoryIcon.entries.filter { category.placement != CategoryPlacement.AppList || it != CategoryIcon.None },
            category.icon, "category_icon", { it.name }, { it.label() }, { value ->
                actions.updateAppCategory(category.id) { it.copy(icon = value) }; dialog = null
            }, { dialog = null }, itemLeading = { CategoryIndicator(it) })
        "condition" -> SettingsSelectionDialog(stringResource(R.string.app_category_condition), CategoryCondition.entries,
            category.condition, "category_condition", { it.name }, { it.label() }, { value ->
                actions.updateAppCategory(category.id) { it.copy(condition = value) }; dialog = null
            }, { dialog = null })
        "start", "end" -> {
            val start = dialog == "start"
            val value = if (start) category.startMinute else category.endMinute
            val picker = rememberTimePickerState(value / 60, value % 60, DateFormat.is24HourFormat(context))
            AlertDialog(onDismissRequest = { dialog = null }, title = {
                Text(stringResource(if (start) R.string.app_category_start else R.string.app_category_end))
            }, text = { TimePicker(picker) }, confirmButton = {
                TextButton(onClick = {
                    val minute = picker.hour * 60 + picker.minute
                    actions.updateAppCategory(category.id) { if (start) it.copy(startMinute = minute) else it.copy(endMinute = minute) }
                    dialog = null
                }) { Text(stringResource(R.string.done)) }
            }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } })
        }
        "delete" -> if (!category.isBuiltIn) AlertDialog(onDismissRequest = { dialog = null }, title = { Text(stringResource(R.string.app_category_delete)) },
            text = { Text(stringResource(R.string.app_category_delete_confirmation, name)) }, confirmButton = {
                TextButton(onClick = { actions.deleteAppCategory(category.id); dialog = null; onBack() }) {
                    Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error)
                }
            }, dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } })
    }
}

@Composable
private fun CategoryInclusion.label() = stringResource(when (this) {
    CategoryInclusion.Manual -> R.string.category_manual
    CategoryInclusion.Dynamic -> R.string.category_dynamic
})

@Composable
private fun CategoryDynamicRule.label() = stringResource(when (this) {
    CategoryDynamicRule.Notifications -> R.string.category_rule_notifications
    CategoryDynamicRule.RecentlyInstalled -> R.string.recently_installed
    CategoryDynamicRule.FrequentlyOpened -> R.string.category_rule_frequent
})

@Composable
private fun CategoryPlacement.label() = stringResource(when (this) {
    CategoryPlacement.None -> R.string.settings_none
    CategoryPlacement.Home -> R.string.category_home
    CategoryPlacement.AppList -> R.string.settings_folder_app_list
})

@Composable
private fun CategoryNotificationLevel.label() = stringResource(when (this) {
    CategoryNotificationLevel.Silent -> R.string.category_notification_silent
    CategoryNotificationLevel.Normal -> R.string.category_notification_normal
    CategoryNotificationLevel.High -> R.string.category_notification_high
})

@Composable
private fun CategoryIcon.label() = stringResource(when (this) {
    CategoryIcon.None -> R.string.settings_none
    CategoryIcon.MusicNote -> R.string.category_icon_music
    CategoryIcon.Keep -> R.string.category_icon_keep
    CategoryIcon.NotificationsUnread -> R.string.category_icon_notifications
    CategoryIcon.Circle -> R.string.category_icon_circle
    CategoryIcon.Info -> R.string.category_icon_info
    CategoryIcon.Schedule -> R.string.category_icon_schedule
    CategoryIcon.ClearDay -> R.string.category_icon_day
    CategoryIcon.Bedtime -> R.string.category_icon_bedtime
})
