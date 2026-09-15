package com.cabin.platform

/** IDs from the Joying 2.23.0711.1001 module-10 implementation and matching steer client.
 * ADC slots, MCU keys, and custom MCU functions are different namespaces.
 */
internal enum class SteeringLearningMode { ADC, MCU }
internal enum class SteeringHardwareStatus {
    DISCONNECTED, READY, STARTED, ASSIGNING, LEARNED, SAVE_SENT, CLEAR_SENT, TIMED_OUT, FAILED,
}
internal data class SteeringHardwareState(
    val connected: Boolean = false,
    val epoch: Long = 0,
    val feedbackSeen: Boolean = false,
    val detecting: Boolean? = null,
    val adc: Int? = null,
    val mcuEnabled: Boolean? = null,
    val learnedAdc: Map<Int, Int> = emptyMap(),
    val mcuKeys: Map<Int, Int> = emptyMap(),
    val mode: SteeringLearningMode? = null,
    val status: SteeringHardwareStatus = SteeringHardwareStatus.DISCONNECTED,
    val pending: Int? = null,
)
internal data class SteeringCommand(val code: Int, val values: List<Int> = emptyList())

internal object SyuSteeringProtocol {
    const val MODULE = 10
    val fields = listOf(0, 1, 2, 3, 4, 5, 6)
    val adcFunctions = listOf("power", "mode", "mute", "volume_up", "volume_down", "previous", "next",
        "navigation", "phone", "hang_up", "play_pause", "fast_forward", "rewind")
    val mcuFunctions = listOf("power", "navigation", "mode", "previous", "next", "home", "back", "volume_up",
        "volume_down", "menu", "apps", "eject", "mute", "voice", "dim", "recents", "play_pause", "camera",
        "phone", "tone_up", "tone_down", "band", "dvd", "panorama", "radio")
    val customFunctions = mapOf(
        3 to "screen_off", 4 to "audio", 5 to "video", 6 to "aux", 7 to "bluetooth", 8 to "bluetooth_music",
        9 to "equalizer", 10 to "tv", 11 to "ipod", 12 to "car_settings", 13 to "settings", 14 to "standby",
        15 to "digit_0", 16 to "digit_1", 17 to "digit_2", 18 to "digit_3", 19 to "digit_4", 20 to "digit_5",
        21 to "digit_6", 22 to "digit_7", 23 to "digit_8", 24 to "digit_9", 25 to "plus", 26 to "star",
        27 to "hash", 28 to "up", 29 to "down", 30 to "left", 31 to "right", 32 to "enter",
        33 to "fast_forward", 34 to "rewind", 35 to "can_app", 36 to "hang_up", 37 to "sd", 38 to "usb",
        39 to "climate", 40 to "front_camera", 41 to "camera_1", 42 to "camera_2", 43 to "camera_3",
        44 to "camera_4", 45 to "camera_5", 46 to "radio_search", 47 to "radio_scan",
    )
    fun start(mode: SteeringLearningMode) = if (mode == SteeringLearningMode.ADC) SteeringCommand(2, listOf(1)) else SteeringCommand(5, listOf(1))
    fun finish(mode: SteeringLearningMode) = if (mode == SteeringLearningMode.ADC) SteeringCommand(2, listOf(0)) else SteeringCommand(5, listOf(2))
    fun save(mode: SteeringLearningMode) = if (mode == SteeringLearningMode.ADC) SteeringCommand(4) else finish(mode)
    fun clear(mode: SteeringLearningMode) = if (mode == SteeringLearningMode.ADC) SteeringCommand(3) else SteeringCommand(5, listOf(3))
    fun assign(mode: SteeringLearningMode, key: Int, function: Int = 1): SteeringCommand? = when (mode) {
        SteeringLearningMode.ADC -> if (key in adcFunctions.indices && function == 1) SteeringCommand(1, listOf(key)) else null
        SteeringLearningMode.MCU -> when {
            key in mcuFunctions.indices && function == 1 -> SteeringCommand(6, listOf(key, 1))
            key in 25..44 && function in customFunctions -> SteeringCommand(6, listOf(key, function))
            else -> null
        }
    }
    fun feedback(state: SteeringHardwareState, field: Int, values: List<Int>): SteeringHardwareState? {
        val valid = when (field) {
            0 -> values.size == 1 && values[0] in -1..99
            1 -> values.size == 2 && values[0] in 0..99 && values[1] in -1..255
            2 -> values.size == 1 && values[0] in 0..255
            3 -> values.size == 2 && values[0] in 0..5 && values[1] in 0..255
            4, 5 -> values.size == 1 && values[0] in 0..1
            6 -> values.size == 2 && values[0] in 0..54 && values[1] in 1..100
            else -> false
        }
        if (!valid) return null
        val ready = state.copy(feedbackSeen = true)
        return when (field) {
            1 -> ready.copy(learnedAdc = ready.learnedAdc + (values[0] to values[1]))
            2 -> ready.copy(adc = values[0])
            4 -> ready.copy(detecting = values[0] == 1)
            5 -> ready.copy(mcuEnabled = values[0] == 1)
            6 -> ready.copy(mcuKeys = ready.mcuKeys + (values[0] to values[1]))
            else -> ready
        }
    }
}
