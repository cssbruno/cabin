package com.cabin.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageParserTest {
    @Test
    fun `unknown message with valid complement preserves frame boundary`() {
        val unknownType = 0x6A6A6A6A
        val header = header(type = unknownType, typeCheck = unknownType.inv())

        val parsed = MessageParser.parseHeader(header)

        assertEquals(MessageType.UNKNOWN, parsed.type)
        assertEquals(unknownType, parsed.rawType)
    }

    @Test(expected = HeaderParseException::class)
    fun `unknown message with invalid complement is rejected`() {
        val unknownType = 0x6A6A6A6A

        MessageParser.parseHeader(header(type = unknownType, typeCheck = 0))
    }

    private fun header(type: Int, typeCheck: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(PROTOCOL_MAGIC)
            .putInt(32)
            .putInt(type)
            .putInt(typeCheck)
            .array()
}
