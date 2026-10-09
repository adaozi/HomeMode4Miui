package com.galaxyrio.gracelauncher.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homemode4miui.R
import com.galaxyrio.gracelauncher.data.media.MediaCommand
import com.galaxyrio.gracelauncher.data.media.NowPlaying
import com.galaxyrio.gracelauncher.ui.components.LocalLauncherInputEnabled
import com.galaxyrio.gracelauncher.ui.components.LauncherLayout
import com.galaxyrio.gracelauncher.ui.components.SwipeDismissContainer
import com.galaxyrio.gracelauncher.ui.theme.LocalLauncherAppearance

/** A wallpaper-native row: no card, progress bar or duplicated player heading. */
@Composable
internal fun HomeMediaPlayer(
    media: NowPlaying,
    onCommand: (String, MediaCommand) -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (String, Long) -> Boolean = { _, _ -> false },
) {
    val appearance = LocalLauncherAppearance.current
    val enabled = LocalLauncherInputEnabled.current
    val openLabel = stringResource(R.string.media_open_player, media.playerName)
    val openPlayer = { onCommand(media.sessionId, MediaCommand.OpenPlayer) }
    val disabledColor = appearance.text.copy(alpha = 0.38f)

    SwipeDismissContainer(
        itemKey = media.sessionId to media.revision,
        onDismiss = { onDismiss(media.sessionId, media.revision) },
        modifier = modifier.fillMaxWidth().testTag("home_media_player"),
        enabled = enabled,
        backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
        contentColor = appearance.text,
        // Clip the dismiss backdrop, not the artwork's faint floating shadow.
        clipContent = false,
    ) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = LauncherLayout.ContentInset, vertical = 3.dp)) {
        // Keep all three controls at 48dp even on a 320dp-wide phone.
        val coverSize = (maxWidth - 144.dp - LauncherLayout.IconLabelGap).coerceIn(44.dp, 88.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(coverSize)
                    .testTag("home_media_artwork")
                    .shadow(2.dp, RoundedCornerShape(6.dp), clip = false,
                        ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.12f),
                        spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.16f))
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f))
                    .clickable(enabled = enabled, onClickLabel = openLabel, onClick = openPlayer)
                    .semantics { contentDescription = openLabel },
                contentAlignment = Alignment.Center,
            ) {
                if (media.artwork != null) {
                    Image(media.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(painterResource(R.drawable.ms_music_note), null,
                        Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(LauncherLayout.IconLabelGap))
            Column(Modifier.weight(1f)) {
                // Metadata is plain wallpaper text. No inner surface, clipping or ripple.
                Column(Modifier.fillMaxWidth().testTag("home_media_metadata")
                    .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() },
                        indication = null, onClickLabel = openLabel, onClick = openPlayer)) {
                    Text(
                        media.title ?: stringResource(R.string.media_unknown_title),
                        modifier = Modifier.testTag("home_media_title"),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium,
                            shadow = appearance.textShadow,
                        ),
                        color = appearance.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        media.artist ?: media.playerName,
                        modifier = Modifier.testTag("home_media_artist"),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp, lineHeight = 20.sp, shadow = appearance.textShadow,
                        ),
                        color = appearance.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onCommand(media.sessionId, MediaCommand.Previous) },
                        enabled = enabled && media.canPrevious,
                        modifier = Modifier.size(48.dp).clip(CircleShape).testTag("media_previous"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = appearance.text, disabledContentColor = disabledColor),
                    ) {
                        Icon(painterResource(R.drawable.ms_skip_previous), stringResource(R.string.media_previous), Modifier.size(24.dp))
                    }
                    val toggleInteractions = remember { MutableInteractionSource() }
                    val toggleLabel = stringResource(if (media.playing) R.string.media_pause else R.string.media_play)
                    Box(
                        modifier = Modifier.size(48.dp).testTag("media_toggle")
                            .clickable(enabled = enabled && media.canToggle, role = Role.Button,
                                interactionSource = toggleInteractions, indication = null,
                                onClick = { onCommand(media.sessionId, MediaCommand.TogglePlayback) })
                            .semantics { contentDescription = toggleLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        // A separate visual child keeps both the ring and its ripple
                        // at 40dp without shrinking the 48dp touch/semantics bounds.
                        Box(Modifier.size(40.dp).testTag("media_toggle_outline").clip(CircleShape)
                            .indication(toggleInteractions, ripple(color = appearance.text))
                            .border(BorderStroke(1.25.dp, if (enabled && media.canToggle) appearance.text else disabledColor), CircleShape),
                            contentAlignment = Alignment.Center) {
                            Icon(painterResource(if (media.playing) R.drawable.ms_pause else R.drawable.ms_play_arrow),
                                null, Modifier.size(22.dp), tint = if (enabled && media.canToggle) appearance.text else disabledColor)
                        }
                    }
                    IconButton(
                        onClick = { onCommand(media.sessionId, MediaCommand.Next) },
                        enabled = enabled && media.canNext,
                        modifier = Modifier.size(48.dp).clip(CircleShape).testTag("media_next"),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = appearance.text, disabledContentColor = disabledColor),
                    ) {
                        Icon(painterResource(R.drawable.ms_skip_next), stringResource(R.string.media_next), Modifier.size(24.dp))
                    }
                }
            }
        }
    }
    }
}
