/*
 * 移植自 GraceLauncher v1.1.4（GPL-3.0，https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/overlays/LauncherSheets.kt
 *   尾部三个对话框（TextEntryDialog / FolderPlacementDialog / DeleteFolderDialog）。
 * C 组（C1）搬运：本仓 HomeSheets.kt 为 P4 裁剪版（无弹层对话框段），设置页
 *   ProductivitySettings / PrivateSpaceSettings 消费这三个组件，故拆出独立文件。
 */
package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.FolderPlacement

@Composable
internal fun TextEntryDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onReset: (() -> Unit)? = null) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(40.dp),
        title = { Text(title) },
        text = { OutlinedTextField(value, onValueChange = { value = it.take(80) }, singleLine = true, modifier = Modifier.testTag("text_entry")) },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (onReset != null) TextButton(onClick = onReset) { Text(stringResource(R.string.reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
internal fun FolderPlacementDialog(selected: FolderPlacement, appListAtBottom: Boolean,
    onDismiss: () -> Unit, onSelect: (FolderPlacement, Boolean) -> Unit) {
    var placement by remember { mutableStateOf(selected) }
    var atBottom by remember { mutableStateOf(appListAtBottom) }
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(40.dp), title = { Text(stringResource(R.string.settings_folder_placement)) },
        text = { Column {
            FolderPlacementChoice(stringResource(R.string.settings_folder_favorites), placement.inFavorites,
                Modifier.testTag("folder_placement:Favorites")) { placement = placement.withFavorites(it) }
            FolderPlacementChoice(stringResource(R.string.settings_folder_app_list), placement.inAppList,
                Modifier.testTag("folder_placement:AppList")) { placement = placement.withAppList(it) }
            AnimatedVisibility(placement.inAppList) {
                FolderPlacementChoice(stringResource(R.string.settings_folder_at_bottom), atBottom,
                    Modifier.padding(start = 40.dp).testTag("folder_placement:at_bottom")) { atBottom = it }
            }
        } },
        confirmButton = { TextButton(onClick = { onSelect(placement, atBottom) }, modifier = Modifier.testTag("folder_placement_done")) {
            Text(stringResource(R.string.done))
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun FolderPlacementChoice(title: String, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(16.dp))
        Text(title)
    }
}

@Composable
internal fun DeleteFolderDialog(name: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, shape = RoundedCornerShape(40.dp), title = { Text(stringResource(R.string.settings_folder_delete)) },
        text = { Text(stringResource(R.string.settings_folder_delete_confirmation, name)) },
        confirmButton = { TextButton(onClick = onDelete, modifier = Modifier.testTag("folder_confirm_delete")) {
            Text(stringResource(R.string.settings_delete), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
