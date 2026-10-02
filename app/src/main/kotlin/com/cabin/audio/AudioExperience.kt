package com.cabin.audio

import android.content.Context
import com.cabin.platform.AudioConfig
import org.json.JSONArray
import org.json.JSONObject

/** Selected profiles change allocation only at the next audio session initialization. */
enum class AudioBufferProfile { PLATFORM, RESPONSIVE, BALANCED, RESILIENT;
    fun apply(base: AudioConfig): AudioConfig = when (this) {
        PLATFORM -> base
        RESPONSIVE -> base.copy(prefillThresholdMs = 30, mediaBufferCapacityMs = 250, navBufferCapacityMs = 100)
        BALANCED -> base.copy(prefillThresholdMs = 60, mediaBufferCapacityMs = 500, navBufferCapacityMs = 200)
        RESILIENT -> base.copy(prefillThresholdMs = 120, mediaBufferCapacityMs = 1500, navBufferCapacityMs = 600)
    }
}
data class AudioGainPreset(val name: String, val media: Float, val navigation: Float)
data class AudioStreamHealth(val purpose: String, val bufferMs: Int, val underruns: Int, val overflows: Int, val focus: String)
data class AudioHealth(val streams: List<AudioStreamHealth> = emptyList(), val output: String? = null)

class AudioExperience(context: Context) {
    private val drivers = com.cabin.platform.TeyesFeaturePreferences.get(context)
    private val prefs = context.applicationContext.getSharedPreferences("audio_experience_v1", Context.MODE_PRIVATE)
    var buffering: AudioBufferProfile
        get() = AudioBufferProfile.entries.firstOrNull { it.name == prefs.all["buffering"] } ?: AudioBufferProfile.PLATFORM
        set(value) { prefs.edit().putString("buffering", value.name).apply() }
    var microphoneId: Int
        get() = prefs.all["microphone"] as? Int ?: 0
        set(value) { prefs.edit().putInt("microphone", value.coerceAtLeast(0)).apply() }
    fun presets(slot: Int): List<AudioGainPreset> {
        val array = runCatching { JSONArray(prefs.all["presets.$slot"] as? String ?: "[]") }.getOrDefault(JSONArray())
        return (0 until minOf(array.length(), 8)).mapNotNull { index -> runCatching {
            val row = array.getJSONObject(index)
            AudioGainPreset(row.getString("name").take(40), row.getDouble("media").toFloat(), row.getDouble("nav").toFloat())
                .takeIf { it.name.isNotBlank() && it.media.isFinite() && it.navigation.isFinite() && it.media in 0f..1f && it.navigation in 0f..1f }
        }.getOrNull() }
    }
    fun save(slot: Int, value: AudioGainPreset, replacing: String? = null): Boolean {
        if (drivers.guestActive.value) return false
        val name = value.name.trim().take(40)
        if (name.isBlank() || !value.media.isFinite() || !value.navigation.isFinite()) return false
        val rows = presets(slot).filter { it.name != replacing && it.name != name }
        if (rows.size >= 8) return false
        write(slot, rows + value.copy(name = name, media = value.media.coerceIn(0f, 1f), navigation = value.navigation.coerceIn(0f, 1f)))
        return true
    }
    fun delete(slot: Int, name: String) {
        if (!drivers.guestActive.value) write(slot, presets(slot).filter { it.name != name })
    }
    private fun write(slot: Int, rows: List<AudioGainPreset>) {
        val array = JSONArray()
        rows.forEach { array.put(JSONObject().put("name", it.name).put("media", it.media).put("nav", it.navigation)) }
        prefs.edit().putString("presets.$slot", array.toString()).apply()
    }
}

/** Unit-testable monotonic interpolation; malformed wire values never reach AudioTrack. */
internal fun interpolatedGain(start: Float, target: Float, elapsedMs: Long, durationMs: Long): Float {
    val safeTarget = target.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
    if (durationMs <= 0) return safeTarget
    return (start + (safeTarget - start) * (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)).coerceIn(0f, 1f)
}
