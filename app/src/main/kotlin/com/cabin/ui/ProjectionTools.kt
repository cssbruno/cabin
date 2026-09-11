package com.cabin.ui

import com.cabin.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * An in-composition panel, never a new window or projection-size change. The caller owns
 * gesture cancellation, dismissal and command availability. No transport work or timers run here.
 */
@Composable
fun ProjectionToolsPanel(
    onSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onScreenOff: (() -> Unit)? = null,
    onChangeDevice: (() -> Unit)? = null,
    connectedDeviceName: String? = null,
) {
    Surface(
        modifier = modifier.widthIn(max = 360.dp).fillMaxWidth().testTag("projection_tools"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Close stays outside the scroll area, including on short head-unit displays.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectionToolButton(
                    label = stringResource(if (onChangeDevice != null) R.string.projection_change_device else R.string.action_settings),
                    icon = if (onChangeDevice != null) Icons.Default.PhonelinkSetup else Icons.Default.Settings,
                    onClick = onChangeDevice ?: onSettings,
                    modifier = Modifier.weight(1f),
                    detail = if (onChangeDevice != null) connectedDeviceName else null,
                )
                IconButton(onClick = onClose, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.projection_tools_close))
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onChangeDevice != null) {
                    ProjectionToolButton(stringResource(R.string.action_settings), Icons.Default.Settings, onSettings)
                }
                onScreenOff?.let { screenOff ->
                    ProjectionToolButton(stringResource(R.string.projection_screen_off_short), Icons.Default.VisibilityOff, screenOff,
                        detail = stringResource(R.string.projection_audio_continues))
                }

            }
        }
    }
}

@Composable
private fun ProjectionToolButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
