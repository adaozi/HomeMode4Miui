@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.content.ComponentName
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import com.galaxyrio.gracelauncher.data.icons.ItemIconStore
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSearchBar
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.overlays.IconPackGrid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reuse desktop icon-pack browsing, but return a draft source without changing stored icons. */
@Composable
internal fun IconDesignerSourceSettings(uiState: LauncherUiState, repository: IconPackRepository,
    store: ItemIconStore, component: ComponentName?, onSelect: (ItemIcon) -> Unit, onImport: (ItemIcon) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pack by rememberSaveable { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val query = rememberTextFieldState()
    val icons by produceState<Pair<List<String>, String?>?>(null, pack, component) {
        value = null
        pack?.let { packageName ->
            value = repository.iconNames(packageName) to component?.let { repository.matchingIconName(packageName, it) }
        }
    }
    val back = { if (!importing) { if (pack != null) { pack = null; query.edit { replace(0, length, "") } } else onBack() }; Unit }
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            var imported: ItemIcon? = null
            var retained = false
            try {
                imported = store.importImage(uri)
                onImport(imported); retained = true; onSelect(imported)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, R.string.icon_edit_failed, Toast.LENGTH_LONG).show()
            } finally {
                if (!retained) withContext(NonCancellable) { store.deleteImage(imported) }
                importing = false
            }
        }
    }
    SettingsScaffold(uiState.iconPacks.firstOrNull { it.packageName == pack }?.label ?: stringResource(R.string.icon_designer_source),
        "icon_designer_source_picker", back, fixedCollapsed = true) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (pack == null) SettingsList(PaddingValues(0.dp)) {
                item { SettingsActionItem(stringResource(R.string.icon_edit_follow_theme), null, 0, 3, "icon_designer_source_theme",
                    enabled = !importing) { onSelect(ItemIcon.Theme) } }
                item { SettingsActionItem(stringResource(R.string.icon_pack_system), null, 1, 3, "icon_designer_source_system",
                    enabled = !importing) { onSelect(ItemIcon.System) } }
                item { SettingsActionItem(stringResource(R.string.icon_edit_image), null, 2, 3, "icon_designer_source_image",
                    enabled = !importing, leading = { LauncherIcon(LauncherSymbol.Plus) }) { picker.launch("image/*") } }
                item { SettingsHeading(stringResource(R.string.settings_icon_pack)) }
                itemsIndexed(uiState.iconPacks, key = { _, item -> item.packageName }) { index, item ->
                    SettingsActionItem(item.label, null, index, uiState.iconPacks.size, "icon_designer_source_pack:${item.packageName}",
                        enabled = !importing, leading = {
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                if (item.icon != null) Image(item.icon, null, Modifier.fillMaxSize()) else LauncherIcon(LauncherSymbol.Palette)
                            }
                        }) { pack = item.packageName }
                }
            } else {
                LauncherSearchBar(query, stringResource(R.string.icon_edit_search), "icon_designer_source_query",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                val available = icons
                if (available == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else IconPackGrid(pack!!, available.first, query.text.toString(), repository, !importing, onSelect,
                    Modifier.weight(1f), matchedName = available.second)
            }
        }
    }
}
