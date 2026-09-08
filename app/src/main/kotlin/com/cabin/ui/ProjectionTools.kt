package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoSettings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cabin.CabinManager.ProjectionAction

/**
 * An in-composition panel, never a new window or projection-size change. The caller owns
 * gesture cancellation, dismissal and command availability. No transport work or timers run here.
 */
@Composable
fun ProjectionToolsPanel(
    playing: Boolean,
    voiceLabel: String,
    onAction: (ProjectionAction) -> Unit,
    onRecoverPicture: () -> Unit,
    onHub: (() -> Unit)?,
    onClimate: (() -> Unit)?,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    status: String,
    modifier: Modifier = Modifier,
    onScreenOff: (() -> Unit)? = null,
    onHelp: (() -> Unit)? = null,
    onHome: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("projection_tools"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Close stays outside the scroll area, including on short head-unit displays.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.projection_tools_title),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.projection_tools_close))
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                onHome?.let { open ->
                    OutlinedButton(open, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.launcher_home))
                    }
                }
                Button(
                    onClick = { onAction(ProjectionAction.VOICE) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(voiceLabel.ifBlank { stringResource(R.string.projection_voice_assistant) })
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProjectionToolButton(stringResource(R.string.projection_previous), Icons.Default.SkipPrevious) { onAction(ProjectionAction.PREVIOUS) }
                    ProjectionToolButton(if (playing) stringResource(R.string.projection_pause) else stringResource(R.string.projection_play), if (playing) Icons.Default.Pause else Icons.Default.PlayArrow) {
                        onAction(ProjectionAction.PLAY_PAUSE)
                    }
                    ProjectionToolButton(stringResource(R.string.projection_next), Icons.Default.SkipNext) { onAction(ProjectionAction.NEXT) }
                }
                OutlinedButton(
                    onClick = onRecoverPicture,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.VideoSettings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.projection_recover_picture))
                }
                Text(
                    stringResource(R.string.projection_recover_picture_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.isNotBlank()) {
                    Text(
                        status,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider()
                onScreenOff?.let { screenOff ->
                    ProjectionToolButton(stringResource(R.string.projection_screen_off), Icons.Default.VisibilityOff, screenOff)
                    Text(
                        stringResource(R.string.projection_screen_off_detail),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    onClimate?.let { ProjectionToolButton("A/C", Icons.Default.Air, it) }
                    onHub?.let { ProjectionToolButton(stringResource(R.string.projection_vehicle_hub), Icons.Default.Dashboard, it) }
                    onHelp?.let { ProjectionToolButton(stringResource(R.string.help_title), Icons.Default.Settings, it) }
                    ProjectionToolButton(stringResource(R.string.action_settings), Icons.Default.Settings, onSettings)
                }
                Text(
                    stringResource(R.string.projection_parked_setup),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProjectionToolButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
