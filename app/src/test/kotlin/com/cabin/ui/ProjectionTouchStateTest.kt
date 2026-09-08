package com.cabin.ui

import android.view.InputDevice
import android.view.MotionEvent
import com.cabin.protocol.MessageSerializer.TouchPoint
import com.cabin.protocol.MultiTouchAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectionTouchStateTest {
    private val sent = mutableListOf<List<TouchPoint>>()
    private val state = ProjectionTouchState { sent += it }

    @Test
    fun `cancel releases every pointer even if event lists only one and geometry is gone`() {
        startTwoPointers()
        dispatch(MotionEvent.ACTION_CANCEL, 0, listOf(2), validGeometry = false)

        assertEquals(listOf(2, 7), sent.last().map { it.id })
        assertTrue(sent.last().all { it.action == MultiTouchAction.UP })
        assertEquals(0.2f, sent.last()[0].x, 0f)
        assertEquals(0.7f, sent.last()[1].x, 0f)
        val count = sent.size
        state.cancel()
        assertEquals(count, sent.size)
    }

    @Test
    fun `new gesture after cancellation contains no old pointers`() {
        startTwoPointers()
        dispatch(MotionEvent.ACTION_CANCEL, 0, listOf(2))
        dispatch(MotionEvent.ACTION_DOWN, 0, listOf(4))

        assertEquals(listOf(4), sent.last().map { it.id })
        assertEquals(MultiTouchAction.DOWN, sent.last().single().action)
    }

    @Test
    fun `session clear silently drops old move and up before a new down`() {
        startTwoPointers()
        val count = sent.size
        state.clear()
        dispatch(MotionEvent.ACTION_MOVE, 0, listOf(2, 7))
        dispatch(MotionEvent.ACTION_UP, 0, listOf(2))
        state.cancel()
        assertEquals(count, sent.size)

        dispatch(MotionEvent.ACTION_DOWN, 0, listOf(9))
        assertEquals(listOf(9), sent.last().map { it.id })
    }

    @Test
    fun `surface disposal cancellation releases once and ignores later terminal events`() {
        startTwoPointers()
        state.cancel()
        val count = sent.size
        state.cancel()
        dispatch(MotionEvent.ACTION_UP, 0, listOf(2))
        assertEquals(count, sent.size)
        assertEquals(listOf(MultiTouchAction.UP, MultiTouchAction.UP), sent.last().map { it.action })
    }

    @Test
    fun `pointer up removes only that pointer and cancel releases the remaining finger`() {
        startTwoPointers()
        dispatch(MotionEvent.ACTION_POINTER_UP, 1, listOf(2, 7))
        assertEquals(MultiTouchAction.UP, sent.last().single { it.id == 7 }.action)
        state.cancel()
        assertEquals(listOf(2), sent.last().map { it.id })
        assertEquals(MultiTouchAction.UP, sent.last().single().action)
    }

    @Test
    fun `fresh down recovers a lost cancellation without carrying old fingers`() {
        startTwoPointers()
        dispatch(MotionEvent.ACTION_DOWN, 0, listOf(8))
        assertEquals(listOf(2, 7), sent[sent.lastIndex - 1].map { it.id })
        assertTrue(sent[sent.lastIndex - 1].all { it.action == MultiTouchAction.UP })
        assertEquals(listOf(8), sent.last().map { it.id })
    }

    private fun startTwoPointers() {
        dispatch(MotionEvent.ACTION_DOWN, 0, listOf(2))
        dispatch(MotionEvent.ACTION_POINTER_DOWN, 1, listOf(2, 7))
    }

    private fun dispatch(action: Int, actionIndex: Int, ids: List<Int>, validGeometry: Boolean = true) {
        val event = MotionEvent.obtain(
            0L, 1L, action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), ids.size,
            ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray(),
            ids.map { id -> MotionEvent.PointerCoords().apply { x = id * 10f; y = 50f; pressure = 1f; size = 1f } }.toTypedArray(),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            state.handle(event, if (validGeometry) 100 else 0, 100, 100, 100)
        } finally {
            event.recycle()
        }
    }
}
