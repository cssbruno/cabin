package com.cabin.joying

import android.os.Binder
import android.os.Parcel
import android.os.PersistableBundle

/** Stock f.d callback envelope; dispatch must enqueue work rather than call back into the daemon. */
internal class JoyingNativeListener(private val receive: (Event) -> Unit) : Binder() {
    sealed interface Event {
        data class State(val id: Int, val value: Int) : Event
        data object ShowUi : Event
        data class Message(val id: Int, val value: Int, val text: String?) : Event
        data class Bundle(val values: PersistableBundle) : Event
        data class Bluetooth(val bytes: ByteArray) : Event
    }
    companion object { const val DESCRIPTOR = "CarplayServer.ICarplayListener" }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) { reply?.writeString(DESCRIPTOR); return true }
        if (code and 255 != 1) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        when (val id = code ushr 8) {
            100, 107, 108, 109 -> receive(Event.State(id, data.readInt()))
            101 -> receive(Event.ShowUi)
            105 -> {
                val value = data.readInt()
                data.readInt() // Stock metadata length; string still carries its own Parcel length.
                receive(Event.Message(id, value, data.readString()))
            }
            106 -> {
                val value = data.readInt()
                val name = data.readString()
                data.readString()
                receive(Event.Message(id, value, name))
            }
            110 -> if (data.readInt() != 0) receive(Event.Bundle(PersistableBundle.CREATOR.createFromParcel(data)))
            111 -> {
                val length = data.readInt()
                require(length in 0..65536) { "Invalid Bluetooth callback size" }
                if (length > 0) {
                    val bytes = ByteArray(length)
                    data.readByteArray(bytes)
                    receive(Event.Bluetooth(bytes))
                }
            }
            else -> return false
        }
        reply?.writeInt(0) // Native callback uses a bare status, not writeNoException().
        return true
    }
}
