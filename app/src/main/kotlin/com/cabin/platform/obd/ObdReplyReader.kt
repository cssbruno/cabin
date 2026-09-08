package com.cabin.platform.obd

import java.io.IOException
import java.io.InputStream

/** One bounded ASCII reply, ending exactly at the ELM command-ready prompt. */
internal object ObdReplyReader {
    fun read(input: InputStream): String {
        val result = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw IOException("Adapter disconnected")
            if (byte == '>'.code) return result.toString()
            if (byte !in 0x20..0x7E && byte != 10 && byte != 13 && byte != 9) throw IOException("Invalid adapter response")
            if (result.length >= 4096) throw IOException("Oversized adapter response")
            result.append(byte.toChar())
        }
    }
}
