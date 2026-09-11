package com.cabin.platform

import android.content.Context
import org.json.JSONObject

/** Immutable protocol facts shipped inside the signed APK. No remote or user-supplied command plans. */
data class SyuTemperatureFormat(val scale: Double, val offset: Double, val unit: String, val minRaw: Int, val maxRaw: Int)

internal data class SyuAirFrame(val command: Int, val values: List<Int>)
internal data class SyuAirProfile(
    val id: Int,
    val name: String,
    val fields: Map<String, Int>,
    val commands: Map<String, List<SyuAirFrame>>,
    val low: Int?,
    val high: Int?,
    val unavailable: Int?,
    val temperatureFormats: Map<String, Map<Int, SyuTemperatureFormat>> = emptyMap(),
)

data class SyuAirState(
    val profileId: Int,
    val name: String,
    val readings: Map<String, Int>,
    val actions: Set<String>,
    val low: Int? = null,
    val high: Int? = null,
    val unavailable: Int? = null,
    val temperatureFormats: Map<String, Map<Int, SyuTemperatureFormat>> = emptyMap(),
) {
    fun temperatureText(field: String): String {
        val raw = readings[field] ?: return "—"
        if (raw == unavailable) return "—"
        if (raw == low) return "LOW"
        if (raw == high) return "HIGH"
        val formats = temperatureFormats[field] ?: return "—"
        val fixed = formats[0]?.takeIf { it == formats[1] }
        val format = readings["U_AIR_TEMP_UNIT"]?.let { formats[it] } ?: fixed ?: return "—"
        if (raw !in format.minRaw..format.maxRaw) return "—"
        val value = raw.toBigDecimal().multiply(format.scale.toBigDecimal()).add(format.offset.toBigDecimal())
        return value.stripTrailingZeros().toPlainString() + "°" + format.unit
    }

    fun canSend(action: String): Boolean {
        if (action !in actions || readings.isEmpty()) return false
        val field = syuFeedbackField(action)
        val value = readings[field] ?: return false
        if (!action.contains("TEMP") && value !in 0..254) return false
        if (action.contains("TEMP")) {
            if (value !in -3..4095) return false
            if (value == unavailable) return false
            if ((action.endsWith("ADD") || action.endsWith("UP")) && value == high) return false
            if ((action.endsWith("SUB") || action.endsWith("DOWN")) && value == low) return false
        }
        return true
    }
}

/** Explicit feedback association: unknown functions never become actionable by name guessing. */
internal fun syuFeedbackField(action: String): String? = when (action) {
    "C_AIR_TEMP_LEFT_ADD", "C_AIR_TEMP_LEFT_SUB" -> "U_AIR_TEMP_LEFT"
    "C_AIR_TEMP_RIGHT_ADD", "C_AIR_TEMP_RIGHT_SUB" -> "U_AIR_TEMP_RIGHT"
    "C_AIR_WIND_ADD", "C_AIR_WIND_SUB" -> "U_AIR_WIND_LEVEL_LEFT"
    "C_AIR_FRONT_DEFROST" -> "U_AIR_FRONT"
    "C_AIR_MAX_FRONT_DEFROST" -> "U_AIR_FRONTMAX"
    "C_AIR_REAR_DEFROST" -> "U_AIR_REAR"
    "C_AIR_LEFT_HEAT" -> "U_AIR_SEATHEAT_LEFT"
    "C_AIR_RIGHT_HEAT" -> "U_AIR_SEATHEAT_RIGHT"
    "C_AIR_LEFT_COLD" -> "U_AIR_SEATWIND_LEFT"
    "C_AIR_RIGHT_COLD" -> "U_AIR_SEATWIND_RIGHT"
    "C_AIR_MODE_BODY", "C_AIR_MODE_FOOT", "C_AIR_MODE_BODYFOOT", "C_AIR_MODE_UPFOOT", "C_AIR_MODE_UP",
    "C_AIR_MODE_CHANGER", "C_AIR_MODE_ADD", "C_AIR_MODE_SUB" -> "U_AIR_WIND_LEVEL_LEFT"
    "C_REAR_TEMP_UP", "C_REAR_TEMP_DOWN" -> "U_AIR_BACK_TEMP"
    "C_REAR_WIND_UP", "C_REAR_WIND_DOWN", "C_REAR_MODE", "C_REAR_MODE_BODY", "C_REAR_MODE_FOOT", "C_REAR_MODE_BODY_FOOT" -> "U_AIR_BACK_BLOW_WIND"
    "C_REAR_OFF" -> "U_AIR_BACK_POWER"
    "C_REAR_AUTO" -> "U_AIR_BACK_BLOW_AUTO"
    "C_REAR_LOCK" -> "U_AIR_REAR_LOCK"
    "C_REAR_CTRL" -> "U_AIR_REAR_CTRL"
    "C_REAR_HEAT" -> "U_AIR_REAR_HEAT"
    "C_REAR_COOL" -> "U_AIR_REAR_COOL"
    "C_CLEAN" -> "U_AIR_CLEAN"
    "C_CLEAN_AIR" -> "U_AIR_CLEAN_AIR"
    "C_FAST" -> "U_AIR_FAST"
    "C_NORMAL" -> "U_AIR_NORMAL"
    "C_SOFT" -> "U_AIR_SOFT"
    "C_AIR_POWER_FRONT" -> "U_AIR_FRONT_POWER"
    "C_AIR_MODE_BODY_RIGHT", "C_AIR_MODE_FOOT_RIGHT", "C_AIR_MODE_UP_RIGHT", "C_AIR_MODE_CHANGER_RIGHT" -> "U_AIR_WIND_LEVEL_LEFT"
    "C_AIR_COOL", "C_AIR_ECO", "C_AIR_FOREST", "C_AIR_HEAT", "C_AIR_REARVIEW_HOT", "C_AIR_REST",
    "C_AIR_POWER", "C_AIR_AC", "C_AIR_AUTO", "C_AIR_AUTO_RIGHT", "C_AIR_DUAL", "C_AIR_SYNC",
    "C_AIR_CYCLE", "C_AIR_AC_MAX", "C_AIR_AQS", "C_AIR_SWING", "C_AIR_ZONE", "C_AIR_PTC",
    "C_AIR_STEER", "C_AIR_NANOE", "C_AIR_ION", "C_AIR_BLOWTOP", "C_AIR_FRONT_HOT" -> action.replaceFirst("C_", "U_")
    else -> null
}

internal class SyuAirRegistry private constructor(val profiles: Map<Int, SyuAirProfile>) {
    companion object {
        fun load(context: Context): SyuAirRegistry = try {
            val bytes = context.assets.open("syu/air-profiles.json").use { it.readBytes() }
            require(bytes.size <= 4 * 1024 * 1024)
            parse(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) { SyuAirRegistry(emptyMap()) }

        fun parse(text: String): SyuAirRegistry {
            require(text.length <= 4 * 1024 * 1024)
            val root = JSONObject(text)
            require(root.getInt("schema") == 1)
            val rows = root.getJSONArray("profiles")
            require(rows.length() <= 5000)
            val profiles = linkedMapOf<Int, SyuAirProfile>()
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val id = row.getInt("id")
                require(id > 0 && id !in profiles)
                val name = row.getString("name")
                require(name.length in 1..160 && name.all { it.isLetterOrDigit() || it in "_- ." })
                val fieldsObject = row.getJSONObject("fields")
                require(fieldsObject.length() <= 200)
                val fields = fieldsObject.keys().asSequence().associateWith { key ->
                    require(key.matches(Regex("U_[A-Z0-9_]{1,64}")))
                    fieldsObject.getInt(key).also { require(it in 0..254) }
                }
                val commandsObject = row.getJSONObject("commands")
                require(commandsObject.length() <= 200)
                val commands = commandsObject.keys().asSequence().associateWith { key ->
                    require(key.matches(Regex("C_[A-Z0-9_]{1,64}")))
                    val frames = commandsObject.getJSONArray(key)
                    require(frames.length() in 1..4)
                    List(frames.length()) { frameIndex ->
                        val frame = frames.getJSONArray(frameIndex)
                        require(frame.length() == 2)
                        val command = frame.getInt(0).also { require(it in 0..255) }
                        val values = frame.getJSONArray(1)
                        require(values.length() in 1..8)
                        SyuAirFrame(command, List(values.length()) { values.getInt(it).also { value -> require(value in 0..255) } })
                    }
                }
                val limits = row.optJSONObject("limits")
                val formatObject = if (root.optBoolean("temperatureFormatsVerified")) row.optJSONObject("temperatureFormats") else null
                val formats = formatObject?.keys()?.asSequence()?.associateWith { field ->
                    require(field in setOf("U_AIR_TEMP_LEFT", "U_AIR_TEMP_RIGHT"))
                    val units = formatObject.getJSONObject(field)
                    units.keys().asSequence().associate { unitKey ->
                        require(unitKey in setOf("0", "1"))
                        val model = units.getJSONObject(unitKey)
                        val scale = model.getDouble("scale").also { require(it in setOf(0.1, 0.5, 1.0, 2.0)) }
                        val offset = model.getDouble("offset").also { require(it.isFinite() && it in -1000.0..1000.0) }
                        val unit = model.getString("unit").also { require(it == "C" || it == "F") }
                        val min = model.getInt("minRaw").also { require(it == 0) }
                        val max = model.getInt("maxRaw").also { require(it == 1023) }
                        unitKey.toInt() to SyuTemperatureFormat(scale, offset, unit, min, max)
                    }
                }.orEmpty()
                profiles[id] = SyuAirProfile(id, name, fields, commands,
                    limits?.optInt("TEMPERATURE_LOW"), limits?.optInt("TEMPERATURE_HIGHT"), limits?.optInt("TEMPERATURE_NONE"), formats)
            }
            return SyuAirRegistry(profiles.toMap())
        }
    }
}
