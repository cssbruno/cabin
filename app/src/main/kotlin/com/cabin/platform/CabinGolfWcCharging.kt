package com.cabin.platform

/** WC hybrid's 19-byte schedule message. Global current/charge limits remain in CabinGolfHybrid. */
internal object CabinGolfWcCharging {
    private const val PROFILE = 655377
    private val starts = listOf(407, 426, 445)
    private val offsets = (0..17).filter { it != 6 }
    private val controls = (404..406).toSet() + starts.flatMap { start -> offsets.map { start + it } }
    // Also own the stock per-schedule aliases of global current and charge limits,
    // so generic enum fallback cannot display them using the RZC encoding.
    fun fields(profile: Int): Set<Int> = if (profile == PROFILE) (404..463).toSet() else emptySet()
    private fun range(field: Int): IntRange {
        if (field in 404..406) return 0..1
        if (field !in controls) return IntRange.EMPTY
        return when ((field - 407) % 19) {
            0, 14, 16 -> 0..23
            1 -> 0..59
            else -> 0..1
        }
    }
    fun command(profile: Int, field: Int, target: Int, raw: Map<Int, Int>): Pair<Int, List<Int>>? {
        if (profile != PROFILE || field !in controls || target !in range(field)) return null
        if (field >= 407 && (field - 407) % 19 == 1 && target % 5 != 0) return null
        // All three records arrive in one WC feedback frame. Never zero an unseen record.
        if (controls.any { raw[it] == null || raw.getValue(it) !in range(it) }) return null
        val next = raw + (field to target)
        val enabled = (next.getValue(406) shl 7) or (next.getValue(405) shl 6) or (next.getValue(404) shl 5)
        val mask = if (field <= 406) 28 else listOf(12, 20, 24)[(field - 407) / 19]
        val packet = mutableListOf(enabled or mask)
        for (start in starts) {
            fun value(offset: Int) = next.getValue(start + offset)
            packet += listOf(value(0), value(1), (value(3) shl 7) or (value(4) shl 6) or (value(5) shl 5),
                (value(14) shl 1) or value(15), (value(16) shl 1) or value(17),
                (value(2) shl 7) or (7..13).fold(0) { bits, offset -> bits or (value(offset) shl (13 - offset)) })
        }
        return 147 to packet
    }

    fun read(profile: Int, raw: Map<Int, Int>, portuguese: Boolean): List<FytSyuReading> {
        if (profile != PROFILE) return emptyList()
        // Share schedule labels/time rendering only. WC commands are always encoded above.
        val display = raw.filter { (field, value) -> field in controls && value in range(field) }.mapValues { (field, value) ->
            if (field >= 407 && (field - 407) % 19 in setOf(15, 17) && value in 0..1) value * 30 else value
        }
        return CabinGolfRzcCharging.read(655520, display, portuguese).filter { it.viewId in controls }.map { row ->
            val field = row.viewId
            val options = range(field).filter { field < 407 || (field - 407) % 19 != 1 || it % 5 == 0 }.associateWith { value ->
                when {
                    field >= 407 && (field - 407) % 19 in setOf(0, 1, 14, 16) -> "%02d".format(value)
                    field >= 407 && (field - 407) % 19 in setOf(15, 17) -> if (value == 0) "00" else "30"
                    value == 0 -> if (portuguese) "Desligado" else "Off"
                    else -> if (portuguese) "Ligado" else "On"
                }
            }
            row.copy(screen = "golf_wc_2023", options = if (command(profile, field, options.keys.first(), raw) != null) options else emptyMap())
        }
    }
}
