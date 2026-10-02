package com.cabin.ui

import org.json.JSONException
import org.json.JSONObject

internal fun healthReportSections(report: String): List<String> = try {
    JSONObject(report).keys().asSequence().toList().sorted()
} catch (_: JSONException) {
    emptyList()
}

/** Display filters never modify the frozen report that is copied or saved. */
internal fun healthReportSection(report: String, section: String?): String {
    if (section == null) return report
    return try {
        val root = JSONObject(report)
        if (!root.has(section)) report else JSONObject().put(section, root.get(section)).toString(2)
    } catch (_: JSONException) {
        report
    }
}

internal fun searchHealthReport(content: String, query: String): List<String> {
    val needle = query.trim()
    return content.lineSequence().filter { needle.isEmpty() || it.contains(needle, ignoreCase = true) }.toList()
}
