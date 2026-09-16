package com.cabin.carlink

import android.content.Context
import java.io.File

/** App-owned preferences. A new session takes one consistent snapshot. */
internal data class CarlinkSettings(
    val fps: Int = 25,
    val band: Int = 0,
    val autoConnect: Boolean = true,
    val logo: Int = -1,
) {
    init {
        require(fps in listOf(20, 25, 30, 60))
        require(band in 0..1)
        require(logo in -1..87)
    }
    val channel: Int get() = if (band == 1) 36 else 6
    fun save(context: Context) {
        context.getSharedPreferences("carlink_settings", Context.MODE_PRIVATE).edit()
            .putInt("fps", fps).putInt("band", band).putBoolean("auto_connect", autoConnect)
            .putInt("logo", logo).apply()
    }
    fun logoFile(context: Context): File? {
        if (logo < 0) return null
        val file = File(context.filesDir, "carlink/logo.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        context.assets.open("carlink/logos/%03d.png".format(java.util.Locale.ROOT, logo)).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }
    companion object {
        fun read(context: Context): CarlinkSettings {
            val prefs = context.getSharedPreferences("carlink_settings", Context.MODE_PRIVATE)
            return CarlinkSettings(
                prefs.getInt("fps", 25).takeIf { it in listOf(20, 25, 30, 60) } ?: 25,
                prefs.getInt("band", 0).coerceIn(0, 1), prefs.getBoolean("auto_connect", true),
                prefs.getInt("logo", -1).coerceIn(-1, 87),
            )
        }
    }
}

/** Stock SettingsFragment uses this firmware control; verify writes before claiming success. */
internal object CarlinkMicrophone {
    private const val KEY = "persist.lsec.cp.micLR"
    fun read(): Int = runCatching { readProperty() }.getOrDefault(2)
    private fun readProperty(): Int =
        Class.forName("android.os.SystemProperties").getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, KEY, 2) as Int
    fun set(value: Int) {
        require(value in 0..2)
        Class.forName("android.os.SystemProperties").getMethod("set", String::class.java, String::class.java)
            .invoke(null, KEY, value.toString())
        check(readProperty() == value) { "Microphone setting was rejected by firmware" }
    }
}
