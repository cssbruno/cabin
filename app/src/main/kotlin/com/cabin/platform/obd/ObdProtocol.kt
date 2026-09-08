package com.cabin.platform.obd

/** Standard SAE Mode 01 reads only. No caller-provided commands reach the transport. */
enum class ObdPid(val code: Int, val byteCount: Int) {
    COOLANT(0x05, 1),
    RPM(0x0C, 2),
    SPEED(0x0D, 1),
    ECU_VOLTAGE(0x42, 2),
    ;

    val command: String get() = "01%02X".format(code)
}

/** Parser for ELM responses after echo, line feeds and headers have been disabled. */
object ObdProtocol {
    private val hex = Regex("[0-9A-F]+")
    private val ignored = setOf("SEARCHING...", "BUS INIT: OK")

    fun payloads(response: String, pid: Int, byteCount: Int): List<List<Int>> {
        if (response.length > 4096 || pid !in 0..255 || byteCount !in 1..4) return emptyList()
        val command = "01%02X".format(pid)
        val result = mutableListOf<List<Int>>()
        for (original in response.uppercase().split('\r', '\n', '>')) {
            val line = original.trim()
            if (line.isEmpty() || line in ignored) continue
            val compact = line.replace(" ", "")
            if (compact == command) continue
            // A malformed/error response must not leave an earlier value looking valid.
            if (!hex.matches(compact) || compact.length != (byteCount + 2) * 2) return emptyList()
            val bytes = compact.chunked(2).map { it.toInt(16) }
            if (bytes[0] != 0x41 || bytes[1] != pid) return emptyList()
            result += bytes.drop(2)
        }
        return result.toList()
    }

    fun supportedPids(response: String, base: Int): Set<Int> {
        if (base !in setOf(0x00, 0x20, 0x40)) return emptySet()
        val replies = payloads(response, base, 4)
        if (replies.isEmpty()) return emptySet()
        // Without headers we cannot identify an ECU. Only poll PIDs advertised by all replies.
        return (1..32).filter { offset ->
            replies.all { bytes -> bytes[(offset - 1) / 8] and (1 shl (7 - (offset - 1) % 8)) != 0 }
        }.map { base + it }.toSet()
    }

    fun value(response: String, pid: ObdPid): Double? {
        val replies = payloads(response, pid.code, pid.byteCount).distinct()
        // Do not silently pick a different ECU when two responders disagree.
        val bytes = replies.singleOrNull() ?: return null
        return when (pid) {
            ObdPid.COOLANT -> (bytes[0] - 40).toDouble()
            ObdPid.RPM -> (bytes[0] * 256 + bytes[1]) / 4.0
            ObdPid.SPEED -> bytes[0].toDouble()
            ObdPid.ECU_VOLTAGE -> (bytes[0] * 256 + bytes[1]) / 1000.0
        }
    }
}
