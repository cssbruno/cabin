package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cabin.CabinManager
import com.cabin.R
import com.cabin.platform.TeyesClimateState
import com.cabin.platform.TeyesTelemetryHealth
import com.cabin.ui.formatClimateTemperature
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date

internal val additionalDashboardModules = setOf(
    DashboardModule.DRIVER_TEMPERATURE, DashboardModule.PASSENGER_TEMPERATURE,
    DashboardModule.AIRFLOW, DashboardModule.RECIRCULATION, DashboardModule.HOOD,
    DashboardModule.TRUNK, DashboardModule.CAN_CONNECTION, DashboardModule.DATE,
    DashboardModule.PHONE_CONNECTION, DashboardModule.ASSISTANT,
)

/** Require each field independently: another fresh CAN sample cannot make a missing reading valid. */
internal fun additionalVehicleFieldKnown(module: DashboardModule, state: TeyesClimateState): Boolean {
    fun known(vararg codes: Int) = comfortFieldAvailable(state, *codes)
    return when (module) {
        DashboardModule.DRIVER_TEMPERATURE -> state.leftTemperature != null && known(25, 33)
        DashboardModule.PASSENGER_TEMPERATURE -> state.rightTemperature != null && known(31, 33)
        DashboardModule.RECIRCULATION -> known(21)
        DashboardModule.HOOD -> known(36)
        DashboardModule.TRUNK -> known(41)
        DashboardModule.AIRFLOW -> if (state.profileId == 262465) known(73) else
            (known(28) || known(91)) && (known(26) || known(92)) && (known(27) || known(93))
        else -> false
    }
}

@Composable
internal fun AdditionalDashboardWidget(module: DashboardModule, vehicle: TeyesClimateState,
    phone: CabinManager.State, onAssistant: () -> Unit = {}) {
    val title = stringResource(module.title())
    val known = additionalVehicleFieldKnown(module, vehicle)
    val locale = LocalConfiguration.current.locales[0]
    var date by remember { mutableStateOf(Date()) }
    if (module == DashboardModule.DATE) LaunchedEffect(Unit) {
        while (true) { date = Date(); delay(1000) }
    }
    val value = when (module) {
        DashboardModule.DRIVER_TEMPERATURE -> formatClimateTemperature(vehicle.leftTemperature.takeIf { known }, vehicle.fahrenheit)
        DashboardModule.PASSENGER_TEMPERATURE -> formatClimateTemperature(vehicle.rightTemperature.takeIf { known }, vehicle.fahrenheit)
        DashboardModule.RECIRCULATION -> if (!known) "—" else stringResource(if (vehicle.recirculating) R.string.climate_state_on else R.string.interface_state_off)
        DashboardModule.HOOD, DashboardModule.TRUNK -> if (!known) "—" else stringResource(
            if (if (module == DashboardModule.HOOD) vehicle.hoodOpen else vehicle.bootOpen) R.string.widget_status_open else R.string.widget_status_closed)
        DashboardModule.AIRFLOW -> if (!known) "—" else buildList {
            if (vehicle.blowUp) add(stringResource(R.string.widget_air_screen))
            if (vehicle.blowBody) add(stringResource(R.string.widget_air_face))
            if (vehicle.blowFoot) add(stringResource(R.string.widget_air_feet))
        }.joinToString(" · ").ifEmpty { stringResource(R.string.interface_state_off) }
        DashboardModule.CAN_CONNECTION -> stringResource(when {
            vehicle.health == TeyesTelemetryHealth.CONNECTING -> R.string.widget_status_connecting
            !vehicle.connected -> R.string.widget_status_offline
            vehicle.health == TeyesTelemetryHealth.LIVE -> R.string.widget_status_live
            else -> R.string.widget_status_stale
        })
        DashboardModule.PHONE_CONNECTION -> stringResource(when (phone) {
            CabinManager.State.DISCONNECTED -> R.string.widget_status_offline
            CabinManager.State.CONNECTING -> R.string.widget_status_connecting
            CabinManager.State.DEVICE_CONNECTED -> R.string.connection_status_phone_connected
            CabinManager.State.STREAMING -> R.string.connection_status_streaming
        })
        DashboardModule.DATE -> SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMEd"), locale).format(date)
        else -> ""
    }
    val icon = when (module) {
        DashboardModule.DRIVER_TEMPERATURE, DashboardModule.PASSENGER_TEMPERATURE -> Icons.Default.Thermostat
        DashboardModule.AIRFLOW -> Icons.Default.Air
        DashboardModule.RECIRCULATION -> Icons.Default.Autorenew
        DashboardModule.HOOD -> Icons.Default.DirectionsCar
        DashboardModule.TRUNK -> Icons.Default.Luggage
        DashboardModule.CAN_CONNECTION -> Icons.Default.Cable
        DashboardModule.PHONE_CONNECTION -> Icons.Default.PhoneAndroid
        DashboardModule.DATE -> Icons.Default.CalendarMonth
        else -> Icons.Default.Mic
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("widget-${module.name}")) {
        val compact = maxHeight < 150.dp || maxWidth < 180.dp
        val horizontal = maxWidth > maxHeight * 1.7f && maxWidth >= 260.dp
        val available = known || module == DashboardModule.DATE ||
            (module == DashboardModule.CAN_CONNECTION && vehicle.connected && vehicle.health == TeyesTelemetryHealth.LIVE) ||
            (module == DashboardModule.PHONE_CONNECTION && phone == CabinManager.State.STREAMING)
        @Composable fun Symbol() {
            if (module == DashboardModule.ASSISTANT) FilledIconButton(onAssistant,
                enabled = phone == CabinManager.State.STREAMING, modifier = Modifier.size(56.dp)) {
                Icon(icon, title, Modifier.size(28.dp))
            } else Icon(icon, null, Modifier.size(if (compact) 24.dp else 36.dp),
                tint = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        @Composable fun Reading(modifier: Modifier = Modifier) {
            Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (value.isNotEmpty()) Text(value, fontSize = if (compact) 20.sp else 28.sp,
                    fontWeight = FontWeight.Light, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                if (module != DashboardModule.DATE && (module != DashboardModule.ASSISTANT || !compact)) Text(title,
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val body = Modifier.fillMaxSize().padding(if (compact) 8.dp else 16.dp)
            .semantics { contentDescription = title; stateDescription = value }
        if (horizontal) Row(body, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol(); Reading(Modifier.weight(1f))
        } else Column(body, verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (!compact || module == DashboardModule.ASSISTANT) Symbol()
            Reading()
        }
    }
}
