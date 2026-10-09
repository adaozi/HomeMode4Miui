package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.ItemIcon
import com.galaxyrio.gracelauncher.data.sectionForLabel
import com.galaxyrio.gracelauncher.data.icons.IconPackRepository
import com.galaxyrio.gracelauncher.ui.components.AlphabetRail

@Composable
internal fun IconPackGrid(
    packageName: String,
    names: List<String>,
    query: String,
    repository: IconPackRepository,
    enabled: Boolean,
    onSelect: (ItemIcon) -> Unit,
    modifier: Modifier = Modifier,
    matchedName: String? = null,
) {
    val grouped = remember(names, matchedName) {
        names.filterNot { it == matchedName }.groupBy(::sectionForLabel)
            .toSortedMap(compareBy<String> { it == "#" }.thenBy { it })
            .mapValues { (_, icons) -> icons.sortedWith(String.CASE_INSENSITIVE_ORDER) }
    }
    val term = query.trim().replace('_', ' ')
    val matchedIcon = matchedName?.takeIf { term.isEmpty() || it.replace('_', ' ').contains(term, true) }
    val sections = remember(grouped, term) {
        grouped.mapValues { (_, icons) -> if (term.isEmpty()) icons else icons.filter { it.replace('_', ' ').contains(term, true) } }
            .filterValues { it.isNotEmpty() }
    }
    val letters = remember(sections) { sections.keys.toList() }
    // Include the app match and full-span headings so letter jumps remain correct at every grid width.
    val sectionIndices = remember(sections, matchedIcon) {
        buildMap {
            var index = if (matchedIcon == null) 0 else 1
            sections.forEach { (letter, icons) -> put(letter, index); index += icons.size + 1 }
        }
    }
    val grid = rememberLazyGridState()
    val selectedLetter by remember(grid, sectionIndices) {
        derivedStateOf { sectionIndices.entries.lastOrNull { it.value <= grid.firstVisibleItemIndex }?.key }
    }
    LaunchedEffect(packageName, sections, matchedIcon) { grid.scrollToItem(0) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (sections.isEmpty() && matchedIcon == null) {
            Text(stringResource(R.string.icon_edit_no_results), Modifier.padding(24.dp))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(88.dp), state = grid,
                modifier = Modifier.fillMaxSize().padding(end = 48.dp).testTag("icon_pack_grid"),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (matchedIcon != null) item(key = "matched:$matchedIcon", contentType = "icon") {
                    IconPackGridItem(packageName, matchedIcon, repository, enabled, onSelect,
                        Modifier.testTag("icon_pack_match:$matchedIcon"))
                }
                sections.forEach { (letter, icons) ->
                    item(key = "section:$letter", span = { GridItemSpan(maxLineSpan) }, contentType = "heading") {
                        Text(letter, Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    items(icons, key = { "icon:$it" }, contentType = { "icon" }) { name ->
                        IconPackGridItem(packageName, name, repository, enabled, onSelect)
                    }
                }
            }
            AlphabetRail(
                letters = letters, selectedLetter = selectedLetter,
                height = minOf(maxHeight, 20.dp * letters.size),
                onLetterSelected = { letter -> sectionIndices[letter]?.let { grid.requestScrollToItem(it) } },
                modifier = Modifier.align(Alignment.CenterEnd), includeHome = false, onWallpaper = false,
            )
        }
    }
}

@Composable
private fun IconPackGridItem(packageName: String, name: String, repository: IconPackRepository,
    enabled: Boolean, onSelect: (ItemIcon) -> Unit, modifier: Modifier = Modifier) {
    val choice = ItemIcon("pack", packageName, name)
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, choice) {
        value = repository.selectedIcon(choice)?.bitmap
    }
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled && icon != null, role = Role.Button) { onSelect(choice) }
            .semantics { contentDescription = name.replace('_', ' ') }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
        Text(name.replace('_', ' '), Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall)
    }
}
