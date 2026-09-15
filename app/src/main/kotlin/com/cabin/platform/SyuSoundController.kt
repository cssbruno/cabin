package com.cabin.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class SyuSoundConnection(
    val connected: Boolean = false,
    val moduleId: Int? = null,
    val samples: Map<Int, List<Int>> = emptyMap(),
    val pending: Set<String> = emptySet(),
    val failed: Boolean = false,
    val epoch: Long = 0,
)

/** Owns only the installed toolkit's SOUND module. No native driver or raw CAN commands. */
internal class SyuSoundController(context: Context, private val now: () -> Long = SystemClock::elapsedRealtime) : AutoCloseable {
    private val context = context.applicationContext
    private val worker = HandlerThread("CabinSyuSound").apply { start() }
    private val handler = Handler(worker.looper)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val mutable = MutableStateFlow(SyuSoundConnection())
    val state = mutable.asStateFlow()
    private var owner: ServiceConnection? = null
    private var module: IBinder? = null
    private var callback: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var epoch = 0L
    private val registered = ConcurrentHashMap.newKeySet<Int>()
    private val updated = mutableMapOf<Int, Long>()
    private data class Edit(val value: Int, val queuedAt: Long, var sentAt: Long? = null)
    private val edits = linkedMapOf<String, Edit>()
    private val flush = Runnable { flushEdits() }
    private val retry = Runnable { bind() }
    private val bindTimeout = Runnable { reconnect() }
    private var lastRefresh = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (closed.get() || module == null) return
            val time = now()
            val expired = updated.filterValues { time - it > 45_000 }.keys.toSet()
            if (expired.isNotEmpty()) {
                expired.forEach(updated::remove)
                mutable.value = mutable.value.copy(samples = mutable.value.samples - expired)
            }
            if (edits.values.any { time - (it.sentAt ?: it.queuedAt) > 3_000 }) {
                edits.entries.removeAll { time - (it.value.sentAt ?: it.value.queuedAt) > 3_000 }
                mutable.value = mutable.value.copy(pending = edits.keys.toSet(), failed = true)
            }
            // Ask for cached values again, so quiet settings remain fresh without guessed get codes.
            if (time - lastRefresh >= 20_000) {
                lastRefresh = time
                try {
                    val remote = module ?: return
                    val listener = callback ?: return
                    registered.toList().forEach { SyuBinderTransport.register(remote, listener, it) }
                } catch (_: Exception) { reconnect(); return }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) handler.post { bind() }
    }

    /** The UI passes its rendered epoch; edits from an older connection/profile are discarded. */
    fun set(expectedEpoch: Long, key: String, value: Int) {
        if (closed.get()) return
        handler.post {
            val current = mutable.value
            if (closed.get() || !current.connected || current.epoch != expectedEpoch) return@post
            if (SyuSoundProtocol.command(current.moduleId, current.samples, key, value) == null) return@post
            if (key !in edits && edits.size >= 64) return@post
            edits[key] = Edit(value, now())
            mutable.value = current.copy(pending = edits.keys.toSet(), failed = false)
            handler.removeCallbacks(flush)
            handler.postDelayed(flush, 80)
        }
    }

    private fun flushEdits() {
        val remote = module ?: return
        try {
            for ((key, edit) in edits.toMap()) {
                if (edit.sentAt != null) continue
                val current = mutable.value
                val command = SyuSoundProtocol.command(current.moduleId, current.samples, key, edit.value)
                if (command == null) { edits.remove(key); continue }
                // Balance and fader share one frame; coalesce both axes so a queued
                // fader edit cannot restore the old balance (or vice versa).
                if (key == "balance" || key == "fader") {
                    for ((axis, index) in listOf("balance" to 0, "fader" to 1)) {
                        val other = edits[axis] ?: continue
                        if (SyuSoundProtocol.command(current.moduleId, current.samples, axis, other.value) != null) {
                            command.ints[index] = other.value
                            other.sentAt = now()
                        }
                    }
                }
                edit.sentAt = now()
                SyuBinderTransport.transact(remote, 1, {
                    it.writeInt(command.code)
                    it.writeIntArray(command.ints)
                    it.writeFloatArray(null)
                    it.writeStringArray(null)
                }, {})
            }
            mutable.value = mutable.value.copy(pending = edits.keys.toSet())
        } catch (_: Exception) { reconnect(failed = true) }
    }

    private fun bind() {
        if (closed.get() || owner != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val expected = this
                handler.post {
                    if (closed.get() || owner !== expected) return@post
                    handler.removeCallbacks(bindTimeout)
                    try {
                        val remote = service?.let { SyuBinderTransport.getModule(it, 4) }
                            ?: run { reconnect(); return@post }
                        module = remote
                        death = IBinder.DeathRecipient { lost(expected) }
                        remote.linkToDeath(death!!, 0)
                        callback = createCallback(expected, remote)
                        mutable.value = SyuSoundConnection(connected = true, epoch = ++epoch)
                        subscribe(1)
                        lastRefresh = now()
                        handler.post(tick)
                    } catch (_: Exception) { reconnect() }
                }
            }
            override fun onServiceDisconnected(name: ComponentName?) = lost(this)
            override fun onBindingDied(name: ComponentName?) = lost(this)
            override fun onNullBinding(name: ComponentName?) = lost(this)
        }
        owner = connection
        val accepted = try { bindFytService(context, fytModuleIntent(context, 4), connection) }
            catch (_: RuntimeException) { false }
        if (accepted) handler.postDelayed(bindTimeout, 10_000) else reconnect()
    }

    private fun createCallback(expected: ServiceConnection, remote: IBinder) = object : Binder() {
        init { attachInterface(null, CALLBACK) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code != 1) return false
            data.enforceInterface(CALLBACK)
            if (data.dataSize() > 4096 || data.dataAvail() < 8) return false
            val field = data.readInt()
            if (field !in registered) { reply?.writeNoException(); return true }
            val count = data.readInt()
            if (count !in -1..128 || count.coerceAtLeast(0) > data.dataAvail() / 4) return false
            val values = List(count.coerceAtLeast(0)) { data.readInt() }
            handler.post {
                if (!closed.get() && owner === expected && module === remote) receive(field, values)
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun receive(field: Int, values: List<Int>) {
        if (field == 1) {
            val id = values.singleOrNull()?.takeIf { it in 0..255 }
            if (mutable.value.moduleId != null && id != mutable.value.moduleId) {
                reconnect()
                return
            }
            if (id != mutable.value.moduleId) {
                edits.clear(); updated.clear(); handler.removeCallbacks(flush)
                mutable.value = SyuSoundConnection(connected = true, moduleId = id, epoch = ++epoch)
                try {
                    val wanted = SyuSoundProtocol.fields(id) + 1
                    for (old in registered.toList() - wanted) {
                        registered.remove(old)
                        SyuBinderTransport.unregister(module!!, callback!!, old)
                    }
                    wanted.forEach(::subscribe)
                } catch (_: Exception) { reconnect() }
            }
            return
        }
        val samples = SyuSoundProtocol.normalizedSamples(field, values)
        if (samples.isEmpty()) {
            val invalidKeys = if (field == 9) {
                values.firstOrNull()?.takeIf { it in 0..35 }?.let { setOf(SyuSoundProtocol.EQ_SAMPLE_BASE + it) }
                    ?: mutable.value.samples.keys.filter { it >= SyuSoundProtocol.EQ_SAMPLE_BASE }.toSet()
            } else setOf(field)
            invalidKeys.forEach(updated::remove)
            mutable.value = mutable.value.copy(samples = mutable.value.samples - invalidKeys)
            return
        }
        samples.keys.forEach { updated[it] = now() }
        val next = mutable.value.copy(samples = mutable.value.samples + samples)
        val controls = SyuSoundProtocol.controls(next.moduleId, next.samples).associateBy { it.key }
        edits.entries.removeAll { (key, edit) -> edit.sentAt != null && controls[key]?.current == edit.value }
        mutable.value = next.copy(pending = edits.keys.toSet())
    }

    private fun subscribe(field: Int) {
        if (registered.add(field)) SyuBinderTransport.register(module!!, callback!!, field)
    }
    private fun lost(expected: ServiceConnection) {
        handler.post { if (owner === expected) reconnect() }
    }
    private fun reconnect(failed: Boolean = false) {
        clear(failed)
        handler.removeCallbacks(retry)
        if (!closed.get()) handler.postDelayed(retry, 5_000)
    }
    private fun clear(failed: Boolean = false) {
        handler.removeCallbacks(bindTimeout); handler.removeCallbacks(flush); handler.removeCallbacks(tick)
        val oldOwner = owner; owner = null
        val oldModule = module; module = null
        val oldListener = callback; callback = null
        val oldDeath = death; death = null
        val fields = registered.toList(); registered.clear()
        edits.clear(); updated.clear()
        mutable.value = SyuSoundConnection(epoch = ++epoch, failed = failed)
        if (oldModule != null && oldListener != null) {
            for (field in fields) try { SyuBinderTransport.unregister(oldModule, oldListener, field) } catch (_: Exception) { break }
            try { if (oldDeath != null) oldModule.unlinkToDeath(oldDeath, 0) } catch (_: Exception) { }
        }
        if (oldOwner != null) try { context.unbindService(oldOwner) } catch (_: RuntimeException) { }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        handler.post { handler.removeCallbacksAndMessages(null); clear(); worker.quitSafely() }
    }
    companion object { const val CALLBACK = "com.syu.ipc.IModuleCallback" }
}
