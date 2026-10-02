package com.cabin.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun TripToolsPanel(vehicle: TeyesClimateState) {
    val context = LocalContext.current
    val history = remember(context) { TripHistory(context) }
    val values = rememberAutomationValues("cabin_trips")
    val profile = vehicle.profileId
    SettingsDisclosure(stringResource(R.string.tools_trips), stringResource(R.string.tools_trip_detail), searchLabels = emptySet()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(history.recording(profile), { history.setRecording(profile, it) }, enabled = profile > 0, modifier = Modifier.semantics { contentDescription = context.getString(R.string.gv_record_trips) })
            Text(stringResource(R.string.gv_record_trips))
        }
        Text(stringResource(R.string.gv_record_detail))
        var rate by rememberSaveable(profile) { mutableStateOf(history.fuelValue(profile, "fuelRate").orEmpty()) }
        var price by rememberSaveable(profile) { mutableStateOf(history.fuelValue(profile, "fuelPrice").orEmpty()) }
        var currency by rememberSaveable(profile) { mutableStateOf(history.fuelValue(profile, "fuelCurrency").orEmpty()) }
        val valid = tripFuelEstimate(1.0, rate, price) != null && runCatching { java.util.Currency.getInstance(currency) }.isSuccess
        OutlinedTextField(rate, { rate = it.take(12) }, label = { Text(stringResource(R.string.tools_fuel_rate)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it.take(12) }, label = { Text(stringResource(R.string.tools_fuel_price)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(currency, { currency = it.take(3).uppercase() }, label = { Text(stringResource(R.string.gv_currency)) }, singleLine = true)
        Row {
            TextButton(onClick = { history.prefs.edit().putString("fuelRate.$profile", rate.replace(',', '.')).putString("fuelPrice.$profile", price.replace(',', '.')).putString("fuelCurrency.$profile", currency).apply() }, enabled = valid && profile > 0) { Text(stringResource(R.string.action_apply)) }
            TextButton(onClick = { rate = history.fuelValue(profile, "fuelRate").orEmpty(); price = history.fuelValue(profile, "fuelPrice").orEmpty(); currency = history.fuelValue(profile, "fuelCurrency").orEmpty() }) { Text(stringResource(R.string.action_cancel)) }
        }
        key(profile) { TripHistoryBrowser(history, profile, values) }
    }
    MaintenanceItemsPanel(profile, history, values)
}
