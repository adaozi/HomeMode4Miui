@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.AppIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol

@Composable
internal fun IconDesignerData(uiState: LauncherUiState, selectedKeys: Set<String>, enabled: Boolean,
    onSelection: (Set<String>) -> Unit, onEdit: (String) -> Unit, modifier: Modifier = Modifier) {
    val entries = remember(uiState.itemIcons, uiState.allApps, uiState.shortcutApps, uiState.folders, uiState.settings.privateSpace.exposesApps) {
        uiState.itemIcons.keys.filterNot { it.startsWith("profile:") && !uiState.settings.privateSpace.exposesApps }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { uiState.findItem(it)?.label ?: it })
    }
    val selecting = selectedKeys.isNotEmpty()
    if (entries.isEmpty()) Box(modifier.fillMaxSize().padding(bottom = 88.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.icon_designer_data_empty), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("icon_designer_data_empty"))
    } else LazyColumn(modifier.fillMaxSize().testTag("icon_designer_data_list"),
        contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        itemsIndexed(entries, key = { _, key -> key }) { index, key ->
            val app = uiState.findItem(key)
            val selected = key in selectedKeys
            val shapes = ListItemDefaults.segmentedShapes(index, entries.size)
            SegmentedListItem(
                modifier = Modifier.clip(shapes.shape).combinedClickable(enabled = enabled,
                    role = if (selecting) Role.Checkbox else Role.Button,
                    onClick = { if (selecting) onSelection(if (selected) selectedKeys - key else selectedKeys + key) else onEdit(key) },
                    onLongClick = { onSelection(selectedKeys + key) },
                    onLongClickLabel = stringResource(R.string.icon_designer_select_data))
                    .semantics {
                        this.selected = selected
                        if (selecting) toggleableState = if (selected) ToggleableState.On else ToggleableState.Off
                    }.testTag("icon_designer_data:$key"),
                shapes = shapes,
                colors = ListItemDefaults.segmentedColors(containerColor = if (selected)
                    MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceBright),
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (selecting) Checkbox(selected, onCheckedChange = null)
                        if (app != null) AppIcon(app, size = 40.dp) else LauncherIcon(LauncherSymbol.Apps, Modifier.size(40.dp))
                    }
                },
                content = { Text(app?.label ?: key.substringBefore('/')) },
                supportingContent = if (app == null) ({ Text(stringResource(R.string.app_unavailable)) }) else null,
            )
        }
    }
}
