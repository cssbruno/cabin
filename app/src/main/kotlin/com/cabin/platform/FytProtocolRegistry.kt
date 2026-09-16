package com.cabin.platform

import android.content.Context
import org.json.JSONObject

/** Versioned protocol facts compiled into Cabin. Contains field IDs and names, never vendor instructions. */
internal class FytProtocolRegistry private constructor(
    private val templates: List<JSONObject>,
    private val profiles: Map<Int, Int>,
    private val portuguese: Boolean,
) {
    fun profile(id: Int): FytDetectedProfile {
        val template = profiles[id]?.let(templates::get)
            ?: return FytDetectedProfile(id, emptyMap(), "profile_not_supported")
        fun ints(objectValue: JSONObject): Map<Int, Int> = objectValue.keys().asSequence().associate { it.toInt() to objectValue.getInt(it) }
        val fields = ints(template.getJSONObject("fields"))
        val modules = template.getJSONObject("modules").let { obj -> obj.keys().asSequence().associate { key ->
            val array = obj.getJSONArray(key)
            key.toInt() to (0 until array.length()).map { array.getInt(it) }.toSet()
        } }
        val names = template.getJSONObject("names").let { obj -> obj.keys().asSequence().associate { key ->
            val array = obj.getJSONArray(key)
            key.toInt() to (0 until array.length()).map(array::getString)
        } }
        val decoder = CabinHondaLegacy.dialect(id, template.getString("callback"))
            ?: CabinHondaFactoryMedia.dialect(id, template.getString("callback"))
            ?: CabinHondaEarlyTrip.dialect(id, template.getString("callback"))
            ?: CabinHondaAccordXbs.dialect(id, template.getString("callback"))
            ?: CabinHondaSpecialized.dialect(id, template.getString("callback"))
            ?: CabinHondaAccordWc.dialect(id, template.getString("callback"))
            ?: CabinHondaAccord.dialect(id, template.getString("callback"))
            ?: CabinGolfSettings.dialect(id, template.getString("callback"))
            ?: when (template.getString("callback")) {
                CabinGmWcReadings.CALLBACK -> "gm_wc_0036"
                CabinFordTires.CALLBACK -> "ford_0334"
                CabinHondaTrip.WC_CALLBACK -> "honda_wc_0321"
                else -> template.optString("decoder")
            }
        return FytDetectedProfile(id, fields, if (modules.isEmpty()) "no_packet_reader" else "registered_protocol",
            publishedFields = modules[7].orEmpty(), moduleFields = modules, receiver = template.getString("protocol"),
            syuClient = FytSyuClientFields(callback = template.getString("callback"), names = names,
                display = CabinSyuDecoder(id, decoder, portuguese, CabinSyuEnumTable.parse(template.optJSONArray("enums"), portuguese))))
    }

    companion object {
        // Protocol family observed in the supplied July 2023 firmware and the user's 0718 build.
        // CAN profile alone never authorizes applying this dialect to a different firmware family.
        fun supports(version: String) = Regex("2\\.23\\.07[0-9]{2}\\.[0-9]{4}").matches(version)

        fun load(context: Context): FytProtocolRegistry = context.assets.open("syu/protocols-2023.json").use {
            val bytes = it.readBytes()
            require(bytes.size <= 8 * 1024 * 1024)
            parse(bytes.toString(Charsets.UTF_8), context.resources.configuration.locales[0].language == "pt")
        }

        fun parse(text: String, portuguese: Boolean = false): FytProtocolRegistry {
            val root = JSONObject(text)
            require(root.getInt("schema") == 1 && root.getString("family") == "syu_2023_07")
            val definitions = root.getJSONArray("templates")
            require(definitions.length() in 1..4096)
            val templates = (0 until definitions.length()).map(definitions::getJSONObject)
            val index = root.getJSONObject("profiles")
            require(index.length() in 1..10_000)
            val profiles = index.keys().asSequence().associate { key ->
                val id = key.toInt(); val slot = index.getInt(key)
                require(id > 0 && slot in templates.indices)
                id to slot
            }
            return FytProtocolRegistry(templates, profiles, portuguese)
        }
    }
}
