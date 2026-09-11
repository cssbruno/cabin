package com.cabin.launcher

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt

/** Edit-mode only. Claim two-finger input before the single-finger drag detector sees it. */
internal fun Modifier.widgetPinchResize(tile: DashboardTile,
    columns: Int = DASHBOARD_COLUMNS, rows: Int = DASHBOARD_ROWS,
    onPreview: (Pair<Int, Int>?) -> Unit, onResize: (Int, Int) -> Unit): Modifier = pointerInput(tile, columns, rows) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var scale = 1f
        var active = false
        var target = tile.width to tile.height
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    active = true
                    scale = (scale * event.calculateZoom()).coerceIn(0.125f, 8f)
                    target = (tile.width * scale).roundToInt().coerceIn(1, columns - tile.x) to
                        (tile.height * scale).roundToInt().coerceIn(1, rows - tile.y)
                    onPreview(target)
                }
                if (active) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
            if (active && target != tile.width to tile.height) onResize(target.first, target.second)
        } finally { onPreview(null) }
    }
}
