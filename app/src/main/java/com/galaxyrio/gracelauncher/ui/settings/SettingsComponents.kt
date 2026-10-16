@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.galaxyrio.gracelauncher.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.homemode4miui.R

internal data class SettingsStorageState(val loading: Boolean = false, val loadFailed: Boolean = false, val saveFailed: Boolean = false) {
    val canEdit: Boolean get() = !loading && !loadFailed
}

internal val LocalSettingsStorageState = staticCompositionLocalOf { SettingsStorageState() }

/** Scrollable content includes this inset; fixed bottom controls stay above it. */
internal val LocalSettingsBottomInset = staticCompositionLocalOf { 0.dp }

@Composable
internal fun SettingsAppearanceAction(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag("settings_appearance")) {
        Icon(painterResource(R.drawable.ms_palette), stringResource(R.string.settings_themes))
    }
}

@Composable
internal fun SettingsScaffold(
    title: String,
    tag: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    fixedCollapsed: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val collapsedOffset = with(LocalDensity.current) {
        (TopAppBarDefaults.LargeAppBarCollapsedHeight -
            TopAppBarDefaults.LargeFlexibleAppBarWithoutSubtitleExpandedHeight).toPx()
    }
    val scrollBehavior = if (fixedCollapsed) {
        TopAppBarDefaults.pinnedScrollBehavior(
            state = rememberTopAppBarState(
                initialHeightOffsetLimit = collapsedOffset,
                initialHeightOffset = collapsedOffset,
            ),
            canScroll = { false },
        )
    } else {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    }
    // Keep the same flexible bar, including its typography and insets. Pin its
    // collapsed state even when font scaling changes the measured height limit.
    LaunchedEffect(fixedCollapsed, scrollBehavior.state.heightOffsetLimit) {
        if (fixedCollapsed) scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
    }
    val storage = LocalSettingsStorageState.current
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(tag).then(
            if (fixedCollapsed) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, Modifier.padding(start = 4.dp)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(painterResource(R.drawable.ms_arrow_back), stringResource(R.string.settings_back))
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            val message = when {
                storage.loading -> R.string.settings_storage_loading
                storage.loadFailed -> R.string.settings_storage_load_error
                storage.saveFailed -> R.string.settings_storage_save_error
                else -> null
            }
            if (message != null) Surface(
                modifier = Modifier.fillMaxWidth(),
                color = if (storage.loading) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (storage.loading) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Text(stringResource(message), Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).testTag("settings_storage_status"), style = MaterialTheme.typography.bodyMedium)
            }
        },
        content = { padding ->
            val direction = LocalLayoutDirection.current
            val navigationBottom = minOf(padding.calculateBottomPadding(),
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
            val fixedBottom = padding.calculateBottomPadding() - navigationBottom
            val viewportPadding = PaddingValues(
                start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction), bottom = fixedBottom,
            )
            // Let lists draw beneath the transparent navigation bar. Keep its safe
            // area inside scrollable content, without duplicating it above the IME.
            val imeOverlap = (WindowInsets.ime.asPaddingValues().calculateBottomPadding() - fixedBottom).coerceAtLeast(0.dp)
            CompositionLocalProvider(LocalSettingsBottomInset provides (navigationBottom - imeOverlap).coerceAtLeast(0.dp)) {
                content(viewportPadding)
            }
        },
    )
}

@Composable
internal fun SettingsAppBarAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun SettingsList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp).testTag("settings_list"),
        contentPadding = PaddingValues(bottom = 24.dp + LocalSettingsBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content,
    )
}

@Composable
internal fun SettingsHeading(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Shared feature master switch; ordinary settings below remain segmented rows. */
@Composable
internal fun SettingsFeatureBanner(title: String, checked: Boolean, tag: String, onCheckedChange: (Boolean) -> Unit) {
    Column(Modifier.padding(vertical = 24.dp)) {
        Surface(
            checked = checked, onCheckedChange = onCheckedChange,
            enabled = LocalSettingsStorageState.current.canEdit,
            modifier = Modifier.fillMaxWidth().testTag(tag), shape = CircleShape,
            color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceBright,
            contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        ) {
            Row(Modifier.heightIn(min = 72.dp).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(16.dp))
                Switch(checked, null, enabled = LocalSettingsStorageState.current.canEdit)
            }
        }
    }
}

@Composable
internal fun SettingsActionItem(
    title: String,
    summary: String?,
    index: Int,
    count: Int,
    tag: String,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled && LocalSettingsStorageState.current.canEdit,
        modifier = modifier.testTag(tag),
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        leadingContent = leading,
        content = { Text(title) },
        supportingContent = if (summary == null) null else ({ Text(summary) }),
        trailingContent = trailing,
    )
}

@Composable
internal fun SettingsToggleItem(
    title: String,
    summary: String?,
    checked: Boolean,
    index: Int,
    count: Int,
    tag: String,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    val colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright)
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled && LocalSettingsStorageState.current.canEdit,
        modifier = Modifier.testTag(tag),
        // A preference toggle is not a selected list item. Keep the group's
        // outline and colors stable; only the switch reflects the saved value.
        shapes = shapes.copy(
            selectedShape = shapes.shape, pressedShape = shapes.shape,
            focusedShape = shapes.shape, hoveredShape = shapes.shape, draggedShape = shapes.shape,
        ),
        colors = colors.copy(
            selectedContainerColor = colors.containerColor,
            selectedContentColor = colors.contentColor,
            selectedLeadingContentColor = colors.leadingContentColor,
            selectedTrailingContentColor = colors.trailingContentColor,
            selectedOverlineContentColor = colors.overlineContentColor,
            selectedSupportingContentColor = colors.supportingContentColor,
        ),
        leadingContent = leading,
        content = { Text(title) },
        supportingContent = if (summary == null) null else ({ Text(summary) }),
        // The whole segmented item is the sole toggle and accessibility target.
        trailingContent = {
            Switch(
                checked = checked, onCheckedChange = null,
                enabled = enabled && LocalSettingsStorageState.current.canEdit,
                modifier = Modifier.testTag("${tag}_switch"),
            )
        },
    )
}
