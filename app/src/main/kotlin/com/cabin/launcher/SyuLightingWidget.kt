package com.cabin.launcher

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.cabin.R
import com.cabin.platform.SyuLightingSetting
import com.cabin.platform.TeyesClimateState

@Composable
internal fun SyuLightingWidget(vehicle: TeyesClimateState, moving: Boolean,
    onChange: ((SyuLightingSetting, Int) -> Unit)?, onParkedAction: (() -> Unit) -> Unit) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { SyuLightingSetting.entries.size }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f)) { page ->
            val setting = SyuLightingSetting.entries[page]
            val current = vehicle.syuVehicle.lighting[setting]?.takeIf { vehicle.connected }
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(stringResource(when (setting) {
                    SyuLightingSetting.SENSITIVITY -> R.string.vehicle_light_sensitivity
                    SyuLightingSetting.HEADLIGHT_DELAY -> R.string.vehicle_headlight_delay
                    SyuLightingSetting.INTERIOR_DELAY -> R.string.vehicle_interior_delay
                }), maxLines = 1, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onParkedAction { current?.let { onChange?.invoke(setting, it - 1) } } },
                        enabled = !moving && onChange != null && current != null && current > 0,
                        modifier = Modifier.size(56.dp)) { Text("−") }
                    Text(current?.let { "${setting.values[it]}" + if (setting == SyuLightingSetting.SENSITIVITY) "/5" else " s" } ?: "—")
                    TextButton(onClick = { onParkedAction { current?.let { onChange?.invoke(setting, it + 1) } } },
                        enabled = !moving && onChange != null && current != null && current < setting.values.lastIndex,
                        modifier = Modifier.size(56.dp)) { Text("+") }
                }
            }
        }
        val scope = rememberCoroutineScope()
        PageDots(pager.currentPage, pager.pageCount, { page -> scope.launch { pager.animateScrollToPage(page) } })
    }
}
