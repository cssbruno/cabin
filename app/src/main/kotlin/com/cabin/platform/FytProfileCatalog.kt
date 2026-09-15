package com.cabin.platform

import android.content.Context
import org.json.JSONObject

/** Names are reference metadata, never permission to decode fields or send commands. */
internal object FytProfileCatalog {
    fun load(context: Context): Map<Int, String> = try {
        val bytes = context.assets.open("syu/profile-catalog.json").use { it.readBytes() }
        require(bytes.size <= 2 * 1024 * 1024)
        parse(bytes.toString(Charsets.UTF_8))
    } catch (_: Exception) { emptyMap() }

    internal fun parse(text: String): Map<Int, String> {
        val root = JSONObject(text)
        require(root.getInt("schema") == 1)
        val profiles = root.getJSONObject("profiles")
        require(profiles.length() <= 10_000)
        return profiles.keys().asSequence().associate { key ->
            val id = key.toInt()
            require(id > 0)
            val label = profiles.getJSONArray(key).getString(0)
            require(label.length in 1..160 && label.all { it.isLetterOrDigit() || it == '_' })
            id to label.removePrefix("CAR_").replace('_', ' ')
        }
    }
}
