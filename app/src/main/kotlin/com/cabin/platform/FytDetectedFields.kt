package com.cabin.platform

/** Semantic read mappings only. Detected IDs never become command IDs. */
internal data class FytDetectedProfile(
    val profile: Int,
    val fields: Map<Int, Int>, // Cabin canonical field -> installed callback field.
    val reason: String,
    val unmatched: Set<Int> = emptySet(),
    val publishedFields: Set<Int> = emptySet(),
    val receiver: String = "",
    val moduleFields: Map<Int, Set<Int>> = emptyMap(),
    val syuClient: FytSyuClientFields = FytSyuClientFields(),
) {
    fun normalize(raw: Map<Int, Int>): Map<Int, Int> = buildMap {
        raw[1000]?.takeIf { it == profile }?.let { put(1000, it) }
        if (raw[1000] != profile) return@buildMap
        fields.forEach { (canonical, installed) ->
            raw[installed]?.takeIf { it in when (canonical) { 29, 56 -> 0..7; 25, 31, 52 -> -3..255; in 94..97 -> 0..3; 181 -> 0..65535; else -> 0..1 } }?.let { put(canonical, it) }
        }
        if (33 !in this) listOf(25, 31, 52).forEach { remove(it) }
        if (!setOf(179, 180, 181).all { it in this }) listOf(179, 180, 181).forEach { remove(it) }
    }
}
