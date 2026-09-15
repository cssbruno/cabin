package com.cabin.platform

import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [Utf16ParcelShadow::class])
class FytRawSampleTest {
    @Test fun `null integers preserve text and floats`() = parcel { data ->
        data.writeIntArray(null)
        data.writeFloatArray(floatArrayOf(1.25f, -2f))
        data.writeStringArray(arrayOf(null, "field value"))
        data.setDataPosition(0)
        assertEquals(FytRawSample(emptyList(), listOf(1.25f, -2f), listOf(null, "field value")), FytRawSample.read(data))
    }

    @Test fun `oversized or truncated arrays and text are rejected`() {
        listOf<(Parcel) -> Unit>(
            { it.writeInt(Int.MAX_VALUE) },
            { it.writeInt(8); it.writeInt(1) },
            { it.writeIntArray(null); it.writeFloatArray(null); it.writeStringArray(arrayOf("x".repeat(4097))) },
        ).forEach { write -> parcel { data ->
            write(data); data.setDataPosition(0)
            assertThrows(RuntimeException::class.java) { FytRawSample.read(data) }
        } }
    }

    @Test fun `complete bounded arrays retain values after the first 64 entries`() = parcel { data ->
        data.writeIntArray(IntArray(256) { it })
        data.writeFloatArray(null)
        data.writeStringArray(null)
        data.setDataPosition(0)
        assertEquals((0..255).toList(), FytRawSample.read(data).integers)
    }

    private fun parcel(block: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        try { block(data) } finally { data.recycle() }
    }
}
