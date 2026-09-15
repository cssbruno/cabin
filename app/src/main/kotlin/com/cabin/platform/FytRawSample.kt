package com.cabin.platform

import android.os.Parcel

/** Local diagnostic payload, with no assigned unit or vehicle meaning. Never sent to telemetry. */
data class FytRawSample(
    val integers: List<Int> = emptyList(),
    val floats: List<Float> = emptyList(),
    val strings: List<String?> = emptyList(),
) {
    override fun toString(): String = listOfNotNull(
        integers.takeIf { it.isNotEmpty() }?.joinToString(prefix = "int: "),
        floats.takeIf { it.isNotEmpty() }?.joinToString(prefix = "float: "),
        strings.takeIf { it.isNotEmpty() }?.joinToString(prefix = "text: ") { it ?: "—" },
    ).joinToString("\n").ifEmpty { "—" }

    internal companion object {
        fun read(data: Parcel): FytRawSample {
            val payload = SyuModuleObjectCodec.readPayload(data)
            return FytRawSample(payload.ints?.toList().orEmpty(), payload.floats?.toList().orEmpty(), payload.strings?.toList().orEmpty())
        }
    }
}
