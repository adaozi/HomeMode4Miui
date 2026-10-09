/*
 * P6 裁剪版 —— 移植自 GraceLauncher v1.1.4（GPL-3.0，
 * https://github.com/Galaxy-rio/GraceLauncher）
 * 原路径：app/src/main/java/com/galaxyrio/gracelauncher/ui/overlays/IconPackGrid.kt
 *
 * 保留：已装图标包网格（包图标 + label + 选中态）、System icons 清除项。
 * 裁剪与降级（PORTING_GraceLauncher.md B6.4）：
 *   - 原版设置页体系（IconPackSettings/SettingsScaffold + 多选 fallback 顺序）→
 *     宿主内单选面板（多选顺序随 C 组设置页恢复；单选落 iconPackPackages 单元素列表）
 *   - 原版 ModalBottomSheet 独立窗口 → 同窗口面板（hotfix2 规则）
 */
package com.galaxyrio.gracelauncher.ui.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.icons.IconPackInfo
import com.galaxyrio.gracelauncher.ui.components.LauncherIcon
import com.galaxyrio.gracelauncher.ui.components.LauncherSymbol

/** 单选图标包面板：null 选中 = 系统图标；pkg 选中 = 该包。 */
@Composable
fun IconPackPanel(
    packs: List<IconPackInfo>?,
    selected: String?,
    busy: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().testTag("launcher_sheet")) {
        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onDismiss).clearAndSetSemantics {})
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .heightIn(max = panelWindowHeight() * 0.9f).testTag("icon_pack_panel"),
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp)) {
                Text(stringResource(R.string.settings_icon_pack),
                    Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), fontSize = 25.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                when {
                    packs == null -> Column(
                        Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.CircularProgressIndicator()
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.icon_pack_loading),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                    packs.isEmpty() -> Text(
                        stringResource(R.string.icon_pack_empty),
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = panelWindowHeight() * 0.55f)
                            .testTag("icon_pack_grid"),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        item(key = "system") {
                            IconPackCell(
                                label = stringResource(R.string.icon_pack_system),
                                selected = selected == null,
                                onClick = { onSelect(null) },
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    LauncherIcon(LauncherSymbol.Apps, Modifier.size(30.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(packs, key = { it.packageName }) { pack ->
                            IconPackCell(
                                label = pack.label,
                                selected = selected == pack.packageName,
                                onClick = { onSelect(pack.packageName) },
                            ) {
                                val icon = pack.icon
                                if (icon != null) {
                                    androidx.compose.foundation.Image(icon, null,
                                        Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                } else {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        LauncherIcon(LauncherSymbol.Apps, Modifier.size(30.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconPackCell(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.padding(6.dp).clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(13.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .then(if (selected) Modifier.border(
                    2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(13.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(38.dp)) { icon() } }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
