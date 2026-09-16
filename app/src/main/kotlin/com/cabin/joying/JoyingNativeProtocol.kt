package com.cabin.joying

import android.os.IBinder
import android.os.Parcel
import java.io.EOFException
import java.io.InputStream

/** Verified against Joying Car Link 2.0 2.23.0712.1954 (f.a and f.g$a). */
internal object JoyingNativeProtocol {
    const val DESCRIPTOR = "CarplayServer.ICarplayService"
    // An ABSTRACT socket name, despite looking like a filesystem path.
    const val VIDEO_SOCKET = "cabin.carlink"
    const val TOUCH = 202
    const val SCREEN = 210
    const val LINK_STATE = 215
    const val MAX_FRAME = 4 * 1024 * 1024

    fun command(binder: IBinder, command: Int, values: IntArray = intArrayOf(), strings: List<String> = emptyList()): Int =
        transact(binder, (command shl 8) or 2) { request ->
            values.forEach(request::writeInt)
            strings.forEach(request::writeString)
        }

    fun bluetoothState(binder: IBinder, state: String) = transact(binder, (229 shl 8) or 2) {
        it.writeInt(1)
        android.os.PersistableBundle().apply { putString("_btcmd", state) }.writeToParcel(it, 0)
    }

    fun bluetoothBytes(binder: IBinder, bytes: ByteArray) = transact(binder, (230 shl 8) or 2) {
        require(bytes.size in 1..65536)
        it.writeInt(bytes.size)
        it.writeByteArray(bytes)
    }

    fun registerListener(binder: IBinder, listener: IBinder?) {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            request.writeStrongBinder(listener)
            check(binder.transact(3, request, reply, 0)) { "Carlink listener registration rejected" }
            check(reply.dataAvail() >= 4) { "Missing Carlink listener reply" }
            // f.a.run reads a boolean here, unlike ordinary command status replies.
            // Zero means the daemon did not register our callback.
            val registered = reply.readInt()
            if (reply.dataAvail() >= 4) reply.readException()
            check(registered > 0) { "Carlink listener registration failed: $registered" }
        } finally { request.recycle(); reply.recycle() }
    }

    private fun transact(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(DESCRIPTOR)
            write(request)
            check(binder.transact(code, request, reply, 0)) { "Carlink rejected command $code" }
            check(reply.dataAvail() >= 4) { "Missing Carlink command reply" }
            val result = reply.readInt()
            check(result >= 0) { "Carlink command $code failed: $result" }
            return result
        } finally { request.recycle(); reply.recycle() }
    }

    /** uint32 little-endian payload length followed by one Annex-B access unit. */
    fun readFrame(input: InputStream): ByteArray? {
        val first = input.read()
        if (first < 0) return null
        val header = ByteArray(4)
        header[0] = first.toByte()
        readFully(input, header, 1, 3)
        val length = (0..3).fold(0L) { value, i -> value or ((header[i].toLong() and 255) shl (8 * i)) }
        require(length in 4..MAX_FRAME.toLong()) { "Invalid Carlink video frame length: $length" }
        val frame = ByteArray(length.toInt())
        readFully(input, frame, 0, frame.size)
        require(frame[0] == 0.toByte() && frame[1] == 0.toByte() &&
            (frame[2] == 1.toByte() || (frame[2] == 0.toByte() && frame[3] == 1.toByte()))) {
            "Carlink video payload is not Annex-B H.264"
        }
        return frame
    }

    private fun readFully(input: InputStream, data: ByteArray, offset: Int, length: Int) {
        var position = offset
        while (position < offset + length) {
            val count = input.read(data, position, offset + length - position)
            if (count < 0) throw EOFException("Truncated Carlink video frame")
            if (count == 0) {
                val next = input.read()
                if (next < 0) throw EOFException("Truncated Carlink video frame")
                data[position++] = next.toByte()
            } else position += count
        }
    }
}
