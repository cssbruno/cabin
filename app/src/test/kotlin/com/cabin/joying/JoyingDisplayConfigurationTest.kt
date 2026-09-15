package com.cabin.joying

import org.junit.Assert.*
import org.junit.Test

class JoyingDisplayConfigurationTest {
    @Test fun `landscape capped without stretching`() {
        assertEquals(JoyingDisplayConfiguration(1920, 1080), JoyingDisplayConfiguration.fromDisplay(3840, 2160))
    }
    @Test fun `portrait preserves portrait orientation and maximum`() {
        assertEquals(JoyingDisplayConfiguration(1080, 1920), JoyingDisplayConfiguration.fromDisplay(2160, 3840))
    }
    @Test fun `screen payload carries negotiated geometry and stock FPS marker`() {
        assertArrayEquals(intArrayOf(0, 0, 1024, 600, 1024, 600, 221, 129, 0, 0x53667073, 30),
            JoyingDisplayConfiguration.fromDisplay(1024, 600).nativeValues())
    }
    @Test fun `odd dimensions aligned to eight pixels`() {
        assertEquals(JoyingDisplayConfiguration(1280, 720), JoyingDisplayConfiguration.fromDisplay(1283, 723))
    }
}
