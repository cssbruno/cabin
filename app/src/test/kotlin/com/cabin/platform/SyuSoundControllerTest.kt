package com.cabin.platform

import android.content.*
import android.os.*
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
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
class SyuSoundControllerTest {
    private lateinit var context: TestContext
    private lateinit var controller: SyuSoundController
    private lateinit var worker: HandlerThread
    private var time = 1000L
    @Before fun setup() {
        context = TestContext(ApplicationProvider.getApplicationContext())
        controller = SyuSoundController(context) { time }
        worker = SyuSoundController::class.java.getDeclaredField("worker").let { it.isAccessible = true; it.get(controller) as HandlerThread }
        controller.start(); drain()
    }
    private fun drain() = shadowOf(worker.looper).idle()
    private fun advance(ms: Long) { time += ms; shadowOf(worker.looper).idleFor(Duration.ofMillis(ms)) }
    @After fun close() {
        val looper = worker.looper
        controller.close()
        try { shadowOf(looper).idle() } catch (e: IllegalStateException) { if (e.message != "Looper is quitting" && !(e.message?.startsWith("post to Handler") == true && e.message?.endsWith("Is handler thread dead?") == true)) throw e }
        worker.join(1000); assertFalse(worker.isAlive)
    }
    @Test fun `cached identity precedes settings and synchronous callbacks do not deadlock`() {
        context.connect(); drain()
        assertEquals(11, controller.state.value.moduleId)
        assertEquals(listOf(1, 2, 3, 8, 9, 10, 11, 26), context.remote.registrations)
        assertEquals(listOf(8, 8), controller.state.value.samples[8])
        assertTrue(context.remote.commands.isEmpty())
    }
    @Test fun `volume uses firmware steps and stays on confirmed feedback`() {
        context.connect(); drain()
        val epoch = controller.state.value.epoch
        controller.adjustVolume(epoch, -1); drain()
        assertTrue(context.remote.commands.isEmpty())
        context.remote.emit(2, listOf(12)); context.remote.emit(3, listOf(0)); drain()
        controller.adjustVolume(epoch, -1)
        controller.adjustVolume(epoch, -2)
        controller.adjustVolume(epoch, -5)
        controller.adjustVolume(epoch, 100)
        controller.adjustVolume(epoch - 1, -1)
        drain()
        assertEquals(listOf(0 to listOf(-1), 0 to listOf(-2), 0 to listOf(-5)), context.remote.commands)
        assertEquals(listOf(12), controller.state.value.samples[2])
        context.remote.emit(2, listOf(13)); drain()
        assertEquals(listOf(13), controller.state.value.samples[2])
        context.remote.commands.clear()
        context.remote.replyCached = false
        advance(46_000)
        controller.adjustVolume(epoch, -1); drain()
        assertTrue(context.remote.commands.isEmpty())
    }

    @Test fun `unknown profile receives identity only and cannot write`() {
        context.remote.identity = 13
        context.connect(); drain()
        controller.set(controller.state.value.epoch, "balance", 9); drain(); advance(100)
        assertEquals(listOf(1), context.remote.registrations)
        assertTrue(context.remote.commands.isEmpty())
    }
    @Test fun `edits are bounded coalesced and confirmed by callbacks rather than replies`() {
        context.connect(); drain()
        val epoch = controller.state.value.epoch
        controller.set(epoch, "loudness", 7)
        controller.set(epoch, "loudness", 1)
        controller.set(epoch, "loudness", 0)
        controller.set(epoch, "loudness", 1)
        drain(); advance(100)
        assertEquals(1, context.remote.commands.size)
        assertEquals(5, context.remote.commands.single().first)
        assertEquals(listOf(1), context.remote.commands.single().second)
        assertEquals(listOf(0), controller.state.value.samples[11])
        assertEquals(setOf("loudness"), controller.state.value.pending)
        context.remote.emit(11, listOf(1)); drain()
        assertTrue(controller.state.value.pending.isEmpty())
    }
    @Test fun `balance and fader queued together preserve both axes`() {
        context.connect(); drain()
        val epoch = controller.state.value.epoch
        controller.set(epoch, "balance", 10); controller.set(epoch, "fader", 12)
        drain(); advance(100)
        assertEquals(listOf(3 to listOf(10, 12)), context.remote.commands)
        context.remote.emit(8, listOf(10,12)); drain()
        assertTrue(controller.state.value.pending.isEmpty())
    }
    @Test fun `missing confirmation expires pending without inventing new value`() {
        context.connect(); drain()
        controller.set(controller.state.value.epoch, "loudness", 1); drain(); advance(100)
        advance(4000)
        assertTrue(controller.state.value.failed)
        assertTrue(controller.state.value.pending.isEmpty())
        assertEquals(listOf(0), controller.state.value.samples[11])
    }
    @Test fun `disconnect rejects old callbacks and queued edits`() {
        context.connect(); drain()
        val old = context.remote.listeners.getValue(11)
        val epoch = controller.state.value.epoch
        controller.set(epoch, "loudness", 1); drain()
        context.connection!!.onServiceDisconnected(null); drain(); advance(100)
        context.remote.emit(11,listOf(1),old); drain()
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.samples.isEmpty())
        assertTrue(context.remote.commands.isEmpty())
        advance(5000); context.connect(); drain()
        controller.set(epoch,"loudness",1); drain(); advance(100)
        assertTrue(context.remote.commands.isEmpty())
    }
    @Test fun `changed DSP identity invalidates connection instead of reusing old settings`() {
        context.connect(); drain()
        val oldEpoch = controller.state.value.epoch
        context.remote.emit(1,listOf(7)); drain()
        assertFalse(controller.state.value.connected)
        assertTrue(controller.state.value.samples.isEmpty())
        assertNotEquals(oldEpoch,controller.state.value.epoch)
    }
    @Test fun `invalid and stale samples disable controls`() {
        context.connect(); drain()
        context.remote.emit(8,listOf(8)); drain()
        assertFalse(controller.state.value.samples.containsKey(8))
        context.remote.replyCached = false
        advance(46_000)
        assertTrue(controller.state.value.samples.isEmpty())
    }
    private class TestContext(base: Context) : ContextWrapper(base) {
        val remote = SoundModule()
        var connection: ServiceConnection? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean { connection=conn;return true }
        override fun unbindService(conn: ServiceConnection) { if (connection===conn) connection=null }
        fun connect() {
            connection!!.onServiceConnected(null, object : Binder() {
                override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                    assertEquals(1,code);assertEquals(0,flags)
                    data.enforceInterface("com.syu.ipc.IRemoteToolkit");assertEquals(4,data.readInt())
                    reply!!.writeNoException();reply.writeStrongBinder(remote);return true
                }
            })
        }
    }
    private class SoundModule : Binder() {
        var identity=11
        var replyCached=true
        val listeners=mutableMapOf<Int,IBinder>()
        val registrations=mutableListOf<Int>()
        val commands=mutableListOf<Pair<Int,List<Int>>>()
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
            assertEquals(0,flags);assertNotNull(reply)
            data.enforceInterface("com.syu.ipc.IRemoteModule")
            when(code) {
                3 -> {
                    val listener=data.readStrongBinder()!!;val field=data.readInt();assertEquals(1,data.readInt())
                    listeners[field]=listener;registrations+=field
                    if(replyCached) when(field) {
                        1 -> emit(1,listOf(identity));8 -> emit(8,listOf(8,8));9 -> emit(9,listOf(0,10))
                        10 -> emit(10,listOf(0));11 -> emit(11,listOf(0))
                    }
                }
                4 -> {data.readStrongBinder();listeners.remove(data.readInt())}
                1 -> {commands+=data.readInt() to data.createIntArray()!!.toList();assertNull(data.createFloatArray());assertNull(data.createStringArray())}
                else -> fail("Unexpected transaction")
            }
            reply!!.writeNoException();return true
        }
        fun emit(field:Int,values:List<Int>,target:IBinder=listeners.getValue(field)) {
            val p=Parcel.obtain();val reply=Parcel.obtain()
            try {
                p.writeInterfaceToken(SyuSoundController.CALLBACK);p.writeInt(field);p.writeIntArray(values.toIntArray())
                p.writeFloatArray(null);p.writeStringArray(null)
                assertTrue(target.transact(1,p,reply,0));reply.readException()
            } finally {p.recycle();reply.recycle()}
        }
    }
}
