package com.cabin.platform

/** Joying 2023-08-31 Wc_16Civic_Pannel and listeners. See documents/research/HONDA-FYT-PANEL.md.
 * Profile visibility is taken from onResume; no low-word matching or year/trim inference.
 * These are SYU module 7 client commands, never Honda Binder or OBD identifiers.
 */
internal object SyuHondaPanelProtocol {
    private val wcProfiles = setOf(0x40141, 0x50141, 0x60141, 0xB0141, 0xC0141, 0xD0141)
    private val rzcProfiles = setOf(0x10012a, 0x11012a, 0x29012a)
    private val bnrProfiles = setOf(0x6012a, 0x7012a, 0x8012a, 0x9012a, 0xa012a, 0xb012a, 0xf012a, 0x28012a)
    val profiles = wcProfiles + rzcProfiles + bnrProfiles
    private val rzcKeys = mapOf(
        SyuFactoryControl.HONDA_DISTANCE_UNITS to 21,
        SyuFactoryControl.HONDA_TACHOMETER_DISPLAY to 22,
        SyuFactoryControl.HONDA_TACHOMETER_SETTING to 35,
    )
    private val extended = setOf(0x40141, 0xC0141, 0xD0141)
    private val reverseOnly = setOf(0x50141, 0x60141)
    private val keys = mapOf(
        SyuFactoryControl.HONDA_TURN_BY_TURN to 16,
        SyuFactoryControl.HONDA_WARNING_MESSAGE to 15,
        SyuFactoryControl.HONDA_PANEL_CONFIG to 14,
        SyuFactoryControl.HONDA_REVERSE_TONE to 9,
        SyuFactoryControl.HONDA_SPEED_TIPS to 6,
        SyuFactoryControl.HONDA_MESSAGES to 7,
        SyuFactoryControl.HONDA_IDLE_STOP_TIPS to 8,
        SyuFactoryControl.HONDA_ECO_BACKLIGHT to 5,
        SyuFactoryControl.HONDA_TRAFFIC_SIGNS to 13,
        SyuFactoryControl.HONDA_ALARM_VOLUME to 4,
        SyuFactoryControl.HONDA_TRIP_B_RESET to 3,
        SyuFactoryControl.HONDA_TRIP_A_RESET to 2,
        SyuFactoryControl.HONDA_OUTSIDE_TEMP to 1,
    )
    // The stock screen labels zero as Invalid for these four fields.
    private val oneBased = setOf(SyuFactoryControl.HONDA_ALARM_VOLUME,
        SyuFactoryControl.HONDA_TRIP_A_RESET, SyuFactoryControl.HONDA_TRIP_B_RESET,
        SyuFactoryControl.HONDA_OUTSIDE_TEMP)

    fun controls(profile: Int): Set<SyuFactoryControl> {
        if (profile in rzcProfiles) return rzcKeys.keys
        // The stock BNR entry route hides units, despite sharing the handler.
        if (profile in bnrProfiles) return rzcKeys.keys - SyuFactoryControl.HONDA_DISTANCE_UNITS
        if (profile !in profiles) return emptySet()
        return keys.keys.filter { control -> when (control) {
            SyuFactoryControl.HONDA_TURN_BY_TURN, SyuFactoryControl.HONDA_WARNING_MESSAGE,
            SyuFactoryControl.HONDA_PANEL_CONFIG -> profile in extended
            SyuFactoryControl.HONDA_REVERSE_TONE -> profile in reverseOnly || profile == 0xB0141
            SyuFactoryControl.HONDA_SPEED_TIPS, SyuFactoryControl.HONDA_MESSAGES -> profile !in reverseOnly
            SyuFactoryControl.HONDA_TRAFFIC_SIGNS -> profile == 0xB0141
            else -> true
        } }.toSet()
    }

    fun decode(control: SyuFactoryControl, raw: Int): Int? {
        // RZC/BNR feedback is a full integer, unlike the WC low-byte encoding.
        if (control in rzcKeys) return raw.takeIf { it in 0..control.maximum }
        if (control !in keys || raw !in 0..65535) return null
        val value = (raw and 255) - if (control in oneBased) 1 else 0
        return value.takeIf { it in 0..control.maximum }
    }

    fun frame(profile: Int, control: SyuFactoryControl, value: Int): Pair<Int, List<Int>>? {
        if (control !in controls(profile) || value !in 0..control.maximum) return null
        rzcKeys[control]?.let { return 105 to listOf(it, value) }
        return 106 to listOf(keys.getValue(control), value + if (control in oneBased) 1 else 0)
    }
}
