package com.cabin.carlink

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarlinkEngineServiceTest {
    internal class Host : CarlinkEngineService() {
        var creates = 0
        var terminated = false
        var fail = false
        val receiver = Binder()
        override fun createEngine(): IBinder {
            creates++
            check(!fail) { "Native dependency is unavailable" }
            return receiver
        }
        override fun terminateEngineProcess() { terminated = true }
    }

    @Test fun `binding alone does not start native code and IPC creates one app-owned receiver`() {
        val controller = Robolectric.buildService(Host::class.java).create()
        val service = controller.get()
        val host = service.onBind(Intent())
        assertEquals(0, service.creates)
        assertSame(service.receiver, CarlinkEngineConnection.create(host))
        assertSame(service.receiver, CarlinkEngineConnection.create(host))
        assertEquals(1, service.creates)
        controller.destroy()
        assertTrue(service.terminated)
    }

    @Test fun `library errors reach the client instead of returning a missing engine`() {
        val controller = Robolectric.buildService(Host::class.java).create()
        val service = controller.get().apply { fail = true }
        val error = assertThrows(IllegalStateException::class.java) {
            CarlinkEngineConnection.create(service.onBind(Intent()))
        }
        assertTrue(error.message.orEmpty().contains("Native dependency is unavailable"))
        controller.destroy()
        assertTrue(service.terminated)
    }
}
