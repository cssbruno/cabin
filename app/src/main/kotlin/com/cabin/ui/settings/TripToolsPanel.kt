package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.cabin.R
import com.cabin.platform.*
import java.time.LocalDate
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun TripToolsPanel(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val history = remember { TripHistory(context) }
    val values = rememberAutomationValues("cabin_trips")
    SettingsDisclosure(stringResource(R.string.tools_trips), stringResource(R.string.tools_trip_detail)) {
        for ((key, label) in listOf("fuelRate" to R.string.tools_fuel_rate, "fuelPrice" to R.string.tools_fuel_price)) {
            var text by remember { mutableStateOf(values[key] as? String ?: "") }
            val number = text.replace(',', '.').toDoubleOrNull()
            val valid = number != null && number.isFinite() && number > 0 && number <= if (key == "fuelRate") 100 else 10000
            OutlinedTextField(text, onValueChange = {
                text = it.take(12)
                val value = text.replace(',', '.').toDoubleOrNull()
                if (value != null && value.isFinite() && value > 0 && value <= if (key == "fuelRate") 100 else 10000)
                    history.prefs.edit().putString(key, text.replace(',', '.')).apply()
                else history.prefs.edit().remove(key).apply()
            }, singleLine = true, isError = text.isNotEmpty() && !valid, label = { Text(stringResource(label)) })
        }
        val trips = remember(values["trips.${vehicle.profileId}"]) { history.read(vehicle.profileId) }
        trips.takeLast(10).reversed().forEach { trip ->
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(trip.start)))
            Text(stringResource(R.string.tools_trip_row, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(trip.kilometers), trip.seconds / 60))
            trip.estimatedCost?.let { Text(stringResource(R.string.tools_cost_row, NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }.format(it))) }
        }
        if (trips.isEmpty()) Text(stringResource(R.string.tools_no_trips))
        TextButton(onClick = { history.prefs.edit().remove("trips.${vehicle.profileId}").apply() }) { Text(stringResource(R.string.tools_clear_trips)) }
    }
    SettingsDisclosure(stringResource(R.string.tools_maintenance), stringResource(R.string.tools_date_detail)) {
        val key = "maintenance.${vehicle.profileId}"
        var date by remember(vehicle.profileId) { mutableStateOf(values[key] as? String ?: "") }
        val parsed = try { LocalDate.parse(date) } catch (_: Exception) { null }
        OutlinedTextField(date, onValueChange = {
            date = it.take(10)
            try { LocalDate.parse(date); history.prefs.edit().putString(key, date).apply() } catch (_: Exception) { }
        }, label = { Text("YYYY-MM-DD") }, isError = date.isNotEmpty() && parsed == null, singleLine = true)
        if (parsed != null) Text(stringResource(if (parsed <= LocalDate.now()) R.string.alert_service_due else R.string.tools_scheduled))
        TextButton(onClick = { date = ""; history.prefs.edit().remove(key).apply() }) { Text(stringResource(R.string.tools_clear_reminder)) }
    }
}
