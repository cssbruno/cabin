package com.cabin.platform

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable settings only: widget host IDs, recordings, locations and credentials are excluded. */
class PortableConfigurationBackup(private val context: Context) {
    private val app = context.applicationContext
    internal var writeInterceptor: ((String) -> Unit)? = null
    data class Difference(val section: String, val key: String, val before: String, val after: String)
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun snapshot(): String = synchronized(lock) {
        recoverPending(app)
        JSONObject().put("format", FORMAT).put("schema", 1).put("legacy", TeyesConfigurationBackup.encode(TeyesFeaturePreferences.get(app).configurationSnapshot()))
            .put("sections", JSONObject().apply { stores.forEach { (section, file) ->
                put(section, encodeValues(portableValues(section, prefs(file).all.filter { allowed(section, it.key) })))
            } }).toString(2).also { require(it.toByteArray().size <= MAX_BYTES); decode(it) }
    }

    fun availableSections(text: String): Set<String> = decode(text).keys
    fun diff(text: String): List<Difference> = decode(text).flatMap { (section, incoming) ->
        val current = prefs(stores.getValue(section)).all.filterKeys { allowed(section, it) }
        (current.keys + incoming.keys).sorted().mapNotNull { key ->
            if (current[key] == incoming[key]) null else Difference(section, key, current[key]?.toString().orEmpty(), incoming[key]?.toString().orEmpty())
        }
    }

    /** Journal old values before the first store write; a failed write or restart rolls every store back. */
    fun restore(text: String, selectedSections: Set<String> = availableSections(text)): Boolean = synchronized(lock) {
        val incoming = decode(text)
        require(selectedSections.all(incoming::containsKey))
        if (selectedSections.isEmpty()) return@synchronized true
        recoverPending(app)
        val before = selectedSections.associateWith { section -> prefs(stores.getValue(section)).all.filterKeys { allowed(section, it) }.toMap() }
        val journal = AtomicFile(File(app.noBackupFilesDir, JOURNAL))
        val bytes = JSONObject().apply { before.forEach { (section, values) -> put(section, encodeValues(values)) } }.toString().toByteArray()
        val output = journal.startWrite()
        try { output.write(bytes); journal.finishWrite(output) } catch (e: Exception) { journal.failWrite(output); throw e }
        try {
            selectedSections.forEach { section ->
                writeInterceptor?.invoke(section)
                val preference = prefs(stores.getValue(section))
                val merged = preference.all.filterKeys { !allowed(section, it) } + incoming.getValue(section)
                check(write(preference, merged)) { "Configuration write failed" }
            }
            journal.delete()
            TeyesFeaturePreferences.get(app).refresh()
            true
        } catch (_: Exception) {
            // Keep the journal when rollback cannot persist; startup will retry before any preference readers.
            var restored = true
            before.forEach { (section, values) -> restored = restoreSection(prefs(stores.getValue(section)), section, values) && restored }
            if (restored) journal.delete()
            TeyesFeaturePreferences.get(app).refresh()
            false
        }
    }

    private fun decode(text: String): Map<String, Map<String, Any>> = try { decodeChecked(text) }
        catch (error: Exception) { throw IllegalArgumentException("Invalid portable configuration", error) }
    private fun decodeChecked(text: String): Map<String, Map<String, Any>> {
        require(text.toByteArray().size <= MAX_BYTES)
        validateBackupJson(text)
        val root = JSONObject(text)
        if (root.optString("format") == "carlink-teyes-settings") return legacyValues(TeyesConfigurationBackup.decode(text))
        require(root.getString("format") == FORMAT && root.get("schema") == 1)
        require(root.keys().asSequence().toSet() == setOf("format", "schema", "legacy", "sections"))
        val sections = root.getJSONObject("sections")
        require(sections.length() <= stores.size)
        val result = sections.keys().asSequence().associateWith { section ->
            require(section in stores)
            decodeValues(sections.getJSONObject(section)).also { values -> require(values.keys.all { allowed(section, it) }); validatePortableSection(section, values) }
        }.toMutableMap()
        // The established validator continues to validate all driver/control settings.
        val legacy = TeyesConfigurationBackup.decode(root.getString("legacy"))
        legacyValues(legacy).forEach { (section, values) -> if (section == "drivers" || section !in result) result[section] = values }
        return result
    }

    private fun legacyValues(snapshot: TeyesConfigurationSnapshot): Map<String, Map<String, Any>> = buildMap {
        put("drivers", buildMap {
            snapshot.profiles.forEach { p -> val prefix = "driver.${p.slot}."
                put(prefix + "name", p.name); put(prefix + "phone", p.preferredPhone); put(prefix + "appearance", p.appearance.name)
                put(prefix + "brightness", p.nightBrightness); put(prefix + "media", p.mediaGain); put(prefix + "nav", p.navigationGain)
                put(prefix + "wake", p.resumeOnWake); put(prefix + "overlays", p.recoverOverlays); put(prefix + "compact", p.compactOnLaunch)
            }
            snapshot.keys.forEach { (k,v) -> put("key.$k", v.name) }; snapshot.longKeys.forEach { (k,v) -> put("longKey.$k", v.name) }
            snapshot.keyApps.forEach { (k,v) -> put("keyApp.$k", v) }; snapshot.longKeyApps.forEach { (k,v) -> put("longKeyApp.$k", v) }
            snapshot.shortcuts.forEach { (k,v) -> put("shortcut.${k.name}", v) }
        })
        snapshot.projection?.let { p -> val slot = (prefs("teyes_features_v1").all["active"] as? Int ?: 0).coerceIn(0, 2)
            val prefix = "driver.$slot."
            put("projection", prefs(stores.getValue("projection")).all.filterValues { it != null }.mapValues { it.value!! } + mapOf(prefix + "focus_controls" to p.focusControls, prefix + "vehicle_hud" to p.vehicleHud,
            prefix + "climate_notice_mode" to p.climateNoticeMode.name, prefix + "return_when_ready" to p.returnWhenReady, prefix + "control_side" to p.controlSide.name)) }
        snapshot.measurementUnit?.let { put("units", mapOf("unit" to it.name)) }
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        private const val FORMAT = "cabin-portable-settings"
        private const val JOURNAL = "configuration-restore-journal.json"
        private val lock = Any()
        val stores = linkedMapOf("drivers" to "teyes_features_v1", "projection" to "projection_presentation_v1", "units" to "measurement_presentation_v1",
            "launcher" to "carlink_launcher_v1", "library" to "cabin_launcher_library_v1", "dashboard" to "carlink_dashboard_v1",
            "automation" to "cabin_automation", "appearance" to "cabin_vehicle_appearance", "trips" to "cabin_trips", "vehicle" to "cabin_vehicle_tools", "accessibility" to "cabin_accessibility_v1", "audio" to "audio_experience_v1")
        private fun portableValues(section: String, values: Map<String, *>): Map<String, *> {
            if(section == "library") return values.mapValues { (_, value) ->
                if(value is String) JSONObject(value).put("usage", JSONObject()).toString() else value
            }
            if (section != "dashboard") return values
            return values.mapValues { (_, value) ->
                if (value !is String) value else try {
                    val root = JSONObject(value); val tiles = root.optJSONArray("tiles")
                    if (tiles != null) root.put("tiles", JSONArray().apply {
                        repeat(tiles.length()) { i -> val tile = tiles.getJSONObject(i)
                            if (tile.optString("kind") != "WIDGET") { tile.put("widget", 0); put(tile) }
                        }
                    })
                    root.toString()
                } catch (_: Exception) { value }
            }
        }
        internal fun allowed(section: String, key: String): Boolean = when(section) {
            "drivers" -> key.startsWith("driver.") || key.startsWith("key.") || key.startsWith("longKey.") || key.startsWith("keyApp.") || key.startsWith("longKeyApp.") || key.startsWith("shortcut.")
            "launcher" -> key.startsWith("favorites.") || key.startsWith("gauges.")
            "library" -> !key.startsWith("recent.") && !key.startsWith("undo.")
            "dashboard" -> !key.contains("widget", true)
            "automation" -> key in setOf("quiet", "solar", "parked", "start", "end") || key.startsWith("parkingRetention.")
            "trips" -> key.startsWith("fuel") || key.startsWith("retention.") || key.startsWith("recording.") || key.startsWith("maintenance.") || key.startsWith("maintenanceInterval.") || key.startsWith("maintenanceItems.")
            "audio" -> key == "buffering" || key.startsWith("presets.")
            "vehicle" -> key == "readOnly" || key == "climateTimeout" || key == "climateFavorites" || key == "airFavorites" || key.startsWith("favorites.")
            else -> true
        }
        fun recoverPending(context: Context) = synchronized(lock) {
            val journal = AtomicFile(File(context.noBackupFilesDir, JOURNAL))
            if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return@synchronized
            val bytes = journal.openRead().use { it.readBytes() }
            require(bytes.size <= MAX_BYTES * 4)
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            var success = true
            root.keys().asSequence().forEach { section ->
                val file = stores[section] ?: error("Unknown restore section")
                success = restoreSection(context.getSharedPreferences(file, Context.MODE_PRIVATE), section, decodeValues(root.getJSONObject(section))) && success
            }
            check(success) { "Configuration recovery could not persist" }
            journal.delete()
        }
        private fun restoreSection(prefs: SharedPreferences, section: String, values: Map<String, *>): Boolean =
            write(prefs, prefs.all.filterKeys { !allowed(section, it) } + values)
        private fun write(prefs: SharedPreferences, values: Map<String, *>): Boolean {
            val edit = prefs.edit().clear()
            values.forEach { (key, value) -> when(value) {
                is String -> edit.putString(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value); is Boolean -> edit.putBoolean(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                else -> error("Unsupported preference type")
            } }
            return edit.commit()
        }
        private fun encodeValues(values: Map<String, *>): JSONObject = JSONObject().apply { values.forEach { (key,value) ->
            val type = when(value) { is String -> "s"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Boolean -> "b"; is Set<*> -> "a"; else -> error("Invalid preference") }
            put(key, JSONObject().put("t", type).put("v", if (value is Set<*>) JSONArray(value.toList()) else value))
        } }
        private fun decodeValues(json: JSONObject): Map<String, Any> {
            require(json.length() <= 4096)
            return json.keys().asSequence().associateWith { key ->
                require(key.length in 1..256 && key.none(Char::isISOControl))
                val entry = json.getJSONObject(key)
                require(entry.keys().asSequence().toSet() == setOf("t", "v"))
                when(entry.getString("t")) {
                    "s" -> (entry.get("v") as? String ?: error("String required")).also { require(it.length <= 512_000) }
                    "i" -> entry.get("v") as? Int ?: error("Integer required")
                    "l" -> (entry.get("v") as? Number)?.takeIf { it is Int || it is Long }?.toLong() ?: error("Long required")
                    "f" -> (entry.get("v") as? Number ?: error("Number required")).toFloat().also { require(it.isFinite()) }
                    "b" -> entry.get("v") as? Boolean ?: error("Boolean required")
                    "a" -> entry.getJSONArray("v").let { array -> require(array.length() <= 1000); (0 until array.length()).map { (array.get(it) as? String ?: error("String required")).also { require(it.length <= 512) } }.toSet() }
                    else -> error("Unsupported value type")
                }
            }
        }
    }
}

/** AES-GCM authenticates the whole plaintext before JSON is exposed. Passwords stay in caller memory. */
object PasswordBackup {
    private const val ITERATIONS = 210_000
    fun encrypt(plain: String, password: CharArray): String {
        require(password.size in 8..1024 && plain.toByteArray().size <= PortableConfigurationBackup.MAX_BYTES)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes); val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        return JSONObject().put("format", "cabin-encrypted-backup").put("schema", 1).put("iterations", ITERATIONS)
            .put("salt", b64(salt)).put("nonce", b64(nonce)).put("data", b64(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))).toString()
    }
    fun decrypt(text: String, password: CharArray): String {
        require(text.length <= PortableConfigurationBackup.MAX_BYTES * 2 && password.size <= 1024)
        validateBackupJson(text)
        val root = JSONObject(text)
        require(root.keys().asSequence().toSet() == setOf("format", "schema", "iterations", "salt", "nonce", "data"))
        require(root.getString("format") == "cabin-encrypted-backup" && root.getInt("schema") == 1 && root.getInt("iterations") == ITERATIONS)
        val salt = unb64(root.getString("salt")); val nonce = unb64(root.getString("nonce"))
        require(salt.size == 16 && nonce.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(unb64(root.getString("data"))), Charsets.UTF_8)
    }
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun unb64(text: String) = android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
}
