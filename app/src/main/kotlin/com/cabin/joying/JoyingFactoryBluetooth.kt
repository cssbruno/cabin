package com.cabin.joying

import android.content.*
import android.os.*
import com.cabin.platform.SyuBinderTransport

/** Stock CarLink's module-2 identity subscription. The firmware owns the external BT driver. */
internal class JoyingFactoryBluetooth(
    private val context: Context,
    private val dispatch: (() -> Unit) -> Unit,
    private val identity: (String, String) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var bound = false
    private var module: IBinder? = null
    private var name: String? = null
    private var address: String? = null
    private var last: Pair<String, String>? = null
    private var phoneState = 0
    private var cutState: Int? = null
    private var ownsCut = false
    var remoteAddress: String? = null
        private set
    private val death = IBinder.DeathRecipient { dispatch { clear() } }
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
            if (code != 1) return false
            data.enforceInterface(CALLBACK)
            val sample = readSample(data) ?: return false
            dispatch {
                if (!closed && module != null) {
                    when (sample.field) {
                        6 -> remoteAddress = normalized("Phone", sample.text)?.second?.chunked(2)?.joinToString(":")
                        9 -> phoneState = sample.value ?: 0
                        13 -> cutState = sample.value
                        14 -> address = sample.text
                        15 -> name = sample.text
                    }
                    normalized(name, address)?.let {
                        if (it != last) { identity(it.first, it.second); last = it }
                    }
                }
            }
            reply?.writeNoException()
            return true
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(component: ComponentName?, binder: IBinder?) = dispatch {
            if (!closed) runCatching {
                clear()
                module = binder?.let { SyuBinderTransport.getModule(it, 2) }
                module?.let { remote ->
                    remote.linkToDeath(death, 0)
                    FIELDS.forEach { SyuBinderTransport.register(remote, callback, it) }
                }
            }.onFailure { clear() }
        }
        override fun onServiceDisconnected(component: ComponentName?) = dispatch { clear() }
    }
    fun start() {
        if (!closed && !bound) bound = runCatching {
            context.bindService(Intent("com.syu.ms.toolkit").setPackage("com.syu.ms"), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }
    /** CarLinkService.g: C_LINK_CUT is command 13, with a scalar inside the AIDL int array. */
    fun cutHandsFree() {
        if (ownsCut || phoneState == 0) return
        check(cutState == 0) { "Factory hands-free state is unavailable or already owned" }
        val remote = checkNotNull(module) { "Factory Bluetooth service is unavailable" }
        SyuBinderTransport.transact(remote, 1, {
            it.writeInt(13); it.writeIntArray(intArrayOf(1)); it.writeFloatArray(null); it.writeStringArray(null)
        }, {})
        ownsCut = true
    }
    fun restoreHandsFree() {
        if (!ownsCut) return
        module?.let { remote ->
            SyuBinderTransport.transact(remote, 1, {
                it.writeInt(13); it.writeIntArray(intArrayOf(0)); it.writeFloatArray(null); it.writeStringArray(null)
            }, {})
        }
        ownsCut = false
    }
    private fun clear() {
        runCatching { restoreHandsFree() }
        module?.let { remote ->
            FIELDS.forEach { runCatching { SyuBinderTransport.unregister(remote, callback, it) } }
            runCatching { remote.unlinkToDeath(death, 0) }
        }
        module = null; name = null; address = null; last = null
        remoteAddress = null; phoneState = 0; cutState = null; ownsCut = false
    }
    override fun close() {
        closed = true
        clear()
        if (bound) { runCatching { context.unbindService(connection) }; bound = false }
    }
    companion object {
        private val FIELDS = listOf(6, 9, 13, 14, 15)
        data class Sample(val field: Int, val value: Int?, val text: String?)
        const val CALLBACK = "com.syu.ipc.IModuleCallback"
        fun normalized(name: String?, address: String?): Pair<String, String>? {
            if (name.isNullOrBlank() || address == null) return null
            val mac = address.replace(":", "").uppercase(java.util.Locale.ROOT)
            if (!mac.matches(Regex("[0-9A-F]{12}")) || mac in setOf("000000000000", "020000000000", "FFFFFFFFFFFF")) return null
            var bounded: String = name
            while (bounded.toByteArray(Charsets.UTF_8).size > 63) bounded = bounded.dropLast(1)
            return bounded to mac
        }
        /** Decode bounded nullable AIDL arrays before using the first string. */
        fun readIdentity(data: Parcel): Pair<Int, String>? = readSample(data)?.let {
            if (it.field in 14..15 && it.text != null) it.field to it.text else null
        }
        fun readSample(data: Parcel): Sample? = runCatching {
            require(data.dataSize() <= 4096 && data.dataAvail() >= 16)
            val field = data.readInt()
            require(field in FIELDS)
            val count = data.readInt()
            require(count in -1..16 && count.coerceAtLeast(0) <= data.dataAvail() / 4)
            val values = List(count.coerceAtLeast(0)) { data.readInt() }
            val floats = data.readInt()
            require(floats in -1..16 && floats.coerceAtLeast(0) <= data.dataAvail() / 4)
            repeat(floats.coerceAtLeast(0)) { data.readInt() }
            val strings = data.readInt()
            require(strings in -1..1)
            val text = if (strings == 1) data.readString()?.also { require(it.length <= 256) } else null
            require(if (field == 9 || field == 13) values.size == 1 else text != null)
            Sample(field, values.firstOrNull(), text)
        }.getOrNull()
    }
}
