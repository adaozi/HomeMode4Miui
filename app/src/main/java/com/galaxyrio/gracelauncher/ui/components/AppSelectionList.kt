package com.galaxyrio.gracelauncher.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.settings.LocalSettingsBottomInset
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.LauncherAppOrder
import com.galaxyrio.gracelauncher.data.appSortKey

/** One scrolling surface: optional editor content, search, then the app-only fast-scroll range. */
@Composable
internal fun AppSelectionList(
    apps: List<LauncherApp>,
    selectedKeys: Set<String>,
    query: TextFieldState,
    onSelect: (LauncherApp) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    listTag: String = "settings_list",
    searchTag: String = "settings_app_search",
    itemTagPrefix: String = "app_choice",
    singleChoice: Boolean = false,
    enabled: Boolean = true,
    lockedKeys: Set<String> = emptySet(),
    lockedLabel: String? = null,
    userScrollEnabled: Boolean = true,
    showApps: Boolean = true,
    emptyContent: @Composable () -> Unit = {
        Text(stringResource(R.string.search_no_results), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    },
    beforeApps: LazyListScope.() -> Unit = {},
) {
    val term = query.text.toString().trim()
    val filtered = remember(apps, term) {
        val sortTerm = appSortKey(term)
        apps.filter { term.isEmpty() || it.label.contains(term, true) || it.originalLabel.contains(term, true) ||
            it.packageName.contains(term, true) || it.sortKey.contains(sortTerm, true) }.sortedWith(LauncherAppOrder)
    }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag(listTag).then(if (singleChoice) Modifier.selectableGroup() else Modifier), state = state,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp + LocalSettingsBottomInset.current),
            userScrollEnabled = userScrollEnabled,
        ) {
            beforeApps()
            if (showApps) {
                item(key = "app_selection_search", contentType = "search") {
                    LauncherSearchBar(query, stringResource(R.string.search_apps), searchTag, Modifier.padding(bottom = 12.dp), enabled = enabled)
                }
                if (filtered.isEmpty()) item(key = "app_selection_empty") { Column { emptyContent() } }
                // Keep this range at the end: the scrollbar deliberately excludes everything above it.
                items(filtered, key = { "all:${it.key}" }, contentType = { "app" }) { app ->
                    AppSelectionRow(
                        label = app.label, selected = app.key in selectedKeys, onClick = { onSelect(app) },
                        modifier = Modifier.animateItem().testTag("$itemTagPrefix:${app.key}"),
                        enabled = enabled && app.key !in lockedKeys,
                        annotation = lockedLabel.takeIf { app.key in lockedKeys },
                        singleChoice = singleChoice, icon = { AppIcon(app, size = 36.dp) },
                    )
                }
            }
        }
        if (showApps) AppSelectionScrollbar(
            state, filtered, enabled = userScrollEnabled,
            modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = LocalSettingsBottomInset.current), tag = "${listTag}_scrollbar",
        )
    }
}

/** The favorites row is also used for hidden apps, single-choice targets and editor contents. */
@Composable
internal fun AppSelectionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleChoice: Boolean = false,
    selectable: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    annotation: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp))
            .then(when {
                !selectable -> Modifier
                singleChoice -> Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                else -> Modifier.toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onClick() })
            }).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            if (singleChoice) RadioButton(selected, null, Modifier.size(24.dp), enabled = enabled)
            else Checkbox(selected, null, Modifier.size(24.dp), enabled = enabled)
            Spacer(Modifier.width(16.dp))
        }
        if (icon != null) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(16.dp))
        }
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f, fill = annotation == null), maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f))
            if (annotation != null) Text(annotation, Modifier.widthIn(max = 120.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
