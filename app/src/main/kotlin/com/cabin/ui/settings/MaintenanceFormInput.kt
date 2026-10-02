package com.cabin.ui.settings

/** Manual forms accept either decimal key, never grouping, exponents, or non-finite values. */
internal fun maintenanceDecimal(text: String): Double? {
    val value = text.trim()
    if (!Regex("(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)").matches(value)) return null
    return value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10_000_000.0 }
}
