package com.cabin.platform
import org.junit.Assert.*
import org.junit.Test

class StreamingStabilityTest {
    @Test fun requiresFullContinuousHealthyWindowAndRestartsAfterStall() {
        val stability = StreamingStability()
        assertFalse(stability.observe(0, true))
        assertFalse(stability.observe(29000, true))
        assertTrue(stability.observe(30000, true))
        assertFalse(stability.observe(31000, false))
        assertFalse(stability.observe(50000, true))
        assertFalse(stability.observe(79999, true))
        assertTrue(stability.observe(80000, true))
    }
    @Test fun backwardClockCannotAccidentallyQualifyAsStable() {
        val stability = StreamingStability(10)
        assertFalse(stability.observe(100, true))
        assertFalse(stability.observe(50, true))
        assertTrue(stability.observe(60, true))
    }
}
