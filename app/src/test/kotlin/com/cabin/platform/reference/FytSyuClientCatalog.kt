package com.cabin.platform

import java.io.File
import java.util.zip.ZipFile
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.value.IntEncodedValue

internal class ReferenceSyuDisplay(
    private val profile: Int,
    private val programs: List<Pair<String, FytSyuReadProgram>>,
    private val forwarded: Set<Int>? = null,
    private val string: (Int) -> String?,
) : FytSyuDisplay {
    /** Exhaustive finite-state tables for offline export, never run by Cabin. */
    fun enumFacts(cache: MutableMap<String, List<org.json.JSONObject>>): List<org.json.JSONObject> {
        val facts = programs.flatMap { (_, program) ->
            val key = program.referenceKey + if (program.profileDependent) ":$profile" else ""
            cache.getOrPut(key) {
                val all = (0..1199).associateWith { 0 }
                val probe = sequenceOf(0, 1, 2, 3).mapNotNull { value -> program.read(profile, all.mapValues { value }, string) }
                    .firstOrNull { it.fields.size == 1 }
                val field = probe?.fields?.singleOrNull()
                if (field == null) emptyList() else {
                    val results = (-3..255).mapNotNull { raw ->
                        program.read(profile, mapOf(field to raw), string)?.takeIf { it.fields == setOf(field) }?.output?.map { raw to it }
                    }.flatten().groupBy { it.second.view }
                    results.values.mapNotNull { rows ->
                        if (rows.map { it.second.text to it.second.checked }.distinct().size !in 1..24) null else {
                            val ranges = org.json.JSONArray()
                            var start = 0
                            while (start < rows.size) {
                                val first = rows[start]
                                var end = start
                                while (end + 1 < rows.size && rows[end + 1].first == rows[end].first + 1 && rows[end + 1].second == first.second) end++
                                val range = org.json.JSONObject().put("min", first.first).put("max", rows[end].first)
                                first.second.text?.let { range.put("text", it) }
                                first.second.checked?.let { range.put("checked", it) }
                                ranges.put(range)
                                start = end + 1
                            }
                            org.json.JSONObject().put("field", field).put("values", ranges)
                        }
                    }
                }
            }.filter { forwarded == null || it.getInt("field") in forwarded }
        }
        return facts.distinctBy { it.toString() }.groupBy { it.getInt("field") }.values.mapNotNull { it.singleOrNull() }
    }

    private var previous: Map<Int, Int>? = null
    private var previousRows: List<FytSyuReading> = emptyList()
    private val calculated = mutableMapOf<Int, FytSyuReadProgram.Result>()

    @Synchronized override fun read(raw: Map<Int, Int>): List<FytSyuReading> {
        val values = if (forwarded == null) raw else raw.filterKeys { it in forwarded }
        if (values == previous) return previousRows
        val rows = programs.flatMapIndexed { index, (screen, program) ->
            val cached = calculated[index]
            val result = if (cached != null && cached.fields.all { values[it] == previous?.get(it) }) cached
                else program.read(profile, values, string)
            if (result == null) calculated.remove(index) else calculated[index] = result
            result?.output.orEmpty().map { FytSyuReading(screen, it.view, result!!.fields, it.text, it.checked) }
        }.groupBy { it.screen to it.viewId }.values.mapNotNull { alternatives ->
            // Never select an arbitrary formatter when two methods disagree about a shared view.
            alternatives.distinctBy { it.text to it.checked }.singleOrNull()?.copy(fields = alternatives.flatMap { it.fields }.toSet())
        }
        previous = values.toMap()
        previousRows = rows
        return rows
    }
}

internal object FytSyuClientCatalog {
    fun resolver(apks: List<File>, string: ((Int) -> String?)? = null): (Int) -> FytSyuClientFields {
        val classes = linkedMapOf<String, ClassDef>()
        var totalBytes = 0L
        try {
            apks.forEach { apk -> ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name.matches(Regex("classes([2-9][0-9]*|1[0-9]+)?\\.dex")) }.toList()
                require(entries.size <= 32)
                entries.forEach { entry ->
                    require(entry.size in 1..64L * 1024 * 1024)
                    totalBytes += entry.size
                    require(totalBytes <= 128L * 1024 * 1024)
                    val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it) }
                    dex.classes.filter { it.type.startsWith("Lcom/syu/module/canbus/") || it.type.startsWith("Lcom/syu/carinfo/") || it.type == "Lcom/syu/canbus/ActivityLauncher;" }.forEach {
                        require(it.type !in classes)
                        classes[it.type] = it
                    }
                }
            } }
        } catch (_: Exception) { return { FytSyuClientFields() } }
        val dispatcher = classes["Lcom/syu/module/canbus/HandlerCanbus;"]?.methods?.singleOrNull {
            it.name == "getCallbackCanbusById" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
        } ?: return { FytSyuClientFields() }
        val cache = object : LinkedHashMap<Int, FytSyuClientFields>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FytSyuClientFields>?) = size > 16
        }
        return { profile -> synchronized(cache) { cache.getOrPut(profile) { inspect(dispatcher, classes, profile, string) } } }
    }

    private fun inspect(dispatcher: Method, classes: Map<String, ClassDef>, profile: Int, string: ((Int) -> String?)?): FytSyuClientFields = try {
        val result = FytBytecodeAnalysis.analyze(dispatcher, profile).returns.singleOrNull()
        val type = result?.takeIf { it.startsWith("object:") }?.removePrefix("object:")
        require(type != null && type.startsWith("Lcom/syu/module/canbus/Callback_"))
        val names = linkedMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        var current: String? = type
        while (current != null && seen.add(current)) {
            require(seen.size <= 32)
            val cls = classes[current] ?: break
            cls.fields.forEach { field ->
                val value = (field.initialValue as? IntEncodedValue)?.value
                if (value != null && value in 0..1199 && field.name.matches(Regex("U_[A-Z0-9_]{1,100}")) &&
                    !field.name.endsWith("_BEGIN") && !field.name.endsWith("_END") && field.name != "U_CNT_MAX") {
                    names.putIfAbsent(field.name, value)
                }
            }
            current = cls.superclass
        }
        val update = classes[type]?.methods?.singleOrNull { it.name == "update" && it.parameterTypes.map { it.toString() } == listOf("I", "[I", "[F", "[Ljava/lang/String;") }
        val handlers = classes["Lcom/syu/module/canbus/HandlerCanbus;"]?.methods?.filter(FytSyuNotifyRoutes::copiesScalar)
            ?.map { org.jf.dexlib2.util.ReferenceUtil.getMethodDescriptor(it) }?.toSet().orEmpty()
        val forwarded = if (string != null && update != null) FytSyuNotifyRoutes.forwarded(update, profile, names.values.toSet(), handlers) else emptySet()
        val rootMethod = if (string == null) null else classes["Lcom/syu/canbus/ActivityLauncher;"]?.methods?.singleOrNull { it.name == "launchCanbus" && it.parameterTypes.isEmpty() }
        val roots = rootMethod?.let { FytSyuScreens.roots(it, profile) }.orEmpty()
        val screens = FytSyuScreens.reachable(roots, classes, profile)
        val programs = screens.flatMap { screen -> classes[screen]?.methods?.toList().orEmpty().filter(FytSyuReadProgram::candidate)
            .map { screen to FytSyuReadProgram(it) } }.toMutableList()
        val helperRoutes = screens.flatMap { screen -> classes[screen]?.methods?.toList().orEmpty().flatMap { method ->
            FytSyuNotifyRoutes.helpers(method, profile, names.values.toSet()).entries.map { it.key to it.value }
        } }.groupBy({ it.first }, { it.second }).mapValues { (_, fields) -> fields.flatten().toSet() }
        helperRoutes.forEach { (descriptor, fields) ->
            // A helper reused for different fields cannot be assigned a single source.
            val id = fields.singleOrNull() ?: return@forEach
            val screen = descriptor.substringBefore("->")
            val method = classes[screen]?.methods?.singleOrNull { org.jf.dexlib2.util.ReferenceUtil.getMethodDescriptor(it) == descriptor }
            if (method?.implementation != null && method.returnType == "V" && method.accessFlags and 8 == 0) {
                programs.add(screen to FytSyuReadProgram(method, id))
            }
        }
        FytSyuClientFields(type, names.entries.groupBy { it.value }.mapValues { (_, entries) -> entries.map { it.key }.sorted() },
            screens, if (programs.size <= 512) ReferenceSyuDisplay(profile, programs, forwarded, string ?: { null }) else null)
    } catch (_: Exception) { FytSyuClientFields() }
}
