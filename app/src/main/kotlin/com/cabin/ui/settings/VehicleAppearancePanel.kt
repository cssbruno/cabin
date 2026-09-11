package com.cabin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*

@Composable
internal fun VehicleAppearancePanel(profile: Int) {
    val (appearance, update) = rememberVehicleAppearance(profile)
    SettingsDisclosure(stringResource(R.string.car_appearance), stringResource(R.string.car_appearance_detail)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleBodyStyle.entries.forEach { body ->
                FilterChip(selected = appearance.body == body, onClick = { update(appearance.copy(body = body)) },
                    label = { Text(stringResource(when (body) {
                        VehicleBodyStyle.SEDAN -> R.string.car_sedan
                        VehicleBodyStyle.HATCHBACK -> R.string.car_hatchback
                        VehicleBodyStyle.SUV -> R.string.car_suv
                        VehicleBodyStyle.PICKUP -> R.string.car_pickup
                    })) })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehiclePaint.entries.forEach { paint ->
                FilterChip(selected = appearance.paint == paint, onClick = { update(appearance.copy(paint = paint)) },
                    leadingIcon = { Box(Modifier.size(20.dp).background(Color(paint.argb), CircleShape)) },
                    label = { Text(stringResource(when (paint) {
                        VehiclePaint.SILVER -> R.string.car_silver
                        VehiclePaint.WHITE -> R.string.car_white
                        VehiclePaint.BLACK -> R.string.car_black
                        VehiclePaint.BLUE -> R.string.car_blue
                        VehiclePaint.RED -> R.string.car_red
                        VehiclePaint.GREEN -> R.string.car_green
                    })) })
            }
        }
    }
}
