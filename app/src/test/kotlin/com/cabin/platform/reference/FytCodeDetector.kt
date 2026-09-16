package com.cabin.platform

import java.io.File
import java.util.zip.ZipFile
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.util.ReferenceUtil

/** Derive per-profile read mappings from decoded data flow, independent of APK version/signature. */
internal object FytCodeDetector {

    fun resolver(apks: List<File>, clientResolver: (Int) -> FytSyuClientFields = { FytSyuClientFields() }): (Int) -> FytDetectedProfile {
        val methods = try { readMethods(apks) } catch (_: Exception) {
            return { FytDetectedProfile(it, emptyMap(), "unreadable_dex") }
        }
        val cache = object : LinkedHashMap<Int, FytDetectedProfile>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, FytDetectedProfile>?) = size > 16
        }
        return { profile -> synchronized(cache) {
            cache.getOrPut(profile) { detectMethods(methods, setOf(profile), clientResolver).getValue(profile) }
        } }
    }

    fun detect(apks: List<File>): Map<Int, FytDetectedProfile> = try {
        detectMethods(readMethods(apks))
    } catch (_: Exception) {
        FytFieldSemantics.profiles.associateWith { FytDetectedProfile(it, emptyMap(), "unreadable_dex") }
    }

    internal fun detectMethods(methods: Map<String, Method>, profiles: Set<Int> = FytFieldSemantics.profiles, clientResolver: (Int) -> FytSyuClientFields = { FytSyuClientFields() }): Map<Int, FytDetectedProfile> {
        val publisher = methods["Lf0/wp;->g0(II)V"]
        if (publisher == null || !isFieldPublisher(publisher))
            return profiles.associateWith { FytDetectedProfile(it, emptyMap(), "publisher_not_recognized") }
        val inventory = FytPublishedFields.Inventory(methods)
        return profiles.associateWith { profile ->
            try {
                val dispatcher = methods["Lf0/wp;->V(I)Lf0/rp;"] ?: error("dispatcher_missing")
                val selected = FytBytecodeAnalysis.analyze(dispatcher, profile).returns.singleOrNull()
                    ?: error("ambiguous_dispatch")
                val type = selected.removePrefix("object:")
                val receiver = methods["$type->M2([BII)V"] ?: error("receiver_missing")
                val moduleFields = inventory.discover(receiver, type)
                val published = moduleFields[7].orEmpty()
                if (receiver.definingClass != "Lmodule/canbus/v;") {
                    FytDetectedProfile(profile, emptyMap(), if (moduleFields.isEmpty()) {
                        if (receiver.implementation!!.instructions.all { it.opcode.name in setOf("return-void", "nop", "aget-byte") }) "no_packet_reader" else "no_static_fields"
                    } else "raw_fields_only", publishedFields = published, receiver = type, moduleFields = moduleFields)
                } else {
                    val semantic = try {
                        val packets = FytFieldSemantics.expected.keys.associateWith { packet ->
                            FytBytecodeAnalysis.signatures(FytBytecodeAnalysis.analyze(receiver, profile, packet).publications)
                        }
                        FytFieldSemantics.match(profile, packets)
                    } catch (_: Exception) {
                        // Unverified units/packet semantics must not discard a valid raw inventory.
                        FytDetectedProfile(profile, emptyMap(), "raw_fields_only")
                    }
                    FytStockMetrics.enrich(semantic, receiver, clientResolver(profile))
                        .copy(publishedFields = published, receiver = type, moduleFields = moduleFields)
                }
            } catch (failure: Exception) {
                val reason = failure.message.orEmpty().takeIf { it.matches(Regex("[a-z_]+(:[a-z0-9/\\-]+)?")) } ?: "analysis_failed"
                FytDetectedProfile(profile, emptyMap(), reason)
            }
        }
    }

    fun layout(apks: List<File>): TeyesVehicleDataLayout =
        if (detect(apks).values.any { it.fields.isNotEmpty() }) TeyesVehicleDataLayout.JOYING_2023 else TeyesVehicleDataLayout.UNKNOWN

    internal fun readMethods(apks: List<File>): Map<String, Method> {
        val found = mutableMapOf<String, Method>()
        val parents = mutableMapOf<String, String?>()
        var totalBytes = 0L
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name.matches(Regex("classes([2-9][0-9]*|1[0-9]+)?\\.dex")) }.toList()
                require(entries.size <= 32)
                entries.forEach { entry ->
                    require(entry.size in 1..64L * 1024 * 1024)
                    totalBytes += entry.size
                    require(totalBytes <= 128L * 1024 * 1024)
                    val dex = zip.getInputStream(entry).buffered().use { DexBackedDexFile.fromInputStream(Opcodes.getDefault(), it) }
                    dex.classes.filter { cls -> cls.type.startsWith("Lf0/") || cls.type.startsWith("Lmodule/canbus/") || cls.type.startsWith("Lg0/") || cls.type.startsWith("Lp0/") }.forEach { cls ->
                        parents[cls.type] = cls.superclass
                        cls.methods.forEach { method ->
                            val key = ReferenceUtil.getMethodDescriptor(method)
                            if (method.implementation != null) {
                                require(key !in found) { "Duplicate vendor method" }
                                found[key] = method
                            }
                        }
                    }
                }
            }
        }
        // A concrete receiver may inherit the packet handler from its superclass.
        parents.keys.forEach { type ->
            val key = "$type->M2([BII)V"
            if (key !in found) {
                val seen = mutableSetOf(type)
                var parent = parents[type]
                while (parent != null && seen.add(parent)) {
                    val handler = found["$parent->M2([BII)V"]
                    if (handler != null) { found[key] = handler; break }
                    parent = parents[parent]
                }
            }
        }
        return found
    }

    /** Verify that the bridge forwards callback array, value array, field ID and value in order. */
    internal fun isFieldPublisher(method: Method): Boolean {
        val body = method.implementation ?: return false
        val argsCount = if (method.name == "i0") 3 else 2
        if (body.registerCount < argsCount || body.tryBlocks.isNotEmpty()) return false
        val parameterNames = if (argsCount == 3) listOf("id", "value", "text") else listOf("id", "value")
        val registers = parameterNames.mapIndexed { index, name -> body.registerCount - argsCount + index to name }.toMap().toMutableMap()
        val helper = when (method.name) {
            "g0" -> "Li1/v;->s([Li1/v;[III)V" to listOf("callbacks", "values", "id", "value")
            "h0" -> "Li1/v;->p([Li1/v;ILjava/lang/String;)V" to listOf("callbacks", "id", "value")
            "i0" -> "Li1/v;->t([Li1/v;[II[ILjava/lang/String;)V" to listOf("callbacks", "values", "id", "value", "text")
            else -> return false
        }
        var forwarded = false
        var returned = false
        for (i in body.instructions) {
            val op = i.opcode.name
            if (returned && op != "nop") return false
            when {
                op == "nop" -> Unit
                op == "sget-object" -> {
                    val ref = ReferenceUtil.getReferenceString((i as ReferenceInstruction).reference)
                    registers[(i as OneRegisterInstruction).registerA] = when (ref) {
                        "Lf0/tp;->e:[Li1/v;" -> "callbacks"
                        "Lf0/tp;->f:[I" -> "values"
                        else -> return false
                    }
                }
                op.startsWith("move") && i is TwoRegisterInstruction ->
                    registers[i.registerA] = registers[i.registerB] ?: return false
                op == "invoke-static" || op == "invoke-static/range" -> {
                    if (forwarded || ReferenceUtil.getReferenceString((i as ReferenceInstruction).reference) != helper.first) return false
                    val args = when (i) {
                        is FiveRegisterInstruction -> listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
                        is RegisterRangeInstruction -> (i.startRegister until i.startRegister + i.registerCount).toList()
                        else -> return false
                    }.map { registers[it] }
                    if (args != helper.second) return false
                    forwarded = true
                }
                op == "return-void" -> returned = true
                else -> return false
            }
        }
        return forwarded && returned
    }
}
