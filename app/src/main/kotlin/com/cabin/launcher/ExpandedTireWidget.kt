package com.cabin.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import java.text.NumberFormat

@Composable
internal fun ExpandedTireWidget(vehicle: TeyesClimateState) {
    val (appearance, _) = rememberVehicleAppearance(vehicle.profileId)
    val tires = vehicle.syuVehicle.tires.takeIf { vehicle.connected }.orEmpty()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        VehicleWidgetHeading(R.string.vehicle_tire_pressure, Icons.Default.Speed)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val bodyHeight = minOf(maxHeight, 390.dp)
            Row(Modifier.fillMaxWidth().height(bodyHeight), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround) {
                    TireValue(0, tires.getOrNull(0))
                    TireValue(2, tires.getOrNull(2))
                }
                TireCarDiagram(tires, appearance, Modifier.weight(.85f).fillMaxHeight().padding(vertical = 24.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceAround) {
                    TireValue(1, tires.getOrNull(1))
                    TireValue(3, tires.getOrNull(3))
                }
            }
        }
    }
}

@Composable
private fun TireValue(wheel: Int, tire: SyuTireReading?) {
    val colors = MaterialTheme.colorScheme
    val warning = tire?.warning?.takeIf { it > 0 }
    val accent = if (warning != null) colors.error else colors.onSurface
    Surface(shape = RoundedCornerShape(18.dp), color = if (warning != null) colors.error.copy(alpha = .09f) else colors.surfaceContainerHigh.copy(alpha = .65f),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(stringResource(listOf(R.string.vehicle_wheel_fl, R.string.vehicle_wheel_fr, R.string.vehicle_wheel_rl, R.string.vehicle_wheel_rr)[wheel]),
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tire?.pressureKpa?.let { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(it) } ?: "—",
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = accent, maxLines = 1)
            Text("kPa", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            if (warning != null) {
                Icon(Icons.Default.WarningAmber, null, tint = accent, modifier = Modifier.size(18.dp))
                Text(stringResource(when (warning) {
                    1 -> R.string.vehicle_tire_high; 2 -> R.string.vehicle_tire_low
                    3, 7 -> R.string.vehicle_tire_leak; 4 -> R.string.vehicle_tire_fault
                    5 -> R.string.vehicle_tire_battery; else -> R.string.vehicle_tire_missing
                }), color = accent, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun TireCarDiagram(tires: List<SyuTireReading>, appearance: VehicleAppearance, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val paint = Color(appearance.paint.argb)
    Canvas(modifier) {
        // Preserve sedan proportions at every widget size, including portrait displays.
        val scale = minOf(size.width / 120f, size.height / 260f)
        withTransform({
            translate((size.width - 120f * scale) / 2f, (size.height - 260f * scale) / 2f)
            scale(scale, scale, Offset.Zero)
        }) {
            fun shape(block: Path.() -> Unit) = Path().apply(block)
            val body = shape {
                moveTo(60f, 10f)
                cubicTo(82f, 10f, 94f, 16f, 98f, 35f)
                cubicTo(102f, 57f, 104f, 76f, 104f, 99f)
                lineTo(104f, 205f)
                if (appearance.body == VehicleBodyStyle.SUV || appearance.body == VehicleBodyStyle.PICKUP) {
                    quadraticBezierTo(104f, 242f, 94f, 244f)
                    lineTo(26f, 244f)
                    quadraticBezierTo(16f, 242f, 16f, 205f)
                } else {
                    cubicTo(104f, 230f, 94f, 242f, 60f, 244f)
                    cubicTo(26f, 242f, 16f, 230f, 16f, 205f)
                }
                lineTo(16f, 99f)
                cubicTo(16f, 76f, 18f, 57f, 22f, 35f)
                cubicTo(26f, 16f, 38f, 10f, 60f, 10f)
                close()
            }
            drawPath(body, Brush.horizontalGradient(listOf(paint.copy(alpha = .24f),
                paint.copy(alpha = .62f), paint.copy(alpha = .2f)), startX = 16f, endX = 104f))
            drawPath(body, colors.onSurfaceVariant.copy(alpha = .75f), style = Stroke(1.2f))
            // Hood, shoulder lines and bumpers.
            drawPath(shape { moveTo(32f, 31f); quadraticBezierTo(60f, 23f, 88f, 31f)
                moveTo(29f, 40f); lineTo(25f, 75f); moveTo(91f, 40f); lineTo(95f, 75f)
                moveTo(28f, 224f); quadraticBezierTo(60f, 234f, 92f, 224f) },
                colors.outline.copy(alpha = .65f), style = Stroke(.9f))
            val windshield = shape {
                moveTo(26f, 84f); quadraticBezierTo(60f, 73f, 94f, 84f)
                lineTo(85f, 111f); quadraticBezierTo(60f, 106f, 35f, 111f); close()
            }
            val roofEnd = when (appearance.body) {
                VehicleBodyStyle.SEDAN -> 174f
                VehicleBodyStyle.HATCHBACK -> 194f
                VehicleBodyStyle.SUV -> 205f
                VehicleBodyStyle.PICKUP -> 148f
            }
            val rearGlass = shape {
                moveTo(35f, roofEnd + 6f); quadraticBezierTo(60f, roofEnd + 11f, 85f, roofEnd + 6f)
                lineTo(95f, roofEnd + 26f); quadraticBezierTo(60f, roofEnd + 32f, 25f, roofEnd + 26f); close()
            }
            for (glass in listOf(windshield, rearGlass)) {
                drawPath(glass, colors.surface.copy(alpha = .95f))
                drawPath(glass, colors.primary.copy(alpha = .35f), style = Stroke(1f))
            }
            val roof = shape {
                moveTo(36f, 115f); quadraticBezierTo(60f, 110f, 84f, 115f)
                lineTo(86f, roofEnd); quadraticBezierTo(60f, roofEnd + 6f, 34f, roofEnd); close()
            }
            drawPath(roof, colors.onSurface.copy(alpha = .045f))
            drawPath(roof, colors.outline.copy(alpha = .45f), style = Stroke(.8f))
            if (appearance.body == VehicleBodyStyle.PICKUP) {
                drawRoundRect(colors.surface, Offset(25f, 181f), Size(70f, 48f), CornerRadius(3f))
                for (x in 33..88 step 11) drawLine(colors.outline.copy(alpha = .6f), Offset(x.toFloat(), 187f), Offset(x.toFloat(), 222f), 1f)
            }
            if (appearance.body == VehicleBodyStyle.SUV) {
                for (x in listOf(33f, 87f)) drawLine(paint, Offset(x, 116f), Offset(x, 198f), 2f, StrokeCap.Round)
            }
            // Side glass and the B-pillars make the outline recognizably a four-door car.
            for (right in listOf(false, true)) {
                fun x(value: Float) = if (right) 120f - value else value
                for ((top, bottom) in if (appearance.body == VehicleBodyStyle.PICKUP) listOf(96f to 145f) else listOf(96f to 139f, 144f to roofEnd + 12f)) {
                    val window = shape {
                        moveTo(x(23f), top); lineTo(x(30f), top + 17f)
                        lineTo(x(30f), bottom - 7f); lineTo(x(22f), bottom); close()
                    }
                    drawPath(window, colors.surface)
                    drawPath(window, colors.outline.copy(alpha = .6f), style = Stroke(.7f))
                }
                drawLine(colors.onSurfaceVariant, Offset(x(19f), 135f), Offset(x(19f), 143f), 1.5f, StrokeCap.Round)
                drawLine(colors.onSurfaceVariant, Offset(x(19f), 179f), Offset(x(19f), 187f), 1.5f, StrokeCap.Round)
                val mirror = shape { moveTo(x(18f), 99f); lineTo(x(6f), 105f)
                    quadraticBezierTo(x(4f), 114f, x(16f), 112f); close() }
                drawPath(mirror, colors.surfaceContainerHighest)
                drawPath(mirror, colors.outline, style = Stroke(1f))
                drawLine(colors.onSurface.copy(alpha = .7f), Offset(x(25f), 35f), Offset(x(39f), 32f), 3f, StrokeCap.Round)
                drawLine(colors.onSurfaceVariant.copy(alpha = .6f), Offset(x(25f), 222f), Offset(x(40f), 225f), 2.5f, StrokeCap.Round)
            }
            for (wheel in 0..3) {
                val x = if (wheel % 2 == 0) 10f else 101f
                val y = if (wheel < 2) 53f else 188f
                val tire = tires.getOrNull(wheel)
                val color = when {
                    tire?.warning?.let { it > 0 } == true -> colors.error
                    tire?.pressureKpa != null && tire.warning == 0 -> colors.primary
                    else -> colors.outline
                }
                if (tire?.warning?.let { it > 0 } == true) {
                    drawRoundRect(color.copy(alpha = .12f), Offset(x - 4f, y - 5f), Size(17f, 44f), CornerRadius(7f))
                }
                drawRoundRect(colors.surface, Offset(x, y), Size(9f, 34f), CornerRadius(3f))
                drawRoundRect(color, Offset(x, y), Size(9f, 34f), CornerRadius(3f), style = Stroke(1.8f))
                drawLine(color.copy(alpha = .65f), Offset(x + 4.5f, y + 6f), Offset(x + 4.5f, y + 28f), 1f, StrokeCap.Round)
            }
        }
    }
}
