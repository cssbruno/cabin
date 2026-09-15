package com.cabin.platform

import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/** Vendor cmd/register/unregister are ONE-WAY. There is no success reply or durable-save ACK. */
internal object SyuSteeringWire {
    const val CALLBACK = "com.syu.ipc.IModuleCallback"
    fun command(remote: IBinder, command: SteeringCommand) = send(remote, 1) {
        it.writeInt(command.code); it.writeIntArray(command.values.toIntArray())
        it.writeFloatArray(null); it.writeStringArray(null)
    }
    fun subscribe(remote: IBinder, callback: IBinder, field: Int, register: Boolean) = send(remote, if (register) 3 else 4) {
        it.writeStrongBinder(callback); it.writeInt(field)
        if (register) it.writeInt(1)
    }
    private fun send(remote: IBinder, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(SyuBinderTransport.MODULE_DESCRIPTOR); write(data)
            if (!remote.transact(code, data, null, IBinder.FLAG_ONEWAY)) throw RemoteException("Unsupported steering transaction")
        } finally { data.recycle() }
    }
    fun feedback(data: Parcel): Pair<Int, List<Int>>? {
        if (data.dataSize() > 2048) return null
        return try {
            data.enforceInterface(CALLBACK)
            if (data.dataAvail() < 16) return null
            val field = data.readInt()
            val count = data.readInt()
            if (count !in 1..2 || data.dataAvail() != count * 4 + 8) return null
            val ints = List(count) { data.readInt() }
            // These fields have no float/string payload. Accept null or empty arrays only.
            if (data.readInt() !in -1..0 || data.readInt() !in -1..0) return null
            (field to ints).takeIf { SyuSteeringProtocol.feedback(SteeringHardwareState(), field, ints) != null }
        } catch (_: RuntimeException) { null }
    }
}
