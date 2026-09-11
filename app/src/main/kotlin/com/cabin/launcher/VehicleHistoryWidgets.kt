package com.cabin.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import kotlinx.coroutines.Dispatchers
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun VehicleAlertsWidget(vehicle: TeyesClimateState) {
    val scope = rememberCoroutineScope()
    val reminders = rememberAutomationValues("cabin_trips")
    val today by produceState(java.time.LocalDate.now()) {
        while (true) { value = java.time.LocalDate.now(); kotlinx.coroutines.delay(60_000) }
    }
    val due = try { java.time.LocalDate.parse(reminders["maintenance.${vehicle.profileId}"] as? String) <= today } catch (_: Exception) { false }
    val alerts = (vehicleAlerts(vehicle) + if (due) listOf(VehicleAlert(R.string.alert_service_due)) else emptyList()).distinct()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.alert_title), style = MaterialTheme.typography.titleMedium)
        if (alerts.isEmpty()) Text(stringResource(if (vehicle.connected) R.string.alert_none else R.string.alert_disconnected),
            style = MaterialTheme.typography.bodyMedium)
        else {
            val pager = rememberPagerState { alerts.size }
            HorizontalPager(pager, Modifier.weight(1f)) { index ->
                Column {
                    Text(stringResource(alerts[index].label), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleLarge)
                    alerts[index].detail?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                }
            }
            PageDots(pager.currentPage, alerts.size, { target -> scope.launch { pager.animateScrollToPage(target) } }, maxVisible = 5)
        }
    }
}

@Composable
internal fun TireHistoryWidget(vehicle: TeyesClimateState) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val history = remember(context) { TireHistory(context) }
    val points by produceState<List<TireHistoryPoint>>(emptyList(), vehicle.profileId) {
        value = emptyList()
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key == vehicle.profileId.toString()) trySend(Unit)
            }
            history.prefs.registerOnSharedPreferenceChangeListener(listener)
            trySend(Unit)
            awaitClose { history.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }.conflate().collect {
            value = withContext(Dispatchers.IO) { history.read(vehicle.profileId).takeLast(120) }
        }
    }
    val pager = rememberPagerState { 4 }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.history_title), style = MaterialTheme.typography.titleMedium)
        if (points.isEmpty()) Text(stringResource(R.string.history_empty))
        else {
            HorizontalPager(pager, Modifier.weight(1f)) { wheel ->
                val values = points.mapNotNull { it.tires[wheel].pressureKpa }
                val color = MaterialTheme.colorScheme.primary
                Column(Modifier.fillMaxSize()) {
                    Text(stringResource(listOf(R.string.vehicle_wheel_fl, R.string.vehicle_wheel_fr, R.string.vehicle_wheel_rl, R.string.vehicle_wheel_rr)[wheel]))
                    Text(points.last().tires[wheel].pressureKpa?.let { NumberFormat.getNumberInstance().format(it) + " kPa" } ?: "—")
                    Canvas(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) {
                        val low = (values.minOrNull() ?: 0.0) - 5.0
                        val high = (values.maxOrNull() ?: 0.0) + 5.0
                        val start = points.first().time
                        val span = (points.last().time - start).coerceAtLeast(1)
                        fun position(point: TireHistoryPoint, pressure: Double) = Offset(
                            (point.time - start).toFloat() / span * size.width,
                            ((high - pressure) / (high - low)).toFloat() * size.height)
                        points.forEachIndexed { index, point ->
                            val pressure = point.tires[wheel].pressureKpa ?: return@forEachIndexed
                            val current = position(point, pressure)
                            drawCircle(color, 3.dp.toPx(), current)
                            if (index > 0) {
                                val previous = points[index - 1]
                                val priorPressure = previous.tires[wheel].pressureKpa
                                if (priorPressure != null && point.time - previous.time <= 120_000)
                                    drawLine(color, position(previous, priorPressure), current, 2.dp.toPx())
                            }
                        }
                    }
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(points.last().time)), style = MaterialTheme.typography.labelSmall)
                }
            }
            PageDots(pager.currentPage, 4, { target -> scope.launch { pager.animateScrollToPage(target) } }, maxVisible = 4)
        }
    }
}

@Composable
internal fun TripHistoryWidget(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val values = rememberAutomationValues("cabin_trips")
    val history = remember { TripHistory(context) }
    val trips = remember(vehicle.profileId, values["trips.${vehicle.profileId}"]) { history.read(vehicle.profileId).reversed() }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tools_trips), style = MaterialTheme.typography.titleMedium)
        if (trips.isEmpty()) Text(stringResource(R.string.tools_no_trips)) else {
            val pager = rememberPagerState { trips.size }
            HorizontalPager(pager, Modifier.weight(1f)) { index ->
                val trip = trips[index]
                Column {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(trip.start)))
                    Text(stringResource(R.string.tools_trip_row, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(trip.kilometers), trip.seconds / 60))
                    trip.estimatedCost?.let { Text(stringResource(R.string.tools_cost_row, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(it))) }
                }
            }
            PageDots(pager.currentPage, trips.size, { target -> scope.launch { pager.animateScrollToPage(target) } })
        }
    }
}
