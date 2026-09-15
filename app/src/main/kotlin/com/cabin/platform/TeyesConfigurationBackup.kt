package com.cabin.platform

import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.StringReader

data class TeyesConfigurationSnapshot(
    val profiles: List<TeyesDriverProfile>,
    val keys: Map<Int, TeyesKeyAction>,
    val shortcuts: Map<TeyesShortcut, String>,
    /** Null only for a legacy schema-1 import: leave current app-wide settings alone. */
    val projection: ProjectionPreferencesState? = null,
    val measurementUnit: MeasurementUnit? = null,
    val longKeys: Map<Int, TeyesKeyAction> = emptyMap(),
    val keyApps: Map<Int, String> = emptyMap(),
    val longKeyApps: Map<Int, String> = emptyMap(),
)

/** Portable app preferences only. Never includes access tokens, logs, adapter or CAN configuration. */
object TeyesConfigurationBackup {
    const val MAX_BYTES = 32_768
    private const val SCHEMA = 4
    private val componentPattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+/\\.?[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")

    fun validComponent(value: String): Boolean = value.length <= 512 && componentPattern.matches(value)

    fun validate(snapshot: TeyesConfigurationSnapshot) {
        require(snapshot.profiles.size == 3 && snapshot.profiles.map { it.slot }.toSet() == setOf(0, 1, 2)) { "Exactly three distinct driver profiles are required." }
        snapshot.profiles.forEach { profile ->
            require(profile.name.length in 1..32 && profile.name == profile.name.trim() && profile.name.none { it.isISOControl() }) { "Invalid profile name." }
            require(profile.preferredPhone.isEmpty() || TeyesFeaturePreferences.validPhone(profile.preferredPhone)) { "Invalid preferred phone address." }
            require(profile.nightBrightness.isFinite() && profile.nightBrightness in 0.1f..1f) { "Invalid night brightness." }
            require(profile.mediaGain.isFinite() && profile.mediaGain in 0f..1f) { "Invalid music level." }
            require(profile.navigationGain.isFinite() && profile.navigationGain in 0f..1f) { "Invalid navigation level." }
        }
        require(snapshot.keys.size <= 64 && snapshot.keys.keys.all { TeyesKeyRouter.isMappable(it) }) { "Unsupported steering-wheel key." }
        require(snapshot.longKeys.size <= 64 && snapshot.longKeys.keys.all { TeyesKeyRouter.isMappable(it) }) { "Unsupported long-press key." }
        listOf(snapshot.keyApps to snapshot.keys, snapshot.longKeyApps to snapshot.longKeys).forEach { (apps, actions) ->
            require(apps.size + actions.size <= 64 && apps.keys.all(TeyesKeyRouter::isMappable)) { "Unsupported app key." }
            require(apps.values.all(::validComponent) && apps.keys.none(actions::containsKey)) { "Invalid or conflicting app mapping." }
        }
        require(snapshot.shortcuts.values.all(::validComponent)) { "Invalid app shortcut." }
        require((snapshot.projection == null) == (snapshot.measurementUnit == null)) { "Incomplete presentation settings." }
    }

    fun encode(snapshot: TeyesConfigurationSnapshot): String {
        validate(snapshot)
        require(snapshot.projection != null && snapshot.measurementUnit != null) { "New backups require presentation settings." }
        return JSONObject().apply {
            put("format", "carlink-teyes-settings")
            put("schema", SCHEMA)
            put("projection", JSONObject().apply {
                put("focusControls", snapshot.projection.focusControls)
                put("vehicleHud", snapshot.projection.vehicleHud)
                put("climateNoticeMode", snapshot.projection.climateNoticeMode.name)
                put("returnWhenReady", snapshot.projection.returnWhenReady)
                put("controlSide", snapshot.projection.controlSide.name)
            })
            put("measurementUnit", snapshot.measurementUnit.name)
            put("profiles", JSONArray().apply {
                snapshot.profiles.sortedBy { it.slot }.forEach { profile ->
                    put(JSONObject().apply {
                        put("slot", profile.slot)
                        put("name", profile.name)
                        put("preferredPhone", profile.preferredPhone)
                        put("appearance", profile.appearance.name)
                        put("nightBrightness", profile.nightBrightness.toDouble())
                        put("mediaGain", profile.mediaGain.toDouble())
                        put("navigationGain", profile.navigationGain.toDouble())
                        put("resumeOnWake", profile.resumeOnWake)
                        put("recoverOverlays", profile.recoverOverlays)
                        put("compactOnLaunch", profile.compactOnLaunch)
                    })
                }
            })
            put("keys", JSONArray().apply {
                snapshot.keys.toSortedMap().forEach { (code, action) -> put(JSONObject().put("code", code).put("action", action.name)) }
            })
            put("longKeys", JSONArray().apply { snapshot.longKeys.toSortedMap().forEach { (code, action) -> put(JSONObject().put("code", code).put("action", action.name)) } })
            put("keyApps", appMappings(snapshot.keyApps))
            put("longKeyApps", appMappings(snapshot.longKeyApps))
            put("shortcuts", JSONObject().apply { snapshot.shortcuts.forEach { (kind, component) -> put(kind.name, component) } })
        }.toString(2).also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration is too large." } }
    }

    /** Never allocate an unbounded document from a content provider. Does not close the caller's stream. */
    fun read(input: InputStream): TeyesConfigurationSnapshot {
        val bytes = input.readBytesBounded()
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        return decode(text)
    }

    fun decode(text: String): TeyesConfigurationSnapshot {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Configuration exceeds 32 KB." }
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.isLenient = false
                checkJson(reader, 0)
                require(reader.peek() == JsonToken.END_DOCUMENT) { "Unexpected trailing content." }
            }
            val root = JSONObject(text)
            require(root.string("format") == "carlink-teyes-settings") { "Not a Cabin settings backup." }
            val schema = root.integer("schema")
            require(schema in 1..SCHEMA) { "Unsupported backup version." }
            root.exactFields(setOf("format", "schema", "profiles", "keys", "shortcuts") +
                (if (schema >= 2) setOf("projection", "measurementUnit") else emptySet()) +
                (if (schema >= 3) setOf("longKeys") else emptySet()) +
                (if (schema >= 4) setOf("keyApps", "longKeyApps") else emptySet()))
            val parsedProjection = if (schema >= 2) {
                val projection = root.get("projection") as? JSONObject ?: error("Projection settings must be an object.")
                projection.exactFields(setOf("focusControls", "vehicleHud", "climateNoticeMode", "returnWhenReady", "controlSide"))
                ProjectionPreferencesState(
                    focusControls = projection.boolean("focusControls"),
                    vehicleHud = projection.boolean("vehicleHud"),
                    climateNoticeMode = ClimateNoticeMode.entries.firstOrNull { it.name == projection.string("climateNoticeMode") } ?: error("Unknown climate notice mode."),
                    returnWhenReady = projection.boolean("returnWhenReady"),
                    controlSide = ProjectionControlSide.entries.firstOrNull { it.name == projection.string("controlSide") } ?: error("Unknown control side."),
                )
            } else null
            val parsedUnit = if (schema >= 2) {
                MeasurementUnit.entries.firstOrNull { it.name == root.string("measurementUnit") } ?: error("Unknown measurement unit.")
            } else null
            val profiles = root.get("profiles") as? JSONArray ?: error("Profiles must be an array.")
            require(profiles.length() == 3) { "Exactly three driver profiles are required." }
            val parsedProfiles = (0 until profiles.length()).map { index ->
                val profile = profiles.get(index) as? JSONObject ?: error("Invalid driver profile.")
                profile.exactFields(setOf("slot", "name", "preferredPhone", "appearance", "nightBrightness", "mediaGain", "navigationGain", "resumeOnWake", "recoverOverlays", "compactOnLaunch"))
                TeyesDriverProfile(
                    slot = profile.integer("slot"),
                    name = profile.string("name"),
                    preferredPhone = profile.string("preferredPhone"),
                    appearance = TeyesAppearance.entries.firstOrNull { it.name == profile.string("appearance") } ?: error("Unknown appearance."),
                    nightBrightness = profile.fraction("nightBrightness", 0.1, 1.0),
                    mediaGain = profile.fraction("mediaGain", 0.0, 1.0),
                    navigationGain = profile.fraction("navigationGain", 0.0, 1.0),
                    resumeOnWake = profile.boolean("resumeOnWake"),
                    recoverOverlays = profile.boolean("recoverOverlays"),
                    compactOnLaunch = profile.boolean("compactOnLaunch"),
                )
            }
            val keys = root.get("keys") as? JSONArray ?: error("Keys must be an array.")
            require(keys.length() <= 64) { "Too many key mappings." }
            val parsedKeys = mutableMapOf<Int, TeyesKeyAction>()
            repeat(keys.length()) { index ->
                val key = keys.get(index) as? JSONObject ?: error("Invalid key mapping.")
                key.exactFields(setOf("code", "action"))
                val code = key.integer("code")
                require(!parsedKeys.containsKey(code)) { "Duplicate key mapping." }
                parsedKeys[code] = TeyesKeyAction.entries.firstOrNull { it.name == key.string("action") } ?: error("Unknown key action.")
            }
            val parsedLongKeys = mutableMapOf<Int, TeyesKeyAction>()
            if (schema >= 3) {
                val longKeys = root.getJSONArray("longKeys")
                require(longKeys.length() <= 64)
                repeat(longKeys.length()) { index ->
                    val key = longKeys.getJSONObject(index)
                    key.exactFields(setOf("code", "action"))
                    val code = key.integer("code")
                    require(!parsedLongKeys.containsKey(code))
                    parsedLongKeys[code] = TeyesKeyAction.entries.firstOrNull { it.name == key.string("action") } ?: error("Unknown key action.")
                }
            }
            val shortcuts = root.get("shortcuts") as? JSONObject ?: error("Shortcuts must be an object.")
            val parsedShortcuts = shortcuts.keys().asSequence().associate { name ->
                val kind = TeyesShortcut.entries.firstOrNull { it.name == name } ?: error("Unknown shortcut kind.")
                kind to shortcuts.string(name)
            }
            return TeyesConfigurationSnapshot(parsedProfiles, parsedKeys.toMap(), parsedShortcuts, parsedProjection, parsedUnit, parsedLongKeys.toMap(),
                if (schema >= 4) readAppMappings(root.getJSONArray("keyApps")) else emptyMap(),
                if (schema >= 4) readAppMappings(root.getJSONArray("longKeyApps")) else emptyMap(),
            ).also(::validate)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid or unsupported Cabin backup: ${error.message.orEmpty().take(160)}", error)
        }
    }

    private fun appMappings(apps: Map<Int, String>) = JSONArray().apply {
        apps.toSortedMap().forEach { (code, component) -> put(JSONObject().put("code", code).put("component", component)) }
    }

    private fun readAppMappings(array: JSONArray): Map<Int, String> {
        require(array.length() <= 64)
        val result = mutableMapOf<Int, String>()
        repeat(array.length()) { index ->
            val entry = array.getJSONObject(index)
            entry.exactFields(setOf("code", "component"))
            val code = entry.integer("code")
            require(!result.containsKey(code)) { "Duplicate app key." }
            result[code] = entry.string("component")
        }
        return result
    }

    private fun InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count == -1) return output.toByteArray()
            require(count > 0) { "Could not read the backup." }
            output.write(buffer, 0, count)
            require(output.size() <= MAX_BYTES) { "Configuration exceeds 32 KB." }
        }
    }

    // Android JSONObject accepts comments, duplicate keys and other non-JSON syntax; reject those first.
    private fun checkJson(reader: JsonReader, depth: Int) {
        require(depth <= 6) { "Configuration is nested too deeply." }
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    require(names.size < 64 && names.add(name)) { "Too many or duplicate JSON fields." }
                    checkJson(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var count = 0
                while (reader.hasNext()) {
                    require(++count <= 64) { "Too many entries." }
                    checkJson(reader, depth + 1)
                }
                reader.endArray()
            }
            JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= 512) { "Value is too long." }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            else -> error("Unexpected JSON value.")
        }
    }

    private fun JSONObject.exactFields(expected: Set<String>) {
        require(keys().asSequence().toSet() == expected) { "Missing or unknown settings fields." }
    }

    private fun JSONObject.string(key: String): String = get(key) as? String ?: error("$key must be text.")

    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: error("$key must be true or false.")

    private fun JSONObject.integer(key: String): Int = get(key) as? Int ?: error("$key must be an integer.")

    private fun JSONObject.fraction(key: String, minimum: Double, maximum: Double): Float {
        val number = get(key) as? Number ?: error("$key must be a number.")
        val value = number.toDouble()
        require(value.isFinite() && value in minimum..maximum) { "$key is outside the allowed range." }
        return value.toFloat()
    }
}
