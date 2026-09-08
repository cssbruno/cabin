package com.cabin.platform

/** Transport availability is distinct from fresh vehicle measurements. */
enum class TeyesTelemetryHealth { DISCONNECTED, CONNECTING, LIVE, STALE }

internal object TeyesClimateControlPolicy {
    const val VERIFIED_ALTERNATE_PROFILE = 262465

    // Exact Civic variants identified by FinalCanbus and the 0298 callback. Not a family wildcard.
    private val civic0298Profiles = setOf(1048874, 1114410, 196906, 262442)

    fun isCivic0298(profile: Int): Boolean = profile in civic0298Profiles

    fun supports(profile: Int): Boolean = profile == VERIFIED_ALTERNATE_PROFILE || profile in civic0298Profiles

    fun acCode(profile: Int): Int = if (profile == VERIFIED_ALTERNATE_PROFILE) 30 else 24

    fun fanCode(profile: Int): Int = if (profile == VERIFIED_ALTERNATE_PROFILE) 35 else 29

    /** Normalize only an explicitly selected dialect. Raw field numbers conflict between firmware versions. */
    fun climateValues(
        profile: Int,
        values: Map<Int, Int>,
        layout: TeyesVehicleDataLayout = TeyesVehicleDataLayout.LEGACY,
    ): Map<Int, Int> {
        if (!isCivic0298(profile)) return values.filterKeys { it !in 179..181 && it !in 0..5 && it != 11 && it != 18 && it != 19 }
        if (layout == TeyesVehicleDataLayout.LEGACY) {
            // Preserve the user's working motion interface. Never infer this dialect from profile ID alone.
            return values.filterKeys { it == 1000 || it in 20..35 || it in 51..57 || it == 89 || it == 90 }
        }
        return buildMap {
            values[1000]?.let { put(1000, it) }

            fun copy(
                raw: Int,
                canonical: Int,
                range: IntRange,
            ) {
                values[raw]?.takeIf { it in range }?.let { put(canonical, it) }
            }
            copy(11, 24, 0..1) // A/C
            copy(21, 29, 0..7) // Fan
            copy(18, 28, 0..1) // Screen airflow
            copy(19, 26, 0..1) // Face airflow
            copy(20, 27, 0..1) // Foot airflow
            for (door in 0..5) copy(door, door + 36, 0..1)
            // Service DISTANCE, never oil-life percent. Metadata is mandatory.
            if (values[179] in 0..1 && values[180] in 0..1 && values[181]?.let { it >= 0 } == true) {
                put(179, values.getValue(179))
                put(180, values.getValue(180))
                put(181, values.getValue(181))
            }
            // Reference speed149/RPM151 scaling and temperature encoding are not verified.
            // In particular raw89/90 are seat fields here, not motion data.
        }
    }

    fun canControl(
        connected: Boolean,
        profile: Int,
        freshValues: Map<Int, Int>,
    ): Boolean =
        connected && supports(profile) && freshValues[acCode(profile)]?.let { it in 0..1 } == true &&
            freshValues[fanCode(profile)]?.let { it in 0..7 } == true
}

/** Monotonic timestamps: wall-clock changes must not make old measurements look new. */
internal class TeyesTelemetryFreshness {
    private data class Sample(val value: Int, val receivedAt: Long)

    private val samples = mutableMapOf<Int, Sample>()
    var lastUpdateElapsedRealtimeMs: Long? = null
        private set

    fun update(
        code: Int,
        value: Int,
        now: Long,
    ): Boolean {
        val changed = samples[code]?.let { it.value != value } == true
        samples[code] = Sample(value, now)
        lastUpdateElapsedRealtimeMs = now
        return changed
    }

    fun snapshot(now: Long): Map<Int, Int> =
        samples.filter { (code, sample) ->
            val age = now - sample.receivedAt
            age >= 0 && validValue(code, sample.value) && (code == 1000 || age < lifetimeMs(code))
        }.mapValues { it.value.value }

    fun clear() {
        samples.clear()
        lastUpdateElapsedRealtimeMs = null
    }

    private fun lifetimeMs(code: Int): Long =
        when (code) {
            89, 90 -> 5_000L
            in 0..5, in 36..41 -> 30_000L
            137, 179, 180, 181 -> 300_000L
            else -> 60_000L
        }

    private fun validValue(
        code: Int,
        value: Int,
    ): Boolean =
        when (code) {
            89 -> value in 0..400
            90 -> value in 0..10_000
            137 -> value in 0..100
            in 0..5, in 36..41 -> value in 0..1
            179, 180 -> value in 0..1
            181 -> value >= 0
            else -> true
        }
}

internal class TeyesClimatePopupPolicy {
    private var connectedAt = 0L
    private var lastPopupAt: Long? = null

    fun reset(now: Long) {
        connectedAt = now
        lastPopupAt = null
    }

    fun shouldShow(
        changed: Boolean,
        climateField: Boolean,
        now: Long,
    ): Boolean {
        if (!changed || !climateField || now - connectedAt < 2_000L) return false
        if (lastPopupAt?.let { now - it < 600L } == true) return false
        lastPopupAt = now
        return true
    }
}

/** Stop retrying a missing/broken proprietary service instead of binding forever. */
internal class TeyesTelemetryReconnectPolicy {
    private var attempts = 0

    fun nextDelayMs(): Long? {
        if (attempts >= 5) return null
        return (1_000L shl attempts++).coerceAtMost(16_000L)
    }

    fun reset() {
        attempts = 0
    }
}

@get:androidx.annotation.StringRes
val TeyesTelemetryHealth.labelRes: Int
    get() = when (this) {
        TeyesTelemetryHealth.DISCONNECTED -> com.cabin.R.string.telemetry_disconnected
        TeyesTelemetryHealth.CONNECTING -> com.cabin.R.string.telemetry_connecting
        TeyesTelemetryHealth.LIVE -> com.cabin.R.string.telemetry_live
        TeyesTelemetryHealth.STALE -> com.cabin.R.string.telemetry_stale
    }
