package com.cabin.platform

/** Transport availability is distinct from fresh vehicle measurements. */
enum class TeyesTelemetryHealth { DISCONNECTED, CONNECTING, LIVE, STALE }

internal object TeyesClimateControlPolicy {
    const val VERIFIED_ALTERNATE_PROFILE = 262465

    // Exact Civic variants identified by FinalCanbus and the 0298 callback. Not a family wildcard.
    private val civic0298Profiles = setOf(1048874, 1114410, 196906, 262442)

    fun isCivic0298(profile: Int): Boolean = profile in civic0298Profiles

    // SYU Air_Activity_RZC_Focus.initCallbackId: RZC Civic vertical-screen L/H.
    fun supportsTemperature(profile: Int): Boolean = profile == 1048874 || profile == 1114410

    fun canToggle(state: TeyesClimateState, control: TeyesClimateSwitch): Boolean =
        !state.fytReadOnly && supportsTemperature(state.profileId) && state.connected && state.health == TeyesTelemetryHealth.LIVE &&
            control.feedbackCode in state.availableCodes

    fun canAdjustTemperature(state: TeyesClimateState, zone: TeyesTemperatureZone, increase: Boolean): Boolean {
        val raw = if (zone == TeyesTemperatureZone.DRIVER) state.leftTemperature else state.rightTemperature
        val code = if (zone == TeyesTemperatureZone.DRIVER) 25 else 31
        return !state.fytReadOnly && supportsTemperature(state.profileId) && state.connected && state.health == TeyesTelemetryHealth.LIVE &&
            code in state.availableCodes && 33 in state.availableCodes && raw != null &&
            (raw == -2 || raw == -3 || raw in 0..255) &&
            !(increase && raw == -3) && !(!increase && raw == -2)
    }

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
        if (layout == TeyesVehicleDataLayout.UNKNOWN) return values.filterKeys { it == 1000 }
        if (layout == TeyesVehicleDataLayout.JOYING_2023) {
            // Supplied module/canbus/v emits canonical fields directly, unlike
            // the newer public Civic UI. Do not infer speed or temperature scaling.
            return values.filter { (code, value) ->
                code == 1000 || (code == 29 && value in 0..7) ||
                    (code in setOf(20, 21, 22, 23, 24, 26, 27, 28, 30, 32, 33, 34, 36, 37, 38, 39, 40, 41, 51) && value in 0..1)
            }
        }
        if (layout == TeyesVehicleDataLayout.LEGACY) {
            // Preserve the user's working motion interface. Never infer this dialect from profile ID alone.
            return values.filter { (code, value) ->
                (code == 1000 || code in 20..35 || code in 51..57 || code == 89 || code == 90) &&
                    (!supportsTemperature(profile) || code !in setOf(20, 21, 22, 23, 24, 26, 27, 28, 30, 32, 33) || value in 0..1)
            }
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
            if (supportsTemperature(profile)) {
                // SYU Air reports half-degrees Celsius or integer Fahrenheit, with -2/-3 limits.
                copy(27, 25, -3..255)
                copy(28, 31, -3..255)
                copy(37, 33, 0..1)
                copy(10, 32, 0..1) // Power
                copy(12, 21, 0..1) // Recirculation (reference polarity)
                copy(13, 20, 0..1) // Auto
                copy(14, 30, 0..1) // Dual
                copy(65, 22, 0..1) // Front defrost
                copy(16, 23, 0..1) // Rear defrost
            }
            for (door in 0..5) copy(door, door + 36, 0..1)
            // Service DISTANCE, never oil-life percent. Metadata is mandatory.
            if (values[179] in 0..1 && values[180] in 0..1 && values[181]?.let { it >= 0 } == true) {
                put(179, values.getValue(179))
                put(180, values.getValue(180))
                put(181, values.getValue(181))
            }
            // Reference speed149/RPM151 scaling remains unverified.
            // In particular raw89/90 are seat fields here, not motion data.
        }
    }

    fun canControlFan(connected: Boolean, profile: Int, freshValues: Map<Int, Int>): Boolean =
        connected && supports(profile) && freshValues[fanCode(profile)]?.let { it in 0..7 } == true

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

    /** SYU profiles define their own field meanings; do not apply Civic door/motion ranges. */
    fun airSnapshot(now: Long): Map<Int, Int> = samples.filter { (code, sample) ->
        val age = now - sample.receivedAt
        age >= 0 && (code == 1000 || (age < 60_000L && sample.value in -65_535..65_535))
    }.mapValues { it.value.value }

    /** Raw diagnostics retain no inferred units or field-specific range assumptions. */
    fun rawSnapshot(now: Long): Map<Int, Int> = samples.filter { (_, sample) ->
        now - sample.receivedAt in 0 until 60_000L
    }.mapValues { it.value.value }

    fun remove(code: Int) { samples.remove(code) }

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

/**
 * Recover from a vendor service restart without hammering a unit where the service is absent.
 *
 * The first failures use quick exponential backoff.  Afterwards we retain a low-frequency
 * retry because TEYES can restart its CAN service well after the launcher has started.
 */
internal class TeyesTelemetryReconnectPolicy {
    private var attempts = 0

    fun nextDelayMs(): Long =
        if (attempts < 5) (1_000L shl attempts++).coerceAtMost(16_000L) else 30_000L

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
