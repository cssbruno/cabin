package com.cabin.platform

/** Shared Honda trip packets verified in r/h1/i1/g/b4/c5/m/l6/y9/kc, not inferred from car names. */
internal object CabinHondaEarlyTrip {
    private val profiles = mapOf(
        19 to "0019_XP1_JieDe", 64 to "0064_WC2_LingPai", 65 to "0065_WC2_JieDe",
        117 to "0117_XP1_LINGPAI", 65653 to "0117_XP1_LINGPAI", 131189 to "0117_XP1_LINGPAI", 196725 to "0117_XP1_LINGPAI",
        141 to "0141_RZC_XP1_LingPai", 166 to "0166_WC2_15_AoDeSai", 196774 to "0166_WC2_15_AoDeSai",
        192 to "0192_WC2_15_BinZhi", 65728 to "0192_WC2_15_BinZhi", 203 to "0203_RZC_XP1_AoDeSai",
        196811 to "0203_RZC_XP1_AoDeSai", 262347 to "0203_RZC_XP1_AoDeSai", 297 to "0297_WC2_15_CRV", 65833 to "0297_WC2_15_CRV",
        370 to "0370_RZC_XP1_15FengFan", 65906 to "0370_RZC_XP1_15FengFan", 131442 to "0370_RZC_XP1_15FengFan")
    private val wc = setOf(64, 65, 166, 196774, 192, 65728, 297, 65833)
    private val resetViaRead = setOf(141, 203, 196811, 262347, 370, 65906, 131442)
    fun supports(profile: Int) = profile in profiles
    fun dialect(profile: Int, callback: String): String? = profiles[profile]?.let { name ->
        "honda_early_trip".takeIf { callback == "Lcom/syu/module/canbus/Callback_$name;" }
    }
    fun requests(profile: Int, published: Set<Int>): List<Pair<Int, List<Int>>> =
        if (supports(profile) && profile !in wc) CabinHondaTrip.requests(profile, published) else emptyList()
    fun reset(profile: Int): Pair<Int, List<Int>>? = if (!supports(profile)) null
        else (if (profile in resetViaRead) 100 else 101) to listOf(3)
}
