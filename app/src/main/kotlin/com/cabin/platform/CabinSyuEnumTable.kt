package com.cabin.platform

import org.json.JSONArray

/** Cabin's finite-state lookup tables. No opcodes, expressions, reflection or vendor resources. */
internal class CabinSyuEnumTable private constructor(private val entries: List<Entry>) : FytSyuDisplay {
    private data class Value(val first: Int, val last: Int, val text: String?, val checked: Boolean?)
    private data class Entry(val field: Int, val values: List<Value>)

    override fun read(raw: Map<Int, Int>): List<FytSyuReading> = entries.mapNotNull { entry ->
        val value = raw[entry.field] ?: return@mapNotNull null
        val state = entry.values.firstOrNull { value in it.first..it.last } ?: return@mapNotNull null
        FytSyuReading("vehicle", entry.field, setOf(entry.field), state.text, state.checked)
    }

    companion object {
        fun parse(array: JSONArray?, portuguese: Boolean): CabinSyuEnumTable {
            val entries = (0 until (array?.length() ?: 0)).map { index ->
                val row = array!!.getJSONObject(index)
                val field = row.getInt("field")
                require(field in 0..1199 && field != 1000)
                val values = row.getJSONArray("values")
                require(values.length() <= 259)
                val ranges = (0 until values.length()).map { n ->
                    val value = values.getJSONObject(n)
                    val first = value.getInt("min"); val last = value.getInt("max")
                    require(first >= -3 && last <= 255 && first <= last)
                    val text = when { portuguese && value.has("pt") -> value.getString("pt"); value.has("text") -> value.getString("text"); else -> null }
                    require(text == null || text.length <= 4096)
                    Value(first, last, text, if (value.has("checked")) value.getBoolean("checked") else null)
                }
                require(ranges.zipWithNext().all { (a, b) -> a.last < b.first })
                Entry(field, ranges)
            }
            require(entries.size <= 1199 && entries.map { it.field }.distinct().size == entries.size)
            return CabinSyuEnumTable(entries)
        }
    }
}
