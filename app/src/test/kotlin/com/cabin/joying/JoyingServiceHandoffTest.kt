package com.cabin.joying

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class JoyingServiceHandoffTest {
    @Test fun `ordinary install never pretends stopping service releases stock socket`() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication() as Context) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
            override fun stopService(intent: Intent): Boolean {
                fail("Stock onDestroy keeps its socket open")
                return true
            }
        }
        assertThrows(SecurityException::class.java) { JoyingServiceHandoff.releaseStockClient(context) }
    }

    @Test fun `already running daemon does not request privileged startup`() {
        assertEquals("daemon", JoyingServiceHandoff.awaitNativeService(
            { "daemon" }, { fail("Already running") }, { fail("No wait needed") }))
    }

    @Test fun `startup waits for asynchronous firmware service registration`() {
        var lookups = 0
        var starts = 0
        var pauses = 0
        assertEquals("daemon", JoyingServiceHandoff.awaitNativeService(
            { if (++lookups == 4) "daemon" else null }, { starts++ }, { pauses++ }))
        assertEquals(1, starts)
        assertEquals(3, pauses)
    }

    @Test fun `missing daemon times out without repeated startup requests`() {
        var starts = 0
        var pauses = 0
        assertThrows(IllegalStateException::class.java) {
            JoyingServiceHandoff.awaitNativeService<String>({ null }, { starts++ }, { pauses++ })
        }
        assertEquals(1, starts)
        assertEquals(25, pauses)
    }
}
