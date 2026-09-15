package com.cabin.platform

import org.jf.dexlib2.iface.Method

/** Stock 0298 read formats, tied to both the installed client's named fields and service expressions. */
internal object FytStockMetrics {
    private data class Spec(val name: String, val signatures: Set<String>)
    private fun plain(value: String) = setOf("$value when ")
    private fun bit(byte: Int, shift: Int) = if (shift == 0) "and(1,byte[$byte])" else "and(1,shr(byte[$byte],$shift))"
    private fun temperature(byte: Int, extra: String? = null): Set<String> {
        fun signature(value: String, vararg conditions: String) = "$value when " +
            (conditions.toList() + listOfNotNull(extra)).sorted().joinToString(" && ")
        return setOf(
            signature("-3", "eq(byte[$byte],-1)"),
            signature("-2", "!eq(byte[$byte],-1)", "eq(byte[$byte],0)"),
            signature("and(255,byte[$byte])", "!eq(byte[$byte],-1)", "!eq(byte[$byte],0)"),
        )
    }
    private const val reversed = "ne(call:Landroid/os/SystemProperties;->getBoolean(Ljava/lang/String;Z)Z(string:persist.fyt.reversetemp, 0),0)"
    private val climate = mapOf(
        25 to Spec("U_AIR_TEMP_LEFT", temperature(4, "!$reversed") + temperature(5, reversed)),
        31 to Spec("U_AIR_TEMP_RIGHT", temperature(5, "!$reversed") + temperature(4, reversed)),
        52 to Spec("U_AIR_BACK_TEMP", temperature(8)),
        53 to Spec("U_AIR_BACK_UP", plain(bit(9, 7))),
        54 to Spec("U_AIR_BACK_BODY", plain(bit(9, 6))),
        55 to Spec("U_AIR_BACK_FOOT", plain(bit(9, 5))),
        56 to Spec("U_AIR_BACK_WIND", plain("and(15,byte[9])")),
        57 to Spec("U_AIR_BACK_AUTO", plain(bit(10, 7))),
        91 to Spec("U_AIR_BLOW_UP_RIGHT", plain(bit(7, 2))),
        92 to Spec("U_AIR_BLOW_BODY_RIGHT", plain(bit(7, 1))),
        93 to Spec("U_AIR_BLOW_FOOT_RIGHT", plain(bit(7, 0))),
        94 to Spec("U_AIR_SEAT_COLD_LEFT", plain("and(3,shr(byte[11],6))")),
        95 to Spec("U_AIR_SEAT_HEAT_LEFT", plain("and(3,shr(byte[11],4))")),
        96 to Spec("U_AIR_SEAT_COLD_RIGHT", plain("and(3,shr(byte[11],2))")),
        97 to Spec("U_AIR_SEAT_HEAT_RIGHT", plain("and(3,byte[11])")),
    )
    private val maintenance = mapOf(
        179 to Spec("U_CARINFO_MAINTANCE_OIL_SERVICE_LIFE_UNIT", plain(bit(8, 1))),
        180 to Spec("U_CARINFO_MAINTANCE_OIL_SERVICE_LIFE_PN_UNIT", plain(bit(8, 0))),
        181 to Spec("U_CARINFO_MAINTANCE_OIL_SERVICE_LIFE", plain("add(byte[10],mul(256,byte[9]))")),
    )

    fun enrich(base: FytDetectedProfile, receiver: Method, client: FytSyuClientFields): FytDetectedProfile {
        if (receiver.definingClass != "Lmodule/canbus/v;" ||
            client.callback != "Lcom/syu/module/canbus/Callback_0298_XP1_2015SIYU_CRV;") return base
        val mapped = base.fields.toMutableMap()
        fun match(packet: Int, specs: Map<Int, Spec>): Map<Int, Int> = try {
            val observed = FytBytecodeAnalysis.signatures(FytBytecodeAnalysis.analyze(receiver, base.profile, packet).publications)
            specs.mapNotNull { (canonical, spec) ->
                val id = client.names.filterValues { spec.name in it }.keys.singleOrNull()
                if (id != null && equivalent(observed[id].orEmpty(), spec.signatures)) canonical to id else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
        val climateMatches = match(0x21, climate)
        // Units must themselves have verified feedback; never silently assume Celsius.
        mapped.putAll(climateMatches.filterKeys { it !in setOf(25, 31, 52) || 33 in mapped })
        val service = match(0x32, maintenance)
        if (service.size == maintenance.size) mapped.putAll(service)
        // Multiple canonical meanings must not alias the same live field.
        val duplicates = mapped.values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return base.copy(fields = mapped.filterValues { it !in duplicates })
    }

    /** Compare Boolean path coverage, independent of the analyzer's branch merge order. */
    internal fun equivalent(observed: Set<String>, expected: Set<String>): Boolean {
        fun parse(rows: Set<String>): Map<String, List<Set<String>>> = rows.groupBy { it.substringBefore(" when ") }
            .mapValues { (_, values) -> values.map { it.substringAfter(" when ").split(" && ").filter(String::isNotEmpty).toSet() } }
        val actual = parse(observed); val wanted = parse(expected)
        if (actual.keys != wanted.keys) return false
        return actual.all { (expression, terms) ->
            val required = wanted.getValue(expression)
            val variables = (terms + required).flatten().map { it.removePrefix("!") }.distinct().sorted()
            if (variables.size > 10) return@all false
            fun trueAt(rows: List<Set<String>>, bits: Int) = rows.any { row -> row.all { literal ->
                val index = variables.indexOf(literal.removePrefix("!"))
                val positive = bits and (1 shl index) != 0
                if (literal.startsWith("!")) !positive else positive
            } }
            (0 until (1 shl variables.size)).all { trueAt(terms, it) == trueAt(required, it) }
        }
    }
}
