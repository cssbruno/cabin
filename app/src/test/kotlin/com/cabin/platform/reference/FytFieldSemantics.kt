package com.cabin.platform

internal object FytFieldSemantics {
    val profiles = setOf(1048874, 1114410, 196906, 262442)
    private fun bit(byte: Int, bit: Int) = if (bit == 0) "and(1,byte[$byte])" else "and(1,shr(byte[$byte],$bit))"
    private fun plain(value: String) = setOf("$value when ")
    private val climate = mapOf(
        32 to plain(bit(2, 7)), 24 to plain(bit(2, 6)), 21 to plain(bit(2, 5)),
        20 to plain(bit(2, 3)), 30 to plain(bit(2, 2)), 23 to plain(bit(2, 1)),
        28 to plain(bit(3, 7)), 26 to plain(bit(3, 6)), 27 to plain(bit(3, 5)),
        22 to plain(bit(6, 7)), 33 to plain(bit(6, 0)), 34 to plain(bit(6, 2)),
        29 to setOf("7 when !le(and(15,byte[3]),7)", "and(15,byte[3]) when le(and(15,byte[3]),7)"),
    )
    private fun door(normalBit: Int, reversedBit: Int) = setOf(
        "${bit(2, normalBit)} when !ne(field:Lf0/tp;->U:I,0)",
        "${bit(2, reversedBit)} when ne(field:Lf0/tp;->U:I,0)",
    )
    private val doors = mapOf(37 to door(6, 7), 38 to door(7, 6), 39 to door(4, 5),
        40 to door(5, 4), 41 to plain(bit(2, 3)), 36 to plain(bit(2, 2)))
    val expected = mapOf(0x21 to climate, 0x24 to doors)

    fun match(profile: Int, packets: Map<Int, Map<Int, Set<String>>>): FytDetectedProfile {
        val matched = mutableMapOf<Int, Int>()
        val missing = mutableSetOf<Int>()
        expected.forEach { (packet, fields) ->
            fields.forEach { (canonical, semantics) ->
                val candidates = packets[packet].orEmpty().filter { (id, observed) -> id != 1000 && observed == semantics }.keys
                if (candidates.size == 1) matched[canonical] = candidates.single() else missing.add(canonical)
            }
        }
        // A callback ID cannot simultaneously represent two different Cabin fields.
        val duplicates = matched.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        matched.filterValues { it in duplicates }.keys.toList().forEach { missing.add(it); matched.remove(it) }
        return FytDetectedProfile(profile, matched, when {
            matched.isEmpty() -> "no_matching_fields"
            missing.isNotEmpty() -> "partial_match"
            else -> "matched"
        }, missing)
    }
}
