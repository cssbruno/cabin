package com.cabin.platform

/** WcGMBasicInfoAct and h0 packets 0x11/0x12/0x32/0x34. */
internal object CabinGmWcReadings {
    const val CALLBACK = "Lcom/syu/module/canbus/Callback_0036_WC2_GM;"
    val fields = setOf(9, 13, 107, 144, 145, 146, 147)
    val motionFields = setOf(13, 107)
    fun motion(raw: Map<Int, Int>): Map<Int, Int> = buildMap {
        raw[13]?.takeIf { it in 1..400 }?.let { put(89, it) }
        raw[107]?.takeIf { it in 1..10000 }?.let { put(90, it) }
    }
    fun read(raw: Map<Int, Int>, pt: Boolean): List<FytSyuReading> = buildList {
        fun row(id: Int, en: String, br: String, text: String) {
            add(FytSyuReading("gm_wc_0036", id, setOf(id), text, label = if (pt) br else en))
        }
        fun decimal(value: Int): String = java.math.BigDecimal(value).movePointLeft(1).setScale(1).toPlainString()
        raw[9]?.takeIf { it in 1..65535 }?.let { row(9, "Instantaneous consumption", "Consumo instantâneo", "${decimal(it)} L/100 km") }
        motion(raw)[89]?.let { row(13, "Speed", "Velocidade", "$it km/h") }
        motion(raw)[90]?.let { row(107, "Engine speed", "Rotação do motor", "$it RPM") }
        raw[144]?.takeIf { it in 0..1 }?.let { row(144, "Parking brake", "Freio de estacionamento",
            if (pt) (if (it == 1) "Acionado" else "Liberado") else (if (it == 1) "Engaged" else "Released")) }
        raw[145]?.takeIf { it in 1..255 }?.let { row(145, "Battery voltage", "Tensão da bateria", "${decimal(it)} V") }
        raw[146]?.takeIf { it in 1..0xffffff }?.let { row(146, "Odometer", "Odômetro", "$it km") }
        raw[147]?.takeIf { it in 0..255 }?.let { row(147, "Outside temperature", "Temperatura externa", "${decimal(it * 5 - 400)}°C") }
    }
}
