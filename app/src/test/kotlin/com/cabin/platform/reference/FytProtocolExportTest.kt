package com.cabin.platform

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit local development task. Research inputs never enter the production APK. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], manifest = org.robolectric.annotation.Config.NONE)
class FytProtocolExportTest {
    @Test fun exportReviewedProtocolFacts() {
        assumeTrue(System.getenv("CABIN_EXPORT_FYT_REGISTRY") == "1")
        val directory = File("../artifacts/joying-uis7862/extracted/applications/app")
        val service = File(directory, "190000000_com.syu.ms/190000000_com.syu.ms.apk")
        val client = File(directory, "190000000_com.syu.canbus/190000000_com.syu.canbus.apk")
        require(service.exists() && client.exists())
        val catalog = JSONObject(File("src/main/assets/syu/profile-catalog.json").readText()).getJSONObject("profiles")
        val profiles = catalog.keys().asSequence().map(String::toInt).sorted().toSet()
        val resourceFile = File(requireNotNull(System.getenv("CABIN_SYU_RESOURCE_DUMP")))
        val resources = mutableMapOf<Int, MutableMap<String, String>>()
        var resourceId = 0
        resourceFile.forEachLine { line ->
            if ("resource 0x" in line) resourceId = 0
            Regex("resource 0x([0-9a-f]+) string/").find(line)?.let { resourceId = it.groupValues[1].toInt(16) }
            Regex("^\\s+\\(([^)]*)\\) (\".*\")$").find(line)?.let { match ->
                val locale = match.groupValues[1]
                if (resourceId != 0 && locale in setOf("", "pt", "pt-rBR")) {
                    val text = try { JSONArray("[${match.groupValues[2]}]").getString(0) } catch (_: Exception) { return@let }
                    resources.getOrPut(resourceId) { mutableMapOf() }[if (locale.isEmpty()) "en" else "pt"] = text
                }
            }
        }
        val clients = FytSyuClientCatalog.resolver(listOf(client))
        val screens = FytSyuClientCatalog.resolver(listOf(client)) { id -> "@resource:$id" }
        val enumCache = mutableMapOf<String, List<JSONObject>>()
        val results = FytCodeDetector.detectMethods(FytCodeDetector.readMethods(listOf(service)), profiles, clients)
        val templates = mutableListOf<JSONObject>()
        val slots = linkedMapOf<String, Int>()
        val index = JSONObject()
        for ((id, protocol) in results.toSortedMap()) {
            val names = clients(id)
            val decoder = when {
                names.callback == "Lcom/syu/module/canbus/Callback_0298_XP1_2015SIYU_CRV;" -> "honda_0298"
                id == 286 -> "bagoo_audi"
                else -> ""
            }
            val row = JSONObject().put("protocol", protocol.receiver).put("callback", names.callback)
                .put("decoder", decoder)
                .put("fields", JSONObject(protocol.fields.toSortedMap().mapKeys { it.key.toString() }))
                .put("modules", JSONObject(protocol.moduleFields.toSortedMap().mapKeys { it.key.toString() }.mapValues { JSONArray(it.value.sorted()) }))
                .put("names", JSONObject(names.names.filterKeys { it in protocol.publishedFields }.toSortedMap().mapKeys { it.key.toString() }))
            val enums = (screens(id).display as? ReferenceSyuDisplay)?.enumFacts(enumCache).orEmpty().filter { it.getInt("field") in protocol.publishedFields }
            val translated = enums.map { original ->
                val fact = JSONObject(original.toString())
                val ranges = fact.getJSONArray("values")
                for (i in 0 until ranges.length()) {
                    val range = ranges.getJSONObject(i)
                    val token = range.optString("text")
                    if (token.startsWith("@resource:")) {
                        val labels = resources[token.removePrefix("@resource:").toInt()] ?: error("Missing stock resource $token")
                        range.put("text", labels["en"] ?: error("Missing default stock resource $token"))
                        labels["pt"]?.let { range.put("pt", it) }
                    }
                }
                fact
            }
            row.put("enums", JSONArray(translated))
            val serialized = row.toString()
            val slot = slots.getOrPut(serialized) { templates.add(row); templates.lastIndex }
            index.put(id.toString(), slot)
        }
        val output = JSONObject().put("schema", 1).put("family", "syu_2023_07")
            .put("referenceVersion", "2.23.0711.1001").put("profiles", index).put("templates", JSONArray(templates))
        File("src/main/assets/syu/protocols-2023.json").writeText(output.toString())
        println("Compiled protocol facts: ${profiles.size} profiles, ${templates.size} distinct definitions")
    }
}
