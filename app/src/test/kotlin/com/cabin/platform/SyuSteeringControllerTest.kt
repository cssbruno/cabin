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
class SyuSteeringControllerTest {
    private lateinit var context: TestContext
    private lateinit var client: SyuSteeringController
    private lateinit var worker: HandlerThread
    @Before fun setup() {
        context = TestContext(ApplicationProvider.getApplicationContext())
        client = SyuSteeringController(context)
        worker = SyuSteeringController::class.java.getDeclaredField("worker").let { it.isAccessible = true; it.get(client) as HandlerThread }
        client.start(); drain()
    }
    private fun drain() = shadowOf(worker.looper).idle()
    private fun tick() = shadowOf(worker.looper).idleFor(201, TimeUnit.MILLISECONDS)
    private val epoch get() = client.state.value.epoch
    @After fun cleanup() {
        val looper = worker.looper
        client.close()
        try { shadowOf(looper).idle() } catch (e: IllegalStateException) {
            if (e.message != "Looper is quitting" && !(e.message?.startsWith("post to Handler") == true && e.message?.endsWith("Is handler thread dead?") == true)) throw e
        }
        worker.join(1000); assertFalse(worker.isAlive)
    }
    @Test fun `startup only subscribes and ADC needs a fresh physical signal`() {
        assertTrue(client.state.value.connected)
        assertTrue(context.module.commands.isEmpty())
        context.module.emit(4, listOf(1)); context.module.emit(2, listOf(83)); drain()
        client.begin(epoch, SteeringLearningMode.ADC); drain(); tick()
        client.assign(epoch, 3); drain()
        assertEquals(listOf(SteeringCommand(2, listOf(1))), context.module.commands)
        context.module.emit(4, listOf(1)); context.module.emit(2, listOf(83)); drain(); tick()
        client.assign(epoch, 3); drain()
        assertEquals(SteeringCommand(1, listOf(3)), context.module.commands.last())
        assertEquals(3, client.state.value.pending)
        assertTrue(client.state.value.learnedAdc.isEmpty())
        context.module.emit(1, listOf(4, 83)); drain()
        assertEquals(3, client.state.value.pending)
        context.module.emit(1, listOf(3, 83)); drain()
        assertNull(client.state.value.pending)
        assertEquals(SteeringHardwareStatus.LEARNED, client.state.value.status)
        tick(); client.save(epoch); drain()
        assertEquals(listOf(SteeringCommand(4), SteeringCommand(2, listOf(0))), context.module.commands.takeLast(2))
        assertEquals(SteeringHardwareStatus.SAVE_SENT, client.state.value.status)
        assertNull(client.state.value.mode)
    }
    @Test fun `stale ADC and sentinel cannot be assigned`() {
        client.begin(epoch, SteeringLearningMode.ADC); drain()
        context.module.emit(4, listOf(1)); context.module.emit(2, listOf(50)); drain(); tick()
        client.assign(epoch, 0); drain()
        assertEquals(1, context.module.commands.size)
        context.module.emit(2, listOf(83)); drain()
        shadowOf(worker.looper).idleFor(4, TimeUnit.SECONDS)
        client.assign(epoch, 0); drain()
        assertEquals(1, context.module.commands.size)
    }
    @Test fun `MCU custom assignment waits for feedback and ending saves`() {
        client.begin(epoch, SteeringLearningMode.MCU); drain(); tick()
        client.assign(epoch, 44, 47); drain()
        assertEquals(listOf(SteeringCommand(5, listOf(1)), SteeringCommand(5, listOf(4)), SteeringCommand(6, listOf(44, 47))), context.module.commands)
        assertTrue(client.state.value.mcuKeys.isEmpty())
        context.module.emit(6, listOf(44, 47)); drain()
        assertEquals(SteeringHardwareStatus.LEARNED, client.state.value.status)
        client.stop(epoch); drain()
        assertEquals(SteeringCommand(5, listOf(2)), context.module.commands.last())
        assertEquals(SteeringHardwareStatus.SAVE_SENT, client.state.value.status)
    }
    @Test fun `disconnect rejects old callbacks and never replays assignments`() {
        val old = epoch
        val callback = context.module.listener!!
        client.begin(epoch, SteeringLearningMode.ADC); drain()
        context.connection!!.onServiceDisconnected(ComponentName("com.syu.ms", "Toolkit")); drain()
        val count = context.module.commands.size
        context.module.emit(2, listOf(99), callback); drain()
        client.assign(old, 1); drain()
        assertNull(client.state.value.adc)
        shadowOf(worker.looper).idleFor(5, TimeUnit.SECONDS)
        assertTrue(client.state.value.connected)
        assertNotEquals(old, epoch)
        assertEquals(count, context.module.commands.size)
        assertNull(client.state.value.mode)
    }
    @Test fun `clear is explicit and prohibited during learning`() {
        client.begin(epoch, SteeringLearningMode.ADC); drain(); tick()
        client.clear(epoch, SteeringLearningMode.ADC); drain()
        assertEquals(1, context.module.commands.size)
        client.stop(epoch); drain(); tick()
        client.clear(epoch, SteeringLearningMode.ADC); drain()
        assertEquals(SteeringCommand(3), context.module.commands.last())
    }
    @Test fun `pending times out without inventing a learned mapping`() {
        client.begin(epoch, SteeringLearningMode.MCU); drain(); tick()
        client.assign(epoch, 3); drain()
        shadowOf(worker.looper).idleFor(11, TimeUnit.SECONDS)
        assertNull(client.state.value.pending)
        assertTrue(client.state.value.mcuKeys.isEmpty())
        assertEquals(SteeringHardwareStatus.TIMED_OUT, client.state.value.status)
        shadowOf(worker.looper).idleFor(50, TimeUnit.SECONDS)
        assertNull(client.state.value.mode)
        assertEquals(SteeringCommand(5, listOf(2)), context.module.commands.last())
    }
    @Test fun `invalid namespaces cannot produce assignment commands`() {
        assertNull(SyuSteeringProtocol.assign(SteeringLearningMode.ADC, 13))
        assertNull(SyuSteeringProtocol.assign(SteeringLearningMode.ADC, 0, 3))
        assertNull(SyuSteeringProtocol.assign(SteeringLearningMode.MCU, 25, 1))
        assertNull(SyuSteeringProtocol.assign(SteeringLearningMode.MCU, 24, 3))
        assertNull(SyuSteeringProtocol.assign(SteeringLearningMode.MCU, 45, 47))
        assertNull(SyuSteeringProtocol.feedback(SteeringHardwareState(), 2, listOf(256)))
        assertNull(SyuSteeringProtocol.feedback(SteeringHardwareState(), 4, listOf(2)))
    }
    private class TestContext(base: Context) : ContextWrapper(base) {
        val module = Module()
        var connection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            connection = conn
            conn.onServiceConnected(ComponentName("com.syu.ms", "Toolkit"), object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    data.enforceInterface("com.syu.ipc.IRemoteToolkit")
                    assertEquals(1, code); assertEquals(10, data.readInt())
                    reply!!.writeNoException(); reply.writeStrongBinder(module); return true
                }
            })
            return true
        }
        override fun unbindService(conn: ServiceConnection) {}
    }
    private class Module : Binder() {
        var listener: IBinder? = null
        val commands = mutableListOf<SteeringCommand>()
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(SyuBinderTransport.MODULE_DESCRIPTOR)
            assertEquals(IBinder.FLAG_ONEWAY, flags); assertNull(reply)
            if (code == 3 || code == 4) {
                listener = data.readStrongBinder()
                val field = data.readInt()
                if (code == 3) {
                    assertEquals(1, data.readInt())
                    if (field == 5) emit(5, listOf(1))
                }
            } else {
                assertEquals(1, code)
                commands += SteeringCommand(data.readInt(), data.createIntArray()!!.toList())
                assertNull(data.createFloatArray()); assertNull(data.createStringArray())
            }
            return true
        }
        fun emit(field: Int, values: List<Int>, target: IBinder = listener!!) {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(SyuSteeringWire.CALLBACK)
                data.writeInt(field); data.writeIntArray(values.toIntArray()); data.writeFloatArray(null); data.writeStringArray(null)
                assertTrue(target.transact(1, data, null, IBinder.FLAG_ONEWAY))
            } finally { data.recycle() }
        }
    }
}
