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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SyuRadioControllerTest {
    private lateinit var context: TestContext
    private lateinit var client: SyuRadioController
    private lateinit var worker: HandlerThread
    @Before fun setup() {
        context = TestContext(ApplicationProvider.getApplicationContext())
        client = SyuRadioController(context)
        worker = SyuRadioController::class.java.getDeclaredField("worker").let { it.isAccessible = true; it.get(client) as HandlerThread }
        client.start(); drain()
    }
    private fun drain() = shadowOf(worker.looper).idle()
    @After fun cleanup() {
        val looper = worker.looper
        client.close()
        try { shadowOf(looper).idle() } catch (e: IllegalStateException) { if (e.message != "Looper is quitting" && !(e.message?.startsWith("post to Handler") == true && e.message?.endsWith("Is handler thread dead?") == true)) throw e }
        worker.join(1000); assertFalse(worker.isAlive)
    }
    @Test fun `cached callbacks initialize station and commands do not fake feedback`() {
        assertEquals(10170, client.state.value.frequency)
        val epoch = client.state.value.epoch
        client.command(epoch, 5); drain()
        assertEquals(listOf(5 to null), context.radio.commands)
        assertEquals(10170, client.state.value.frequency)
        client.command(epoch, 7, 65536); drain() // rate limited
        assertEquals(1, context.radio.commands.size)
        shadowOf(worker.looper).idleFor(301, TimeUnit.MILLISECONDS)
        client.command(epoch, 7, 65536); drain()
        assertEquals(7 to listOf(65536), context.radio.commands.last())
    }
    @Test fun `disconnect clears readings and rejects callbacks and old commands`() {
        val old = client.state.value.epoch
        val callback = context.radio.listener!!
        context.connection!!.onServiceDisconnected(ComponentName("com.syu.ms", "Toolkit")); drain()
        assertFalse(client.state.value.connected)
        context.radio.emit(1, listOf(9950), callback); drain()
        client.command(old, 5); drain()
        assertNull(client.state.value.frequency)
        assertTrue(context.radio.commands.isEmpty())
    }
    @Test fun `band changes invalidate queued commands and stale values expire`() {
        val old = client.state.value.epoch
        context.radio.emit(0, listOf(0)); drain()
        assertNull(client.state.value.frequency)
        client.command(old, 5); drain()
        assertTrue(context.radio.commands.isEmpty())
        context.radio.emit(1, listOf(1000)); drain()
        assertEquals(1000, client.state.value.frequency)
        context.radio.cached = false
        shadowOf(worker.looper).idleFor(31, TimeUnit.SECONDS)
        assertNull(client.state.value.frequency)
        assertTrue(client.state.value.presets.isEmpty())
    }
    private class TestContext(base: Context) : ContextWrapper(base) {
        val radio = Radio()
        var connection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            conn.onServiceConnected(ComponentName("com.syu.ms", "Toolkit"), object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface(SyuRadioController.TOOLKIT)
                    assertEquals(1, code); assertEquals(1, data.readInt())
                    reply!!.writeNoException(); reply.writeStrongBinder(radio); return true
                }
            })
            return true
        }
        override fun unbindService(conn: ServiceConnection) {}
    }
    private class Radio : Binder() {
        var listener: IBinder? = null
        var cached = true
        val commands = mutableListOf<Pair<Int,List<Int>?>>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(SyuRadioController.MODULE)
            if (code == 3 || code == 4) {
                listener = data.readStrongBinder()
                val field = data.readInt()
                if (code == 3 && cached) {
                    data.readInt()
                    emit(field, when (field) { 0 -> listOf(65536); 1 -> listOf(10170); else -> listOf(65536, 9950) })
                }
            } else {
                assertEquals(1, code)
                commands += data.readInt() to data.createIntArray()?.toList()
            }
            reply!!.writeNoException(); return true
        }
        fun emit(field: Int, values: List<Int>, target: IBinder = listener!!) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SyuRadioController.CALLBACK)
                data.writeInt(field); data.writeIntArray(values.toIntArray()); data.writeFloatArray(null); data.writeStringArray(null)
                assertTrue(target.transact(1, data, reply, 0)); reply.readException()
            } finally { data.recycle(); reply.recycle() }
        }
    }
}
