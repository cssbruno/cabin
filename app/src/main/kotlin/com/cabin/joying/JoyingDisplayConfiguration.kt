package com.cabin.joying

/** Stock f.a.b clamps by orientation and rounds both dimensions down to multiples of eight. */
internal data class JoyingDisplayConfiguration(val width: Int, val height: Int) {
    fun nativeValues(): IntArray = intArrayOf(0, 0, width, height, width, height,
        221, (221f * height / width + 0.5f).toInt(), 0, 0x53667073, 30)

    companion object {
        fun fromDisplay(width: Int, height: Int): JoyingDisplayConfiguration {
            require(width >= 8 && height >= 8)
            val portrait = width <= height
            val scale = minOf(1.0, (if (portrait) 1080.0 else 1920.0) / width,
                (if (portrait) 1920.0 else 1080.0) / height)
            fun align(value: Int) = ((value * scale).toInt() / 8 * 8).coerceAtLeast(8)
            return JoyingDisplayConfiguration(align(width), align(height))
        }
    }
}
