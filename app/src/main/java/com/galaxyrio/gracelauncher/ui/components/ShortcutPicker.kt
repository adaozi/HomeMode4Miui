@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.ShortcutStatus
import com.galaxyrio.gracelauncher.ui.LauncherActions
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.settings.SettingsScaffold
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private data class ShortcutGroup(val app: LauncherApp, val shortcuts: List<LauncherApp>, val status: ShortcutStatus)

/** Select any exported launcher shortcut, independently of its app-list visibility. */
@Composable
internal fun ShortcutPickerScreen(uiState: LauncherUiState, actions: LauncherActions,
    selectedKeys: Set<String>, onBack: () -> Unit, onSelect: (LauncherApp) -> Unit,
    singleChoice: Boolean = false, busy: Boolean = false) {
    BackHandler(onBack = onBack)
    val query = rememberTextFieldState()
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var retry by remember { mutableIntStateOf(0) }
    val apps = remember(uiState.allApps) { uiState.allApps.filter { it.shortcut == null && it.folderId == null && !it.isLauncherSettings } }
    // Saving a selection refreshes decorated icons, not the provider inventory.
    // Do not replace the expanded list with a loader after each checkbox tap.
    val appKeys = apps.map(LauncherApp::key)
    val load by rememberUpdatedState(actions.shortcuts)
    val groups by produceState<List<ShortcutGroup>?>(null, appKeys, uiState.hasShortcutAccess, retry) {
        value = null
        value = if (!uiState.hasShortcutAccess) emptyList() else coroutineScope {
            apps.map { app -> async {
                val result = load(app)
                ShortcutGroup(app, result.shortcuts.map { it.asApp(app) }, result.status)
            } }.awaitAll()
        }
    }
    val term = query.text.toString().trim()
    val visible = groups.orEmpty().filter { group -> group.shortcuts.isNotEmpty() &&
        (term.isEmpty() || group.app.label.contains(term, true) || group.app.packageName.contains(term, true) || group.shortcuts.any { it.label.contains(term, true) }) }
    val loading = groups == null || uiState.isLoadingApps
    val loadFailed = uiState.appLoadFailed || groups.orEmpty().any { it.status != ShortcutStatus.Ready }
    SettingsScaffold(stringResource(R.string.shortcuts_title), "shortcut_picker", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            LauncherSearchBar(query, stringResource(R.string.shortcuts_search), "shortcut_query",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), enabled = !busy)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    !uiState.hasShortcutAccess -> item {
                        Text(stringResource(R.string.shortcut_permission), Modifier.padding(8.dp))
                        TextButton(onClick = actions.requestDefaultHome) { Text(stringResource(R.string.set_default_launcher)) }
                    }
                    loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    visible.isEmpty() && !loadFailed -> item { Text(stringResource(R.string.no_shortcuts), Modifier.padding(8.dp)) }
                }
                visible.forEach { group ->
                    val owner = apps.firstOrNull { it.key == group.app.key } ?: group.app
                    val open = group.app.key in expanded
                    item(key = "app:${group.app.key}") {
                        val angle by animateFloatAsState(if (open) 180f else 0f, label = "shortcutGroupArrow")
                        val state = stringResource(if (open) R.string.widget_group_expanded else R.string.widget_group_collapsed)
                        ListItem(onClick = { expanded = if (open) expanded - group.app.key else expanded + group.app.key }, enabled = !busy,
                            modifier = Modifier.animateItem().testTag("shortcut_group:${group.app.key}").semantics { stateDescription = state },
                            leadingContent = { AppIcon(owner, size = 40.dp) },
                            content = { Text(owner.label) },
                            trailingContent = { Icon(painterResource(R.drawable.ms_expand_more), null, Modifier.rotate(angle)) },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer))
                    }
                    if (open) items(group.shortcuts, key = { "choice:${it.key}" }) { shortcut ->
                        val app = uiState.findItem(shortcut.key) ?: shortcut
                        AppSelectionRow(app.label, app.key in selectedKeys, { onSelect(app) },
                            modifier = Modifier.animateItem().padding(start = 16.dp).testTag("shortcut_choice:${app.key}"),
                            enabled = !busy, singleChoice = singleChoice,
                            icon = { AppIcon(app, size = 36.dp) })
                    }
                }
                if (uiState.hasShortcutAccess && !loading && loadFailed) item(key = "shortcuts_load_error") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                            .testTag("shortcuts_load_error"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.shortcuts_load_partial), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            if (uiState.appLoadFailed) actions.refreshApps()
                            retry++
                        }, enabled = !busy, modifier = Modifier.testTag("shortcuts_retry")) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }
    }
}
