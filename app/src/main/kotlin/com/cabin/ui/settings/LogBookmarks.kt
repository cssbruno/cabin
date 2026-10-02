package com.cabin.ui.settings

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class LogBookmark(val source: LogSource, val line: Int, val offset: Long) {
    val key get() = "${source.path}:$offset"
}
internal class LogBookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("cabin_log_bookmarks", Context.MODE_PRIVATE)
    fun list(): List<LogBookmark> = runCatching {
        val text = prefs.getString("items", "[]") ?: "[]"
        require(text.length <= 65536)
        val array = JSONArray(text)
        (0 until minOf(array.length(), 40)).map { i ->
            val item = array.getJSONObject(i)
            LogBookmark(LogSource(item.getString("path"), item.getLong("size"), item.getLong("modified"), identity = item.optString("identity").takeIf(String::isNotEmpty), boundaryHash = item.optString("boundaryHash").takeIf(String::isNotEmpty)), item.getInt("line"), item.getLong("offset"))
        }.filter { it.line > 0 && it.offset >= 0 && it.offset < it.source.size }
    }.getOrDefault(emptyList())
    fun toggle(row: WorkspaceRow) {
        val items = list().toMutableList()
        if (items.any { it.key == row.key }) items.removeAll { it.key == row.key }
        else { items += LogBookmark(row.source, row.line, row.offset); while (items.size > 40) items.removeAt(0) }
        save(items)
    }
    fun remove(key: String) = save(list().filterNot { it.key == key })
    private fun save(items: List<LogBookmark>) {
        prefs.edit().putString("items", JSONArray(items.map { item -> JSONObject()
            .put("path", item.source.path).put("size", item.source.size).put("modified", item.source.modified)
            .put("line", item.line).put("offset", item.offset).put("identity", item.source.identity).put("boundaryHash", item.source.boundaryHash) }).toString()).apply()
    }
}
