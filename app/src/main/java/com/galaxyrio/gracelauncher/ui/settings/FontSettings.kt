package com.galaxyrio.gracelauncher.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.AppFont
import com.galaxyrio.gracelauncher.data.ClockFontFile
import com.galaxyrio.gracelauncher.data.ClockFontStore
import com.galaxyrio.gracelauncher.ui.theme.rememberAppFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun appFontLabel(id: String?): String = AppFont.entries.firstOrNull { it.id == id }?.let {
    stringResource(when (it) {
        AppFont.Default -> R.string.clock_font_default
        AppFont.System -> R.string.font_system
        AppFont.NotoSans -> R.string.font_noto_sans
        AppFont.Sacramento -> R.string.clock_sacramento
        AppFont.Bokor -> R.string.clock_bokor
        AppFont.Plaster -> R.string.clock_plaster
        AppFont.Monoton -> R.string.clock_monoton
        AppFont.LuckiestGuy -> R.string.font_luckiest_guy
    })
} ?: id?.let(ClockFontStore::displayName) ?: stringResource(R.string.clock_font_unavailable)

@Composable
internal fun AppFontDialog(selected: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ClockFontStore(context) }
    val scope = rememberCoroutineScope()
    val fonts by produceState(emptyList<ClockFontFile>(), store) { value = store.list() }
    var importing by remember { mutableStateOf(false) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            try {
                val font = store.import(uri, requireClockDigits = false)
                currentOnSelect(font.id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, R.string.font_import_error, Toast.LENGTH_LONG).show()
            } finally { importing = false }
        }
    }
    FontPickerDialog(
        fonts, selected, onSelect,
        onImport = { picker.launch(arrayOf("*/*")) }, onDismiss = { if (!importing) onDismiss() },
        builtIns = AppFont.entries, tag = "app_font", previewFonts = true, importing = importing,
    )
}

/** Plain radio choices in a dialog; segmented lists remain on the settings page. */
@Composable
internal fun FontPickerDialog(
    fonts: List<ClockFontFile>, selected: String?, onSelect: (String?) -> Unit,
    onImport: () -> Unit, onDismiss: () -> Unit,
    builtIns: List<AppFont> = listOf(AppFont.Default), tag: String = "clock_font",
    previewFonts: Boolean = false, importing: Boolean = false,
) {
    val enabled = !importing && LocalSettingsStorageState.current.canEdit
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.settings_font)) },
        text = {
            Column {
                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.clock_font_importing), Modifier.padding(vertical = 8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 360.dp).selectableGroup()) {
                    items(builtIns, key = { "builtin:${it.name}" }) { font ->
                        FontOption(appFontLabel(font.id), font.id, selected, onSelect, tag, previewFonts, enabled)
                    }
                    items(fonts, key = { it.id }) { font ->
                        FontOption(font.name, font.id, selected, onSelect, tag, previewFonts, enabled)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport, enabled = enabled, modifier = Modifier.testTag("${tag}_import")) {
                Text(stringResource(R.string.clock_font_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !importing) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun FontOption(label: String, id: String?, selected: String?, onSelect: (String?) -> Unit,
    tag: String, previewFont: Boolean, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("$tag:${id ?: "default"}")
        .selectable(selected == id, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(id) }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        RadioButton(selected == id, onClick = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyLarge, fontFamily = if (previewFont) rememberAppFontFamily(id) else null)
    }
}
