package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.R
import com.cabin.platform.*
import java.util.Locale

enum class VehicleGauge(val label: Int) {
    RPM(R.string.vehicle_engine_speed), OIL(R.string.vehicle_oil_life),
    SERVICE(R.string.vehicle_oil_service),
}

internal data class GaugeReading(val value: String = "—", val unit: String = "", val source: String = "TEYES / CANBUS", val available: Boolean = false)

/** Values have already been normalized and individually expired by the TEYES controller. */
internal fun vehicleGaugeReading(gauge: VehicleGauge, vehicle: TeyesClimateState,
    units: MeasurementUnit, locale: Locale = Locale.getDefault()): GaugeReading {
    if (!vehicle.connected || vehicle.health != TeyesTelemetryHealth.LIVE) return GaugeReading()
    if (gauge == VehicleGauge.SERVICE) {
        val distance = vehicle.oilServiceDistance
        if (distance == null || !setOf(179, 180, 181).all { it in vehicle.availableCodes }) return GaugeReading()
        val formatted = MeasurementFormatter.serviceDistance(distance, vehicle.oilServiceDistanceMiles, units, locale)
        return GaugeReading(formatted.substringBeforeLast(' '), formatted.substringAfterLast(' '), available = true)
    }
    val number = when (gauge) {
        VehicleGauge.RPM -> vehicle.engineRpm?.takeIf { 90 in vehicle.availableCodes && it in 0..10_000 }
        VehicleGauge.OIL -> vehicle.oilLifePercent?.takeIf { 137 in vehicle.availableCodes && it in 0..100 }
        VehicleGauge.SERVICE -> null
    } ?: return GaugeReading()
    val value = number
    val unit = when (gauge) {
        VehicleGauge.RPM -> "RPM"; VehicleGauge.OIL -> "%"; VehicleGauge.SERVICE -> ""
    }
    return GaugeReading(value.toString(), unit, available = true)
}

@Composable
fun VehicleWidgetsPage(preferences: LauncherPreferences, vehicle: TeyesClimateState, moving: Boolean,
    onParkedAction: (() -> Unit) -> Unit) {
    val context = LocalContext.current
    val layout by preferences.state.collectAsStateWithLifecycle()
    val units by MeasurementPreferences.get(context).unit.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(moving) { if (moving) editing = false }
    var page by rememberSaveable { mutableIntStateOf(0) }
    val gaugePages = maxOf(1, (layout.gauges.size + 1) / 2)
    val totalPages = gaugePages + if (moving) 0 else 1
    LaunchedEffect(totalPages) { page = page.coerceAtMost(totalPages - 1) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PageDots(page, totalPages, { page = it }, Modifier.weight(1f))
            TextButton({ onParkedAction { editing = !editing } }, enabled = !moving, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(if (editing) R.string.launcher_done else R.string.launcher_customize))
            }
        }
        if (editing && !moving && page < gaugePages) {
            Text(stringResource(R.string.gauge_add))
            VehicleGauge.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { gauge -> FilterChip(selected = gauge in layout.gauges, onClick = { preferences.toggleGauge(gauge) },
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp), label = { Text(stringResource(gauge.label)) }) }
                }
            }
        } else if (page < gaugePages) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                layout.gauges.drop(page * 2).take(2).forEach { gauge ->
                    val reading = vehicleGaugeReading(gauge, vehicle, units)
                    val label = stringResource(gauge.label)
                    Card(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label }) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            if (gauge != VehicleGauge.RPM) {
                                Text(stringResource(gauge.label), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(reading.value, style = MaterialTheme.typography.displaySmall, maxLines = 1)
                            if (reading.available) Text(reading.unit)
                        }
                    }
                }
            }
            if (layout.gauges.isEmpty()) Text(stringResource(R.string.gauge_empty))
        } else Box(Modifier.weight(1f)) { LauncherWidgets(preferences, canEdit = editing) }
    }
}
