package com.cabin.platform

import android.os.BadParcelableException
import android.os.IBinder
import android.os.Parcel

/** Framework ModuleObject wire order: integers, floats, strings. Null and empty are distinct. */
internal data class SyuModuleObject(
    val ints: IntArray? = null,
    val floats: FloatArray? = null,
    val strings: Array<String?>? = null,
)

/**
 * Readback infrastructure only: get codes must be verified for the selected vendor module.
 * Call on the module owner's worker, never the UI thread. A read can synchronously call back.
 */
internal object SyuModuleReadback {
    fun get(remote: IBinder, getCode: Int, arguments: SyuModuleObject = SyuModuleObject()): SyuModuleObject? =
        SyuBinderTransport.transact(remote, 2, write = {
            it.writeInt(getCode)
            SyuModuleObjectCodec.write(it, arguments)
        }, read = SyuModuleObjectCodec::readNullable)
}

/** Bounds are checked before allocating arrays or letting Parcel allocate an incoming string. */
internal object SyuModuleObjectCodec {
    const val MAX_VALUES = 1024
    const val MAX_STRINGS = 64
    const val MAX_STRING_CHARS = 4096

    fun write(parcel: Parcel, value: SyuModuleObject) {
        require((value.ints?.size ?: 0) <= MAX_VALUES) { "Too many SYU integers" }
        require((value.floats?.size ?: 0) <= MAX_VALUES) { "Too many SYU floats" }
        require((value.strings?.size ?: 0) <= MAX_STRINGS) { "Too many SYU strings" }
        require(value.strings?.all { it == null || it.length <= MAX_STRING_CHARS } != false) { "SYU string too long" }
        // Validate the aggregate before creating a Parcel containing large caller-owned strings.
        val bytes = 12L + (value.ints?.size ?: 0) * 4L + (value.floats?.size ?: 0) * 4L +
            (value.strings?.sumOf { if (it == null) 4L else 4L + (((it.length + 1L) * 2L + 3L) / 4L) * 4L } ?: 0L)
        require(bytes <= SyuBinderTransport.MAX_PARCEL_BYTES - 256) { "SYU arguments too large" }
        parcel.writeIntArray(value.ints)
        parcel.writeFloatArray(value.floats)
        parcel.writeStringArray(value.strings)
    }

    fun readNullable(parcel: Parcel): SyuModuleObject? {
        malformedUnless(parcel.dataAvail() <= SyuBinderTransport.MAX_PARCEL_BYTES, "SYU result too large")
        return when (readInt(parcel)) {
            0 -> null.also { malformedUnless(parcel.dataAvail() == 0, "Trailing null SYU result") }
            1 -> read(parcel).also { malformedUnless(parcel.dataAvail() == 0, "Trailing SYU result") }
            else -> throw BadParcelableException("Invalid SYU result presence")
        }
    }

    internal fun readPayload(parcel: Parcel): SyuModuleObject {
        malformedUnless(parcel.dataAvail() <= SyuBinderTransport.MAX_PARCEL_BYTES, "SYU payload too large")
        return read(parcel).also { malformedUnless(parcel.dataAvail() == 0, "Trailing SYU payload") }
    }

    private fun read(parcel: Parcel): SyuModuleObject {
        val ints = readCount(parcel, MAX_VALUES)?.let { count ->
            malformedUnless(parcel.dataAvail() >= count * 4, "Truncated SYU integers")
            IntArray(count) { parcel.readInt() }
        }
        val floats = readCount(parcel, MAX_VALUES)?.let { count ->
            malformedUnless(parcel.dataAvail() >= count * 4, "Truncated SYU floats")
            FloatArray(count) { parcel.readFloat() }
        }
        val strings = readCount(parcel, MAX_STRINGS)?.let { count ->
            malformedUnless(parcel.dataAvail() >= count * 4, "Truncated SYU strings")
            Array(count) { readString(parcel) }
        }
        return SyuModuleObject(ints, floats, strings)
    }

    private fun readCount(parcel: Parcel, maximum: Int): Int? = readInt(parcel).let { count ->
        malformedUnless(count in -1..maximum, "Invalid SYU array length")
        if (count == -1) null else count
    }

    private fun readInt(parcel: Parcel): Int {
        malformedUnless(parcel.dataAvail() >= 4, "Truncated SYU result")
        return parcel.readInt()
    }

    private fun readString(parcel: Parcel): String? {
        val start = parcel.dataPosition()
        val length = readInt(parcel)
        malformedUnless(length in -1..MAX_STRING_CHARS, "Invalid SYU string length")
        if (length == -1) return null
        val paddedBytes = ((length + 1) * 2 + 3) / 4 * 4
        malformedUnless(parcel.dataAvail() >= paddedBytes, "Truncated SYU string")
        parcel.setDataPosition(start)
        return parcel.readString().also { malformedUnless(it != null && it.length == length, "Invalid SYU string") }
    }

    private fun malformedUnless(valid: Boolean, message: String) {
        if (!valid) throw BadParcelableException(message)
    }
}
