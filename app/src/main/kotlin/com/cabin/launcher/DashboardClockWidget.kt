package com.cabin.launcher

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun DashboardClockWidget() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val time by produceState(System.currentTimeMillis()) { while (true) { delay(1_000); value = System.currentTimeMillis() } }
    val date = Date(time)
    val calendar = Calendar.getInstance().apply { timeInMillis = time }
    BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
        val large = maxWidth >= 280.dp && maxHeight >= 320.dp
        val clockSize = minOf(maxWidth * .65f, maxHeight * .48f, 230.dp)
        val showDate = maxHeight >= 140.dp
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (large) {
                Canvas(Modifier.size(clockSize)) {
                    val radius = size.minDimension / 2f
                    val center = Offset(size.width / 2, size.height / 2)
                    fun point(angle: Double, length: Float) = center + Offset((cos(angle) * length).toFloat(), (sin(angle) * length).toFloat())
                    for (tick in 0..59) {
                        val angle = tick * Math.PI / 30 - Math.PI / 2
                        drawLine(if (tick % 5 == 0) colors.primary else colors.outline.copy(alpha = .35f),
                            point(angle, radius * if (tick % 5 == 0) .84f else .91f), point(angle, radius * .98f),
                            if (tick % 5 == 0) 2.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
                    }
                    val minutes = calendar.get(Calendar.MINUTE)
                    val hours = calendar.get(Calendar.HOUR) + minutes / 60.0
                    drawLine(colors.onSurface, center, point(hours * Math.PI / 6 - Math.PI / 2, radius * .48f), 5.dp.toPx(), StrokeCap.Round)
                    drawLine(colors.primary, center, point(minutes * Math.PI / 30 - Math.PI / 2, radius * .70f), 3.dp.toPx(), StrokeCap.Round)
                    drawCircle(colors.primary, 5.dp.toPx(), center)
                }
                Spacer(Modifier.height(28.dp))
            }
            Text(android.text.format.DateFormat.getTimeFormat(context).format(date),
                style = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Light, maxLines = 1)
            if (showDate) {
                Spacer(Modifier.height(12.dp))
                Text(android.text.format.DateFormat.getMediumDateFormat(context).format(date),
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            }
        }
    }
}
