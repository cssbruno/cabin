package com.cabin.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import kotlin.math.roundToInt

@Composable
internal fun VehicleWidgetHeading(title: Int, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun ExpandedFactoryWidget(group: SyuFactoryGroup, vehicle: TeyesClimateState, moving: Boolean,
    onChange: ((SyuFactoryControl, Int) -> Unit)?, onParkedAction: (() -> Unit) -> Unit) {
    val controls = SyuFactoryControl.entries.filter { it.group == group && it in vehicle.syuVehicle.factoryCapabilities }
    if (controls.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("—") }; return }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        VehicleWidgetHeading(when (group) {
            SyuFactoryGroup.HONDA_PANEL -> R.string.honda_panel_title
            SyuFactoryGroup.SEAT_MEMORY -> R.string.energy_seat_preset
            SyuFactoryGroup.CHARGING -> R.string.energy_settings
            SyuFactoryGroup.AMBIENT -> R.string.energy_palette
            SyuFactoryGroup.CAMERA -> R.string.vehicle_camera_mode
            SyuFactoryGroup.MIRRORS -> R.string.vehicle_mirror_settings
            SyuFactoryGroup.PARKING -> R.string.vehicle_parking_settings
        }, when (group) {
            SyuFactoryGroup.HONDA_PANEL -> Icons.Default.DirectionsCar
            SyuFactoryGroup.SEAT_MEMORY -> Icons.Default.AirlineSeatReclineNormal
            SyuFactoryGroup.CHARGING -> Icons.Default.EvStation
            SyuFactoryGroup.AMBIENT -> Icons.Default.Palette
            SyuFactoryGroup.CAMERA -> Icons.Default.Videocam
            SyuFactoryGroup.MIRRORS -> Icons.Default.DirectionsCar
            SyuFactoryGroup.PARKING -> Icons.Default.LocalParking
        })
        if (group == SyuFactoryGroup.CAMERA) {
            val current = vehicle.syuVehicle.factoryControls[SyuFactoryControl.CAMERA_MODE]?.takeIf { vehicle.connected }
            val enabled = !moving && current != null && onChange != null
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (mode in 0..2) {
                    val selected = current == mode
                    Surface(onClick = { onParkedAction { onChange?.invoke(SyuFactoryControl.CAMERA_MODE, mode) } }, enabled = enabled,
                        shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f).fillMaxWidth().semantics { this.selected = selected },
                        border = androidx.compose.foundation.BorderStroke(1.dp,
                            if (selected) colors.primary.copy(alpha = .7f) else colors.outlineVariant.copy(alpha = .25f)),
                        color = if (selected) colors.primary.copy(alpha = .12f) else colors.surfaceContainerHigh.copy(alpha = .45f),
                        contentColor = if (selected) colors.primary else colors.onSurfaceVariant) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp))
                                .background(if (selected) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .035f)),
                                contentAlignment = Alignment.Center) {
                                Icon(when (mode) { 0 -> Icons.Default.PanoramaWideAngle; 1 -> Icons.Default.CropLandscape; else -> Icons.Default.South },
                                    null, Modifier.size(30.dp))
                            }
                            Text(stringResource(listOf(R.string.vehicle_camera_wide, R.string.vehicle_camera_standard, R.string.vehicle_camera_down)[mode]),
                                Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.headlineSmall, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                            Icon(if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                null, Modifier.size(24.dp), tint = if (selected) colors.primary else colors.outline.copy(alpha = .5f))
                        }
                    }
                }
            }
        } else {
            controls.forEach { control ->
                val title = stringResource(control.title())
                val current = vehicle.syuVehicle.factoryControls[control]?.takeIf { vehicle.connected }
                val enabled = !moving && current != null && onChange != null
                Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = colors.surfaceContainerHigh.copy(alpha = .65f)) {
                    if (control.maximum == 1 && control != SyuFactoryControl.HONDA_DISTANCE_UNITS) Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(when (control) {
                            SyuFactoryControl.RAIN_WIPERS -> Icons.Default.WaterDrop
                            SyuFactoryControl.REVERSE_REAR_WIPER -> Icons.Default.Waves
                            SyuFactoryControl.MIRROR_REVERSE_DIP -> Icons.Default.South
                            SyuFactoryControl.MIRROR_PARK_FOLD -> Icons.Default.UnfoldLess
                            SyuFactoryControl.PARKING_AUTO -> Icons.Default.LocalParking
                            else -> Icons.Default.SyncAlt
                        }, null, tint = colors.primary, modifier = Modifier.size(24.dp))
                        Text(stringResource(control.title()), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Switch(modifier = Modifier.semantics { contentDescription = title }, checked = current == 1, onCheckedChange = { checked -> onParkedAction { onChange?.invoke(control, if (checked) 1 else 0) } }, enabled = enabled)
                    } else {
                        var drag by remember(control) { mutableStateOf<Float?>(null) }
                        LaunchedEffect(current, enabled) { if (!enabled) drag = null }
                        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(control.title()), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(current?.let { factoryValueLabel(control, it) } ?: "—", color = colors.primary, style = MaterialTheme.typography.titleMedium)
                            }
                            Slider(value = drag ?: (current ?: 0).toFloat(), onValueChange = { drag = it },
                                onValueChangeFinished = { val value = drag?.roundToInt(); drag = null
                                    if (enabled && value != null) onParkedAction { onChange?.invoke(control, value) } },
                                valueRange = 0f..control.maximum.toFloat(), steps = control.maximum - 1, enabled = enabled,
                                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = title })
                        }
                    }
                }
            }
        }
    }
}
