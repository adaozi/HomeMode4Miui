@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.licenses.LibraryLicense
import com.galaxyrio.gracelauncher.data.licenses.LicensesRepository
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import kotlinx.coroutines.CancellationException

private val LicenseLeadingColumnWidth = 52.dp
private val LicenseColumnSpacing = 16.dp

@Composable
internal fun LicenseSettings(onBack: () -> Unit, navigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { LicensesRepository(context) }
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<LibraryLicense>>?>(null, repository, attempt) {
        value = null
        value = try {
            Result.success(repository.getLibraries())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    SettingsScaffold(stringResource(R.string.settings_licenses), "settings_licenses", onBack) { padding ->
        val loaded = result
        when {
            loaded == null -> LicenseStatus(padding) {
                CircularProgressIndicator()
                Text(stringResource(R.string.settings_license_loading))
            }
            loaded.isFailure -> LicenseStatus(padding) {
                LauncherIcon(LauncherSymbol.Info, Modifier.size(40.dp), MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.licenses_load_failed), textAlign = TextAlign.Center)
                TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.licenses_retry)) }
            }
            else -> LicenseList(loaded.getOrThrow(), padding, navigate)
        }
    }
}

@Composable
private fun LicenseStatus(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        content = content,
    )
}

/** Layout adapted from Sudoku's LicensesScreen: summary, search, expandable segments. */
@Composable
private fun LicenseList(libraries: List<LibraryLicense>, padding: PaddingValues, navigate: (SettingsPage) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(libraries, query) { libraries.filter { it.matches(query) } }
    SettingsList(padding) {
        item(key = "app_notices") { AppNoticesCard(navigate) }
        item(key = "intro") {
            Column(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.licenses_third_party), style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ms_account_balance), null, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        pluralStringResource(R.plurals.licenses_count, libraries.size, libraries.size),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("licenses_count"),
                    )
                }
            }
        }
        item(key = "search") {
            LicenseSearchBar(query, { query = it; expandedId = null }, Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        if (filtered.isEmpty()) item(key = "empty") {
            Column(
                Modifier.fillMaxWidth().padding(32.dp).testTag("licenses_empty"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LauncherIcon(LauncherSymbol.Search, Modifier.size(40.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.licenses_no_results), textAlign = TextAlign.Center)
            }
        }
        itemsIndexed(filtered, key = { _, library -> library.id }) { index, library ->
            LibraryLicenseItem(library, expandedId == library.id, index, filtered.size) {
                expandedId = if (expandedId == library.id) null else library.id
            }
        }
    }
}

@Composable
private fun AppNoticesCard(navigate: (SettingsPage) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 24.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    modifier = Modifier.size(52.dp), shape = CircleShape,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, contentColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.padding(4.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.licenses_app_license), Modifier.padding(top = 2.dp).testTag("app_license_gpl3"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                }
            }
            Text(
                stringResource(R.string.licenses_app_summary), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            TextButton(
                onClick = { navigate(SettingsPage.AppLicense) },
                modifier = Modifier.align(Alignment.End).testTag("settings_app_license"),
            ) { Text(stringResource(R.string.licenses_full_text)) }
        }
    }
}

@Composable
private fun LicenseSearchBar(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    val focus = LocalFocusManager.current
    TextField(
        value = query, onValueChange = onQueryChange, modifier = modifier.testTag("licenses_search"),
        placeholder = { Text(stringResource(R.string.licenses_search)) },
        leadingIcon = { LauncherIcon(LauncherSymbol.Search) },
        trailingIcon = if (query.isEmpty()) null else ({
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("licenses_clear_search")) {
                Icon(painterResource(R.drawable.ms_close), stringResource(R.string.licenses_clear_search))
            }
        }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        shape = SearchBarDefaults.inputFieldShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun LibraryLicenseItem(library: LibraryLicense, expanded: Boolean, index: Int, count: Int, onToggle: () -> Unit) {
    val expandedState = stringResource(if (expanded) R.string.licenses_expanded else R.string.licenses_collapsed)
    SegmentedListItem(
        onClick = onToggle,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        modifier = Modifier.fillMaxWidth().testTag("license:${library.id}").semantics { stateDescription = expandedState },
        content = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(LicenseLeadingColumnWidth), shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(library.name.firstOrNull()?.uppercase() ?: "?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.width(LicenseColumnSpacing))
                    Column(Modifier.weight(1f)) {
                        Text(library.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            library.version ?: stringResource(R.string.licenses_unknown_version), Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LauncherIcon(LauncherSymbol.Chevron, Modifier.rotate(if (expanded) 180f else 0f), MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(expandFrom = Alignment.Top), exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)) {
                    LibraryLicenseDetails(library, Modifier.fillMaxWidth().padding(top = 14.dp).testTag("license_details:${library.id}"))
                }
            }
        },
    )
}

@Composable
private fun LibraryLicenseDetails(library: LibraryLicense, modifier: Modifier) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val openUri: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.licenses_link_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
    val artifact = library.artifactId.trimEnd(':')
    val coordinates = library.version?.takeUnless { artifact.endsWith(":$it") }?.let { "$artifact:$it" } ?: artifact
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LicenseMetadataRow(R.drawable.ms_code, stringResource(R.string.licenses_artifact_id), coordinates)
        LicenseMetadataRow(R.drawable.ms_person, stringResource(R.string.licenses_developer), library.developers.joinToString().ifBlank { stringResource(R.string.licenses_unknown_developer) })
        LicenseMetadataRow(R.drawable.ms_account_balance, stringResource(R.string.licenses_license), library.licenses.joinToString { it.name }.ifBlank { stringResource(R.string.licenses_unknown) })
        library.website?.let { website ->
            TextButton(onClick = { openUri(website) }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)) {
                Box(Modifier.width(LicenseLeadingColumnWidth), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ms_link), null, Modifier.size(22.dp))
                }
                Spacer(Modifier.width(LicenseColumnSpacing))
                Text(website, Modifier.weight(1f), overflow = TextOverflow.Ellipsis, maxLines = 2, textAlign = TextAlign.Start)
            }
        }
        library.licenses.forEach { license ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { license.url?.let(openUri) }, enabled = license.url != null) {
                    Text(if (library.licenses.size == 1) stringResource(R.string.licenses_full_text) else stringResource(R.string.licenses_named_full_text, license.name))
                }
            }
        }
    }
}

@Composable
private fun LicenseMetadataRow(icon: Int, label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(LicenseLeadingColumnWidth), contentAlignment = Alignment.TopCenter) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(LicenseColumnSpacing))
        Row(Modifier.weight(1f)) {
            Text("$label:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
