package com.cabin.platform

internal enum class SyuSoundControlKind { PRESET, EQ_GAIN, BALANCE, FADER, LOUDNESS, SUBWOOFER_GAIN }

internal data class SyuSoundControl(
    val key: String,
    val kind: SyuSoundControlKind,
    val current: Int,
    val range: IntRange,
    val band: Int? = null,
)

internal data class SyuSoundCommand(val code: Int, val ints: IntArray)

/** Verified Joying 2023-08-31 Equalizer client contracts, not a raw DSP driver.
 * Values are vendor steps, deliberately not advertised as decibels or frequencies.
 * The caller must clear samples on identity change/disconnect and expire stale samples.
 */
internal object SyuSoundProtocol {
    const val MODULE = 4
    const val IDENTITY = 1
    const val EQ_SAMPLE_BASE = 1000

    private data class Profile(val bands: Int, val gain: IntRange)
    // The 7738 customer-specific axis transform is intentionally not selected by ID alone.
    private fun profile(moduleId: Int?): Profile? = when (moduleId) {
        6, 7 -> Profile(32, 0..24)
        11 -> Profile(36, 0..20)
        else -> null
    }

    // Names from the inspected factory client; these are service profiles, not chip probes.
    fun profileName(moduleId: Int?): String? = when (moduleId) {
        6 -> "C32107"
        7 -> "C7602"
        11 -> "AKM7604"
        else -> null
    }

    fun supported(moduleId: Int?): Boolean = profile(moduleId) != null

    fun fields(moduleId: Int?): Set<Int> =
        when (moduleId) {
            11 -> setOf(IDENTITY, 2, 3, 8, 9, 10, 11, 26)
            6, 7 -> setOf(IDENTITY, 2, 3, 8, 9, 10, 11)
            else -> setOf(IDENTITY)
        }

    /** One field-9 callback is one band. Separate keys let each band's freshness expire. */
    fun normalizedSamples(field: Int, ints: List<Int>): Map<Int, List<Int>> = when {
        field == IDENTITY && ints.size == 1 -> mapOf(field to ints.toList())
        field == 8 && ints.size == 2 -> mapOf(field to ints.toList())
        field in setOf(2, 3, 10, 11, 26) && ints.size == 1 -> mapOf(field to ints.toList())
        field == 9 && ints.size == 2 && ints[0] in 0..35 ->
            mapOf(EQ_SAMPLE_BASE + ints[0] to listOf(ints[1]))
        else -> emptyMap()
    }

    fun mergeSample(samples: Map<Int, List<Int>>, field: Int, ints: List<Int>): Map<Int, List<Int>> =
        samples + normalizedSamples(field, ints)

    fun controls(moduleId: Int?, samples: Map<Int, List<Int>>): List<SyuSoundControl> {
        val profile = profile(moduleId) ?: return emptyList()
        return buildList {
            fun scalar(field: Int, key: String, kind: SyuSoundControlKind, range: IntRange) {
                val value = samples[field]?.singleOrNull() ?: return
                if (value in range) add(SyuSoundControl(key, kind, value, range))
            }
            // Factory user presets 9+ have additional app-side persistence/dialog semantics.
            scalar(10, "preset", SyuSoundControlKind.PRESET, 0..8)
            scalar(11, "loudness", SyuSoundControlKind.LOUDNESS, 0..1)
            if (moduleId == 11) scalar(26, "subwoofer.gain", SyuSoundControlKind.SUBWOOFER_GAIN, 0..10)
            val axes = samples[8]
            if (axes?.size == 2 && axes.all { it in 0..16 }) {
                add(SyuSoundControl("balance", SyuSoundControlKind.BALANCE, axes[0], 0..16))
                add(SyuSoundControl("fader", SyuSoundControlKind.FADER, axes[1], 0..16))
            }
            for (band in 0 until profile.bands) {
                val value = samples[EQ_SAMPLE_BASE + band]?.singleOrNull() ?: continue
                if (value in profile.gain) add(SyuSoundControl("eq.$band", SyuSoundControlKind.EQ_GAIN, value, profile.gain, band))
            }
        }
    }

    // SOUND command 0 delegates step size, limits and call mute policy to the firmware.
    fun volumeCommand(moduleId: Int?, samples: Map<Int, List<Int>>, action: Int): SyuSoundCommand? {
        if (!supported(moduleId) || samples[2]?.singleOrNull() !in 0..255) return null
        if (action !in setOf(-1, -2, -5)) return null
        if (action == -5 && samples[3]?.singleOrNull() !in 0..1) return null
        return SyuSoundCommand(0, intArrayOf(action))
    }

    fun command(moduleId: Int?, samples: Map<Int, List<Int>>, key: String, value: Int): SyuSoundCommand? {
        val control = controls(moduleId, samples).firstOrNull { it.key == key } ?: return null
        if (value !in control.range) return null
        return when (control.kind) {
            SyuSoundControlKind.PRESET -> SyuSoundCommand(2, intArrayOf(value))
            SyuSoundControlKind.SUBWOOFER_GAIN -> SyuSoundCommand(23, intArrayOf(value))
            SyuSoundControlKind.LOUDNESS -> SyuSoundCommand(5, intArrayOf(value))
            SyuSoundControlKind.BALANCE -> SyuSoundCommand(3, intArrayOf(value, samples.getValue(8)[1]))
            SyuSoundControlKind.FADER -> SyuSoundCommand(3, intArrayOf(samples.getValue(8)[0], value))
            SyuSoundControlKind.EQ_GAIN -> SyuSoundCommand(1, intArrayOf(requireNotNull(control.band), value))
        }
    }
}
