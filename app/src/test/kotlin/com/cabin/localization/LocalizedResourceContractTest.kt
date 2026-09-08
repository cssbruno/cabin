package com.cabin.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** Formatting errors can crash only in one locale; validate every shipped translation. */
class LocalizedResourceContractTest {
    @Test
    fun `all six languages cover every app message and preserve format arguments`() {
        val resourceRoot = sequenceOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        val english = readMessages(File(resourceRoot, "values"))
        assertTrue("Expected the interface, setup, units and diagnostic translations", english.size > 500)
        assertEquals(setOf("en", "pt", "es", "fr", "de", "it"), AppLanguage.names.keys)
        val locales = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(resourceRoot, "xml/locales_config.xml")).getElementsByTagName("locale")
        assertEquals(AppLanguage.names.keys, (0 until locales.length).map {
            (locales.item(it) as Element).getAttribute("android:name")
        }.toSet())
        AppLanguage.names.keys.filter { it != "en" }.forEach { language ->
            val translatedMessages = readMessages(File(resourceRoot, "values-$language"))
            assertEquals(
                "Every translatable app message needs a $language translation",
                english.keys.map { it.substringBefore('/') }.toSet(),
                translatedMessages.keys.map { it.substringBefore('/') }.toSet(),
            )
            assertTrue("$language must cover every English quantity", translatedMessages.keys.containsAll(english.keys))
            translatedMessages.forEach { (key, translated) ->
                // Some languages also need “many”; arguments match the base “other” form.
                val value = english[key] ?: english.getValue(key.substringBefore('/') + "/other")
                assertTrue("Empty $language translation for $key", translated.isNotBlank())
                assertEquals("Format arguments changed in $language/$key", placeholders(value), placeholders(translated))
                // Exercise Java/Android formatter syntax, including literal percent signs.
                val arguments: Map<Int, Any> = placeholders(value).map { (position, conversion) ->
                    position to when (conversion) {
                        "d" -> 12
                        "f" -> 1.5
                        else -> "example"
                    }
                }.toMap()
                if (arguments.isNotEmpty()) {
                    val ordered: Array<Any> = (1..arguments.keys.max()).map { arguments[it] ?: "unused" }.toTypedArray()
                    String.format(Locale.US, value, *ordered)
                    String.format(Locale.forLanguageTag(language), translated, *ordered)
                }
            }
        }
    }

    private fun placeholders(value: String): List<Pair<Int, String>> =
        Regex("%(\\d+)\\$[-+0-9.]*([sdf])").findAll(value).map { it.groupValues[1].toInt() to it.groupValues[2] }.sortedBy { it.first }.toList()

    private fun readMessages(directory: File): Map<String, String> = buildMap {
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        directory.listFiles().orEmpty().filter { it.extension == "xml" }.forEach { file ->
            val nodes = parser.parse(file).documentElement.childNodes
            for (index in 0 until nodes.length) {
                val node = nodes.item(index) as? Element ?: continue
                if (node.getAttribute("translatable") == "false") continue
                val name = node.getAttribute("name")
                when (node.tagName) {
                    "string" -> put(name, node.textContent)
                    "plurals" -> {
                        val items = node.getElementsByTagName("item")
                        for (itemIndex in 0 until items.length) {
                            val item = items.item(itemIndex) as Element
                            put("$name/${item.getAttribute("quantity")}", item.textContent)
                        }
                    }
                }
            }
        }
    }
}
