@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.LauncherApp
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.IconDesign
import com.galaxyrio.gracelauncher.data.icons.IconLayers
import com.galaxyrio.gracelauncher.data.icons.ItemIconStore
import com.galaxyrio.gracelauncher.ui.LauncherUiState
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherTypography
import com.galaxyrio.gracelauncher.ui.theme.rememberLauncherAppearance

@Composable
internal fun IconDesignerPreview(
    uiState: LauncherUiState, selectedApp: LauncherApp?, choice: ItemIcon, design: IconDesign, layers: IconLayers?,
    store: ItemIconStore, dynamicColors: Pair<Int, Int>, themeColors: Pair<Int, Int>, all: Boolean, enabled: Boolean, onChooseApp: () -> Unit,
    onChange: (IconDesign) -> Unit,
    modifier: Modifier = Modifier,
) {
    val appearance = rememberLauncherAppearance(uiState.textMode, uiState.themedIcons)
    val shape = ListItemDefaults.segmentedShapes(0, 1).shape
    Box(modifier.fillMaxWidth().testTag("icon_designer_preview").drawWithContent {
        // Match Clock style's window onto the real wallpaper, including live wallpapers.
        drawOutline(shape.createOutline(size, layoutDirection, this), Color.Transparent, blendMode = BlendMode.Clear)
        drawContent()
    }, contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalLauncherAppearance provides appearance) {
            MaterialTheme(typography = LocalLauncherTypography.current) {
                if (all) AllIconsPreview(uiState, choice, store, dynamicColors, themeColors)
                else if (selectedApp == null) {
                    val description = stringResource(R.string.icon_designer_choose_app)
                    Box(
                        Modifier.size(80.dp).testTag("icon_designer_choose_app").clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .clickable(role = Role.Button, onClick = onChooseApp).semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) {
                        LauncherIcon(LauncherSymbol.Plus, Modifier.size(32.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                } else DesignerPreviewIcon(selectedApp, layers, design, dynamicColors, 160.dp * (design.iconSize / 100f),
                    Modifier.testTag("icon_designer_selected_app"), draggable = enabled, onChange = onChange, themeColors = themeColors)
            }
        }
    }
}

/** One enabled-pack match, one unmatched adaptive icon, and one unmatched legacy icon. */
internal fun iconDesignerPreviewRows(apps: List<LauncherApp>): List<List<LauncherApp>> {
    val activities = apps.filter { it.shortcut == null && it.folderId == null }
    return listOf(
        activities.filter { it.themeIconPackPackage != null }.sortedByDescending { it.isSystemApp }.take(1),
        activities.filter { it.themeIconPackPackage == null && it.isAdaptiveIcon }.take(1),
        activities.filter { it.themeIconPackPackage == null && !it.isAdaptiveIcon }.take(1),
    )
}

@Composable
private fun AllIconsPreview(uiState: LauncherUiState, choice: ItemIcon, store: ItemIconStore, dynamicColors: Pair<Int, Int>, themeColors: Pair<Int, Int>) {
    val rows = remember(uiState.allApps) { iconDesignerPreviewRows(uiState.allApps) }
    val categories = listOf(stringResource(R.string.icon_designer_pack_apps), stringResource(R.string.icon_designer_adaptive_apps),
        stringResource(R.string.icon_designer_legacy_apps))
    val iconSize = 40.dp * ((choice.design?.iconSize ?: 100) / 100f)
    val appearance = LocalLauncherAppearance.current
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp).testTag("icon_designer_grid"),
        verticalArrangement = Arrangement.Center) {
            rows.forEachIndexed { row, entries ->
                val app = entries.firstOrNull()
                val label = app?.label ?: stringResource(R.string.icon_designer_example)
                Row(Modifier.fillMaxWidth().heightIn(min = LauncherLayout.RowMinHeight).testTag("icon_designer_row:$row")
                    .semantics(mergeDescendants = true) { contentDescription = "${categories[row]} · $label" }
                    .padding(LauncherLayout.ContentInset), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.testTag("icon_designer_icon:$row:0")) {
                        if (app != null) DesignerBulkPreviewIcon(app, uiState, choice, store, dynamicColors, themeColors, iconSize)
                        else DesignerSamplePreviewIcon(row, choice, dynamicColors, themeColors, iconSize)
                    }
                    Spacer(Modifier.width(LauncherLayout.IconLabelGap))
                    Text(label, color = appearance.text,
                        style = MaterialTheme.typography.bodyLarge.merge(TextStyle(fontWeight = FontWeight.Normal,
                            letterSpacing = 0.2.sp, shadow = appearance.textShadow)),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
    }
}
