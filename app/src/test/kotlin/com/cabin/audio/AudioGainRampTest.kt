package com.cabin.audio

import org.junit.Assert.*
import org.junit.Test

class AudioGainRampTest {
    @Test fun rampHonorsFractionalDurationWithoutOvershoot() {
        assertEquals(1f, interpolatedGain(1f, .2f, -1, 500), .0001f)
        assertEquals(.6f, interpolatedGain(1f, .2f, 250, 500), .0001f)
        assertEquals(.2f, interpolatedGain(1f, .2f, 1000, 500), .0001f)
        assertEquals(.6f, interpolatedGain(.2f, 1f, 250, 500), .0001f)
    }
    @Test fun zeroDurationAndCorruptWireValuesAreSafe() {
        assertEquals(.2f, interpolatedGain(1f, .2f, 0, 0), .0001f)
        assertEquals(1f, interpolatedGain(1f, Float.NaN, 10, 0), .0001f)
        assertEquals(0f, interpolatedGain(1f, -1f, 10, 0), .0001f)
    }
}
