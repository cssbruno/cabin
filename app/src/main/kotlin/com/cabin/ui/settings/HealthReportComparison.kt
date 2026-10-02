package com.cabin.ui.settings

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReportDifference(val field: String, val before: String?, val after: String?) {
    val kind get() = when { before == null || after == null -> "missing"; before == after -> "unchanged"; else -> "changed" }
}
internal suspend fun readHealthReport(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    context.contentResolver.openInputStream(uri)!!.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 1_048_576)
            output.write(buffer, 0, count)
        }
        output.toString("UTF-8").also(::validatedHealthReport)
    }
}
internal fun validatedHealthReport(text: String): JSONObject {
    require(text.toByteArray().size <= 1_048_576)
    var depth = 0; var quoted = false; var escaped = false
    for (c in text) {
        if (escaped) { escaped = false; continue }
        if (quoted && c == '\\') { escaped = true; continue }
        if (c == '"') { quoted = !quoted; continue }
        if (!quoted) when(c) { '{', '[' -> { depth++; require(depth <= 24) }; '}', ']' -> depth-- }
        require(depth >= 0)
    }
    require(depth == 0 && !quoted)
    return JSONObject(text).also { require(it.optInt("schemaVersion") in 1..2 && it.has("projectionState") && it.has("vehicleHealth")) }
}
internal fun compareHealthReports(first: String, second: String): List<ReportDifference> {
    val a = validatedHealthReport(first); val b = validatedHealthReport(second)
    require(a.getInt("schemaVersion") == b.getInt("schemaVersion")) { "Incompatible schemas" }
    fun flatten(root: JSONObject): Map<String, String> {
        val result = sortedMapOf<String, String>()
        fun visit(value: Any?, path: String) {
            require(result.size < 8192)
            when (value) {
                is JSONObject -> {
                    if (value.length() == 0) result[path] = "{}"
                    value.keys().asSequence().sorted().forEach { visit(value.get(it), "$path/${it.replace("~", "~0").replace("/", "~1")}") }
                }
                is JSONArray -> {
                    if (value.length() == 0) result[path] = "[]"
                    for (i in 0 until value.length()) visit(value.get(i), "$path/$i")
                }
                else -> result[path] = value?.toString() ?: "null"
            }
        }
        visit(root, "")
        return result
    }
    val left = flatten(a); val right = flatten(b)
    return (left.keys + right.keys).sorted().map { ReportDifference(it, left[it], right[it]) }
}
