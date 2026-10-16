@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AboutSettings(onBack: () -> Unit, navigate: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    SettingsScaffold(stringResource(R.string.settings_about), "settings_about", onBack) { padding ->
        SettingsList(padding) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AboutAppIcon()
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.settings_about_description), style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
                        Text(stringResource(R.string.settings_version, version), Modifier.padding(horizontal = 14.dp, vertical = 7.dp), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item { SettingsHeading(stringResource(R.string.settings_about_app)) }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_changelog), stringResource(R.string.settings_changelog_summary),
                    0, 2, "settings_open_changelog", leading = { LauncherIcon(LauncherSymbol.Hourglass) },
                ) { navigate(SettingsPage.Changelog) }
            }
            item {
                SettingsActionItem(
                    stringResource(R.string.settings_licenses), stringResource(R.string.settings_licenses_summary),
                    1, 2, "settings_open_licenses", leading = { LauncherIcon(LauncherSymbol.Info) },
                ) { navigate(SettingsPage.Licenses) }
            }
        }
    }
}

@Composable
private fun AboutAppIcon() {
    val size = 92.dp
    val containerSize = size * (LoadingIndicatorDefaults.ContainerWidth.value / LoadingIndicatorDefaults.IndicatorSize.value)
    val shapes = remember { listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Sunny, MaterialShapes.SoftBurst) }
    Box(Modifier.size(containerSize).testTag("about_logo_container").clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        LoadingIndicator(color = MaterialTheme.colorScheme.primaryContainer, polygons = shapes, modifier = Modifier.fillMaxSize())
        Icon(
            painterResource(R.drawable.ic_grace_solid), contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size * 0.72f).testTag("about_logo_g"),
        )
    }
}

@Composable
internal fun ChangelogSettings(onBack: () -> Unit) {
    // Release notes are authored by the maintainer, not inferred from implemented features.
    SettingsScaffold(stringResource(R.string.settings_changelog), "settings_changelog", onBack) { }
}

@Composable
internal fun AppLicenseSettings(onBack: () -> Unit) {
    val context = LocalContext.current
    val unavailable = stringResource(R.string.settings_license_unavailable)
    val text by produceState<String?>(null, context, unavailable) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
            }.getOrDefault(unavailable)
        }
    }
    // Reflow the plain-text file's fixed-width line wraps for a phone, without
    // changing its wording or the verbatim license bundled in the APK.
    val loadedParagraphs = remember(text) {
        text?.split("\n\n")?.map { paragraph -> paragraph.lineSequence().joinToString(" ") { it.trim() }.trim() }
    }
    val paragraphs = loadedParagraphs ?: listOf(stringResource(R.string.settings_license_loading))
    SettingsScaffold(stringResource(R.string.licenses_app_license), "settings_license_text", onBack) { padding ->
        SettingsList(padding) {
            // Paragraph-sized lazy items keep the long legal text selectable and
            // readable without creating one enormous Text layout.
            items(paragraphs.size) { index ->
              androidx.compose.foundation.text.selection.SelectionContainer {
                Text(
                    paragraphs[index],
                    Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                )
              }
            }
        }
    }
}
