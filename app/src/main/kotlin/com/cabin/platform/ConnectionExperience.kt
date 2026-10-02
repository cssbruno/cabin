package com.cabin.platform

import android.content.Context
import com.cabin.R
import org.json.JSONArray
import org.json.JSONObject

/** Stable codes produced at the failure site. UI must never classify diagnostic log text. */
enum class ConnectionFailure(val message: Int, val retryable: Boolean = false) {
    NONE(R.string.gx_connection_ready),
    USER_STOP(R.string.gx_user_stopped),
    ADAPTER_MISSING(R.string.gx_adapter_missing, true),
    ADAPTER_CHOICE(R.string.gx_adapter_choice),
    PERMISSION_NEEDED(R.string.gx_permission_needed),
    OPEN_FAILED(R.string.gx_open_failed, true),
    WRITE_FAILED(R.string.gx_write_failed, true),
    CORRUPT_PACKET(R.string.gx_corrupt_packet, true),
    ADAPTER_TIMEOUT(R.string.gx_adapter_timeout, true),
    PHONE_TIMEOUT(R.string.gx_phone_timeout),
    USB_DETACHED(R.string.gx_usb_detached, true),
    ENGINE_FAILED(R.string.gx_engine_failed, true),
    UNKNOWN(R.string.gx_unknown_failure),
}
enum class ConnectionStage(val label: Int, val timeoutMs: Long) {
    IDLE(R.string.gx_stage_idle, 0),
    DISCOVERY(R.string.gx_stage_discovery, 30_000),
    PERMISSION(R.string.gx_stage_permission, 30_000),
    INITIALIZATION(R.string.gx_stage_initialization, 35_000),
    PHONE(R.string.gx_stage_phone, 60_000),
    PICTURE(R.string.gx_stage_picture, 30_000),
    STREAMING(R.string.gx_stage_streaming, 0),
}
data class ConnectionProgress(
    val stage: ConnectionStage = ConnectionStage.IDLE,
    val startedAtMs: Long = 0,
    val failure: ConnectionFailure = ConnectionFailure.NONE,
    val retryAtMs: Long = 0,
    val retriesPaused: Boolean = false,
)
enum class PhoneConnectionPreference { AUTOMATIC, MANUAL, PREFERRED }
data class ConnectionSessionSummary(val endedAt: Long, val durationMs: Long, val reason: ConnectionFailure, val recoveryMs: Long?)

/** Explicit local opt-in; the stored schema accepts numbers and enum codes only. */
class ConnectionHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("connection_summaries_v1", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.all["enabled"] as? Boolean ?: false
        set(value) { prefs.edit().putBoolean("enabled", value).apply(); if (!value) clear() }
    var retentionDays: Int
        get() = (prefs.all["days"] as? Int ?: 7).coerceIn(1, 90)
        set(value) { prefs.edit().putInt("days", value.coerceIn(1, 90)).apply(); prune() }
    @Synchronized fun read(now: Long = System.currentTimeMillis()): List<ConnectionSessionSummary> {
        if (!enabled) return emptyList()
        val array = runCatching { JSONArray(prefs.all["sessions"] as? String ?: "[]") }.getOrDefault(JSONArray())
        val rows = (0 until minOf(array.length(), 100)).mapNotNull { index ->
            runCatching {
                val row = array.getJSONObject(index)
                ConnectionSessionSummary(row.getLong("end"), row.getLong("duration").coerceAtLeast(0),
                    ConnectionFailure.valueOf(row.getString("reason")), row.optLong("recovery", -1).takeIf { it >= 0 })
            }.getOrNull()
        }.filter { it.endedAt >= now - retentionDays * 86_400_000L && it.endedAt <= now }
        if (rows.size != array.length()) write(rows)
        return rows
    }
    @Synchronized fun append(summary: ConnectionSessionSummary) {
        if (!enabled) return
        write((read() + summary).takeLast(100))
    }
    @Synchronized fun clear() { prefs.edit().remove("sessions").apply() }
    private fun prune() = write(read())
    private fun write(rows: List<ConnectionSessionSummary>) {
        val array = JSONArray()
        rows.forEach { row -> array.put(JSONObject().put("end", row.endedAt).put("duration", row.durationMs)
            .put("reason", row.reason.name).apply { row.recoveryMs?.let { put("recovery", it) } }) }
        prefs.edit().putString("sessions", array.toString()).apply()
    }
}

/** A real uninterrupted interval, not merely time since the STREAMING state was entered. */
internal class StreamingStability(private val requiredMs: Long = 30_000L) {
    private var healthySince: Long? = null
    fun observe(now: Long, framesFresh: Boolean): Boolean {
        if (!framesFresh) { healthySince = null; return false }
        val start = healthySince ?: now.also { healthySince = it }
        if (now < start) { healthySince = now; return false }
        return now - start >= requiredMs
    }
}
