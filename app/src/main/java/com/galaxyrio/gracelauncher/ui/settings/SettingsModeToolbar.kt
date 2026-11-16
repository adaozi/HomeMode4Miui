@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared editor mode switch; the host owns its position and bottom inset. */
@Composable
internal fun SettingsModeToolbar(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    tag: String,
    itemTags: List<String>,
    modifier: Modifier = Modifier,
    itemWidth: Dp = 88.dp,
    onSelect: (Int) -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(toolbarContainerColor = MaterialTheme.colorScheme.surfaceBright),
        expandedShadowElevation = 3.dp,
        modifier = modifier.testTag(tag).selectableGroup(),
    ) {
        val indicator by animateDpAsState(itemWidth * selectedIndex, MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "settingsModeToolbarIndicator")
        Box {
            Box(Modifier.offset(x = indicator).size(itemWidth, 48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
            Row {
                labels.forEachIndexed { index, label ->
                    val isSelected = index == selectedIndex
                    TextButton(onClick = { onSelect(index) }, enabled = enabled,
                        modifier = Modifier.width(itemWidth).heightIn(min = 48.dp).testTag(itemTags[index])
                            .semantics { selected = isSelected; role = Role.Tab },
                        colors = ButtonDefaults.textButtonColors(containerColor = Color.Transparent,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
