package com.cabin.platform.obd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ObdReplyReaderTest {
    @Test fun `reader stops at prompt without eating next reply`() {
        val input = ByteArrayInputStream("410D10\r>410D11\r>".toByteArray())
        assertEquals("410D10\r", ObdReplyReader.read(input))
        assertEquals("410D11\r", ObdReplyReader.read(input))
    }

    @Test(expected = IOException::class) fun `truncated replies are not emitted`() {
        ObdReplyReader.read(ByteArrayInputStream("410D10".toByteArray()))
    }

    @Test(expected = IOException::class) fun `oversized responses fail closed`() {
        ObdReplyReader.read(ByteArrayInputStream(("A".repeat(4097) + ">").toByteArray()))
    }

    @Test(expected = IOException::class) fun `non ASCII response is rejected`() {
        ObdReplyReader.read(ByteArrayInputStream(byteArrayOf(0x80.toByte(), '>'.code.toByte())))
    }

    @Test fun `exact size limit is accepted`() {
        assertEquals(4096, ObdReplyReader.read(ByteArrayInputStream(("A".repeat(4096) + ">").toByteArray())).length)
    }
}
