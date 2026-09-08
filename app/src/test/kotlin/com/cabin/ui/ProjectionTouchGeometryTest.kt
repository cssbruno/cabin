package com.cabin.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionTouchGeometryTest {
    @Test
    fun `CarPlay side letterbox maps surface left and right edges without translation`() {
        // Climate shrinks an 800-wide container to a 444x250 fitted video surface.
        assertEquals(0f, normalizeProjectionTouchCoordinate(0f, 444, 800), 0f)
        assertEquals(0.5f, normalizeProjectionTouchCoordinate(222f, 444, 800), 0f)
        assertEquals(1f, normalizeProjectionTouchCoordinate(444f, 444, 800), 0f)
    }

    @Test
    fun `CarPlay top and bottom letterbox map surface local y edges directly`() {
        assertEquals(0f, normalizeProjectionTouchCoordinate(0f, 250, 480), 0f)
        assertEquals(0.5f, normalizeProjectionTouchCoordinate(125f, 250, 480), 0f)
        assertEquals(1f, normalizeProjectionTouchCoordinate(250f, 250, 480), 0f)
    }

    @Test
    fun `Android Auto vertical center crop keeps existing positive offset and denominator`() {
        assertEquals(0f, normalizeProjectionTouchCoordinate(100f, 600, 400), 0f)
        assertEquals(1f / 3f, normalizeProjectionTouchCoordinate(300f, 600, 400), 0.00001f)
        assertEquals(2f / 3f, normalizeProjectionTouchCoordinate(500f, 600, 400), 0.00001f)
    }

    @Test
    fun `Android Auto horizontal center crop keeps existing positive offset and denominator`() {
        assertEquals(0f, normalizeProjectionTouchCoordinate(100f, 1000, 800), 0f)
        assertEquals(0.4f, normalizeProjectionTouchCoordinate(500f, 1000, 800), 0f)
        assertEquals(0.8f, normalizeProjectionTouchCoordinate(900f, 1000, 800), 0f)
    }

    @Test
    fun `equal extents preserve unshifted normalization`() {
        assertEquals(0f, normalizeProjectionTouchCoordinate(0f, 800, 800), 0f)
        assertEquals(0.5f, normalizeProjectionTouchCoordinate(400f, 800, 800), 0f)
        assertEquals(1f, normalizeProjectionTouchCoordinate(800f, 800, 800), 0f)
    }

    @Test
    fun `drag outside view does not introduce new clamping behavior`() {
        assertEquals(-0.1f, normalizeProjectionTouchCoordinate(-80f, 800, 800), 0f)
        assertEquals(1.1f, normalizeProjectionTouchCoordinate(880f, 800, 800), 0f)
    }

    @Test
    fun `invalid geometry never divides by zero or emits nonfinite values`() {
        assertEquals(0f, normalizeProjectionTouchCoordinate(10f, 0, 800), 0f)
        assertEquals(0f, normalizeProjectionTouchCoordinate(10f, 800, -1), 0f)
        assertEquals(0f, normalizeProjectionTouchCoordinate(Float.NaN, 800, 800), 0f)
    }
}
