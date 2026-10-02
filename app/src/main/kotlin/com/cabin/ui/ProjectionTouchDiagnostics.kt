package com.cabin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cabin.R

internal val projectionTouchTargets = listOf(Offset(.1f, .1f), Offset(.9f, .1f), Offset(.5f, .5f), Offset(.1f, .9f), Offset(.9f, .9f))

internal fun projectionTouchTargetAt(point: Offset, width: Float, height: Float, radius: Float): Int? {
    if (width <= 0 || height <= 0 || radius <= 0) return null
    return projectionTouchTargets.indexOfFirst { target ->
        (point - Offset(target.x * width, target.y * height)).getDistance() <= radius
    }.takeIf { it >= 0 }
}

/** Local panel input only. No calibration values or gestures are sent to the phone. */
@Composable
internal fun ProjectionTouchDiagnostics(enabled: Boolean) {
    var mode by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf(emptySet<Int>()) }
    var pointers by remember { mutableStateOf(emptyList<Offset>()) }
    var peak by remember { mutableStateOf(0) }
    var trail by remember { mutableStateOf(emptyList<Offset>()) }
    var cancellations by remember { mutableStateOf(0) }
    var pointerLosses by remember { mutableStateOf(0) }
    LaunchedEffect(enabled) {
        if (!enabled) { mode = null; pointers = emptyList() }
    }
    val ink = MaterialTheme.colorScheme.primary
    val completedInk = MaterialTheme.colorScheme.tertiary
    val backdrop = MaterialTheme.colorScheme.surfaceContainerHighest
    val canvasLabel = stringResource(if (mode == "multi") R.string.px_multitouch_canvas else R.string.px_touch_canvas)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("targets" to R.string.px_touch_test, "multi" to R.string.px_multitouch_test, "drag" to R.string.gx_drag_test).forEach { (option, label) ->
                FilterChip(selected = mode == option, enabled = enabled, onClick = {
                    mode = if (mode == option) null else option
                    hits = emptySet(); peak = 0; pointers = emptyList(); trail = emptyList(); cancellations = 0; pointerLosses = 0
                }, label = { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        if (mode != null && enabled) {
            if (mode == "drag") Text(stringResource(R.string.gx_drag_instruction))
            Text(stringResource(if (mode == "multi") R.string.px_multitouch_instruction else R.string.px_touch_instruction))
            Text(stringResource(R.string.px_touch_local))
            Box(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(280.dp).semantics { contentDescription = canvasLabel }
                .pointerInput(mode) {
                    val radius = 28.dp.toPx()
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        first.consume()
                        if (mode == "targets") {
                            projectionTouchTargetAt(first.position, size.width.toFloat(), size.height.toFloat(), radius)?.let { hits = hits + it }
                        }
                        pointers = listOf(first.position)
                        peak = maxOf(peak, 1)
                        var previousIds = setOf(first.id)
                        var finished = false
                        try {
                        do {
                            val event = awaitPointerEvent()
                            if (mode == "drag") {
                                pointerLosses += previousIds.count { id -> event.changes.none { it.id == id } }
                                trail = (trail + event.changes.first().position).takeLast(128)
                            }
                            previousIds = event.changes.filter { it.pressed }.map { it.id }.toSet()
                            pointers = event.changes.filter { it.pressed }.map { it.position }
                            peak = maxOf(peak, pointers.size)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        finished = true
                        } finally { if (!finished && mode == "drag") cancellations++ }
                    }
                }) {
                drawRect(backdrop)
                if (mode == "targets") {
                    projectionTouchTargets.forEachIndexed { index, target ->
                        val center = Offset(target.x * size.width, target.y * size.height)
                        drawCircle(if (index in hits) completedInk else ink, 24.dp.toPx(), center,
                            style = Stroke(if (index in hits) 8.dp.toPx() else 3.dp.toPx()))
                        drawLine(ink, center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), 2.dp.toPx())
                        drawLine(ink, center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), 2.dp.toPx())
                    }
                }
                if (mode == "drag") trail.zipWithNext().forEach { (start, end) -> drawLine(ink, start, end, 4.dp.toPx()) }
                pointers.forEach { drawCircle(ink.copy(alpha = .65f), 20.dp.toPx(), it) }
            }
            if (mode == "drag") OutlinedButton(onClick = { trail = emptyList() }, modifier = Modifier.align(Alignment.TopEnd).heightIn(min = 56.dp)) {
                Text(stringResource(R.string.gx_drag_overlay))
            }
            }
            if (mode == "drag") {
                Text(stringResource(R.string.gx_drag_result, cancellations, pointerLosses))
            } else if (mode == "multi") {
                Text(stringResource(R.string.px_multitouch_count, pointers.size, peak))
                if (peak >= 2) Text(stringResource(R.string.px_multitouch_pass))
            } else {
                Text(stringResource(R.string.px_touch_progress, hits.size, projectionTouchTargets.size))
                if (hits.size == projectionTouchTargets.size) Text(stringResource(R.string.px_touch_pass))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { hits = emptySet(); peak = 0; pointers = emptyList(); trail = emptyList(); cancellations = 0; pointerLosses = 0 }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.px_touch_reset))
                }
                OutlinedButton(onClick = { mode = null; pointers = emptyList() }, modifier = Modifier.heightIn(min = 56.dp)) {
                    Text(stringResource(R.string.diagnostics_stop))
                }
            }
        }
    }
}
