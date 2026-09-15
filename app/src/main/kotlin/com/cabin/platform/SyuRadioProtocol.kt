package com.cabin.platform

internal data class SyuRadioState(
    val connected: Boolean = false,
    val samples: Map<Int, Int> = emptyMap(),
    val epoch: Long = 0,
) {
    val band: Int? get() = samples[0]?.takeIf { connected && (it in 0..1 || it in 65536..65538) }
    val frequency: Int? get() = samples[1]?.takeIf { SyuRadioProtocol.validFrequency(band, it) }
    val presets: Map<Int, Int> get() = samples.filter { (key, value) ->
        connected && key >= 100000 && SyuRadioProtocol.validChannel(key - 100000) &&
            SyuRadioProtocol.validFrequency(if (key - 100000 >= 65536) 65536 else 0, value)
    }.mapKeys { it.key - 100000 }
}

/** Joying factory radio Ipc_New uses module 1 and these exact client frames. */
internal object SyuRadioProtocol {
    fun validChannel(channel: Int) = channel in 0..11 || channel in 65536..65553
    fun validFrequency(band: Int?, value: Int) = when (band) {
        0, 1 -> value in 150..30000
        in 65536..65538 -> value in 6500..10800
        else -> false
    }
    fun sample(field: Int, values: List<Int>): Pair<Int, Int>? = when {
        field in 0..1 && values.size == 1 -> field to values[0]
        field == 4 && values.size == 2 && validChannel(values[0]) -> (100000 + values[0]) to values[1]
        else -> null
    }
    fun command(state: SyuRadioState, code: Int, channel: Int?): Pair<Int, IntArray?>? {
        if (!state.connected || state.frequency == null) return null
        return when {
            code in 3..6 && channel == null -> code to null // step up/down, seek up/down
            code in 7..8 && channel != null && channel in state.presets &&
                (code == 7 || (channel >= 65536) == ((state.band ?: 0) >= 65536)) -> code to intArrayOf(channel)
            else -> null
        }
    }
    fun label(band: Int?, frequency: Int): String =
        if (band != null && band >= 65536) java.lang.String.format(java.util.Locale.ROOT, "%.2f MHz", frequency / 100.0)
        else "$frequency kHz"
}
