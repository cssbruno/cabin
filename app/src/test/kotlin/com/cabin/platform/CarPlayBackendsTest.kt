package com.cabin.platform

import org.junit.Assert.*
import org.junit.Test

class CarPlayBackendsTest {
    @Test fun `single available source is selected automatically`() {
        assertEquals(CarPlayBackend.JOYING, resolveCarPlayBackends(true, false, null).selected)
        assertEquals(CarPlayBackend.DONGLE, resolveCarPlayBackends(false, true, null).selected)
    }
    @Test fun `both sources require choice and preserve a valid choice`() {
        val both = resolveCarPlayBackends(true, true, null)
        assertEquals(listOf(CarPlayBackend.DONGLE, CarPlayBackend.JOYING), both.available)
        assertNull(both.selected)
        CarPlayBackend.entries.forEach {
            assertEquals(it, resolveCarPlayBackends(true, true, it).selected)
        }
    }
    @Test fun `explicit choice survives temporary USB removal and hardware changes`() {
        assertEquals(CarPlayBackend.DONGLE, resolveCarPlayBackends(true, false, CarPlayBackend.DONGLE).selected)
        assertEquals(CarPlayBackend.JOYING, resolveCarPlayBackends(false, true, CarPlayBackend.JOYING).selected)
    }
    @Test fun `no hardware offers no phantom source`() {
        val none = resolveCarPlayBackends(false, false, CarPlayBackend.JOYING)
        assertTrue(none.available.isEmpty())
        assertEquals(CarPlayBackend.JOYING, none.selected)
    }
}
