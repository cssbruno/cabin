package com.cabin.platform

import android.content.*
import android.os.*
import androidx.test.core.app.ApplicationProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyuAudioSourceMonitorTest {
    private lateinit var context: TestContext
    private lateinit var monitor: SyuAudioSourceMonitor
    private lateinit var worker: HandlerThread
    @Before fun setup() {
        context = TestContext(ApplicationProvider.getApplicationContext())
        monitor = SyuAudioSourceMonitor(context)
        worker = SyuAudioSourceMonitor::class.java.getDeclaredField("worker").let { it.isAccessible = true; it.get(monitor) as HandlerThread }
        monitor.start(); drain()
    }
    private fun drain() = shadowOf(worker.looper).idle()
    @After fun close() {
        val looper = worker.looper
        monitor.close()
        try { shadowOf(looper).idle() } catch (e: IllegalStateException) { if (e.message != "Looper is quitting") throw e }
        worker.join(1000)
        assertFalse(worker.isAlive)
    }
    @Test fun `reads MAIN source callback without sending control commands`() {
        context.connect(); drain()
        assertEquals(SyuAudioSourceState(true, null), monitor.state.value)
        context.remote.emit(1); drain()
        assertEquals(SyuAudioSourceState(true, 1), monitor.state.value)
        context.remote.emit(3); drain()
        assertEquals(3, monitor.state.value.sourceId)
        assertEquals(listOf(3), context.remote.transactions)
        assertEquals(1, context.remote.cached)
    }
    @Test fun `disconnect clears source and old callbacks cannot restore it`() {
        context.connect(); drain()
        val old = context.remote.callback!!
        context.remote.emit(10); drain()
        val connection = context.connection!!
        connection.onServiceDisconnected(ComponentName("com.syu.ms", "app.ToolkitService")); drain()
        context.remote.emit(1, target = old); drain()
        assertEquals(SyuAudioSourceState(), monitor.state.value)
        assertTrue(context.remote.transactions.contains(4))
    }
    @Test fun `invalid sources and unrelated fields are not presented as radio`() {
        context.connect(); drain()
        context.remote.emit(1, field = 7); drain(); assertNull(monitor.state.value.sourceId)
        context.remote.emit(99); drain(); assertNull(monitor.state.value.sourceId)
        context.remote.emit(0); drain(); assertEquals(0, monitor.state.value.sourceId)
    }
    @Test fun `null binding retries and subsequently receives a source`() {
        val initial = context.connection!!
        initial.onNullBinding(null); drain()
        assertEquals(SyuAudioSourceState(), monitor.state.value)
        assertNull(context.connection)
        shadowOf(worker.looper).idleFor(java.time.Duration.ofSeconds(5))
        assertNotSame(initial, context.connection)
        context.connect(); drain()
        context.remote.emit(5); drain()
        assertEquals(SyuAudioSourceState(true, 5), monitor.state.value)
    }
    @Test fun `binding timeout unbinds before scheduling another attempt`() {
        val initial = context.connection!!
        shadowOf(worker.looper).idleFor(java.time.Duration.ofSeconds(10))
        assertNull(context.connection)
        assertEquals(SyuAudioSourceState(), monitor.state.value)
        shadowOf(worker.looper).idleFor(java.time.Duration.ofSeconds(5))
        assertNotNull(context.connection)
        assertNotSame(initial, context.connection)
    }
    @Test fun `cached callback during synchronous registration is acknowledged and queued`() {
        context.remote.cachedSource = 3
        context.connect(); drain()
        assertEquals(SyuAudioSourceState(true, 3), monitor.state.value)
    }
    @Test fun `remote exception clears state and ignores cached callbacks from failed registration`() {
        context.remote.cachedSource = 1
        context.remote.failRegistration = true
        context.connect(); drain()
        assertEquals(SyuAudioSourceState(), monitor.state.value)
        assertNull(context.connection)
    }
    private class TestContext(base: Context) : ContextWrapper(base) {
        val remote = MainModule()
        var connection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean { connection = conn; return true }
        override fun unbindService(conn: ServiceConnection) { if (connection === conn) connection = null }
        fun connect() {
            connection!!.onServiceConnected(ComponentName("com.syu.ms", "app.ToolkitService"), object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    assertEquals(1, code); data.enforceInterface(SyuAudioSourceMonitor.TOOLKIT)
                    assertEquals(0, data.readInt()); reply!!.writeNoException(); reply.writeStrongBinder(remote); return true
                }
            })
        }
    }
    private class MainModule : Binder() {
        var callback: IBinder? = null
        var cached = 0
        var cachedSource: Int? = null
        var failRegistration = false
        val transactions = mutableListOf<Int>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(0, flags)
            requireNotNull(reply)
            transactions += code
            assertTrue(code == 3 || code == 4)
            data.enforceInterface(SyuAudioSourceMonitor.MODULE)
            val listener = data.readStrongBinder(); assertEquals(0, data.readInt())
            if (code == 3) { cached = data.readInt(); callback = listener } else callback = null
            if (code == 3 && cachedSource != null) emit(cachedSource!!)
            if (failRegistration && code == 3) reply.writeException(SecurityException("denied")) else reply.writeNoException()
            return true
        }
        fun emit(id: Int, field: Int = 0, target: IBinder = callback!!) {
            val p = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                p.writeInterfaceToken(SyuAudioSourceMonitor.CALLBACK); p.writeInt(field); p.writeIntArray(intArrayOf(id));
                p.writeFloatArray(null); p.writeStringArray(null); assertTrue(target.transact(1, p, reply, 0)); reply.readException()
            } finally { p.recycle(); reply.recycle() }
        }
    }
}
