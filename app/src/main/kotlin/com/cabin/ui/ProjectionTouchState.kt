package com.cabin.ui

import android.view.MotionEvent
import com.cabin.protocol.MessageSerializer.TouchPoint
import com.cabin.protocol.MultiTouchAction

/** Gesture state shared by UI events and transport callbacks that end a phone session. */
internal class ProjectionTouchState(private val send: (List<TouchPoint>) -> Unit) {
    private val activeTouches = linkedMapOf<Int, TouchPoint>()

    /** Release the whole gesture, including pointers absent from Android's cancellation event. */
    @Synchronized
    fun cancel() {
        val releases = activeTouches.values.map { it.copy(action = MultiTouchAction.UP) }
        clear()
        if (releases.isNotEmpty()) send(releases)
    }

    /** The transport has already ended; forget pointers without sending into another session. */
    @Synchronized
    fun clear() {
        activeTouches.clear()
    }

    @Synchronized
    fun handle(
        event: MotionEvent,
        surfaceWidth: Int,
        surfaceHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
    ) {
        // Cancellation remains meaningful after layout/surface dimensions have disappeared.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            cancel()
            return
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) return

        fun point(index: Int, action: MultiTouchAction) = TouchPoint(
            x = normalizeProjectionTouchCoordinate(event.getX(index), surfaceWidth, containerWidth),
            y = normalizeProjectionTouchCoordinate(event.getY(index), surfaceHeight, containerHeight),
            action = action,
            id = event.getPointerId(index),
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // DOWN begins a new Android gesture, even if an earlier terminal event was lost.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
                val down = point(event.actionIndex, MultiTouchAction.DOWN)
                activeTouches[down.id] = down
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val move = point(i, MultiTouchAction.MOVE)
                    activeTouches[move.id]?.let { existing ->
                        // Preserve the existing normalized 0.003 MOVE deadband.
                        val dx = kotlin.math.abs(existing.x - move.x) * 1000
                        val dy = kotlin.math.abs(existing.y - move.y) * 1000
                        if (dx > 3 || dy > 3) activeTouches[move.id] = move
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val up = point(event.actionIndex, MultiTouchAction.UP)
                // A surface/session reset may be followed by the old gesture's UP.
                if (up.id !in activeTouches) return
                activeTouches[up.id] = up
            }
            else -> return
        }

        if (activeTouches.isNotEmpty()) send(activeTouches.values.toList())
        activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
    }
}
