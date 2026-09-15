package com.cabin.platform

import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.util.ReferenceUtil

/** Inventory of possible callback IDs, without assigning vehicle meanings to them. */
internal object FytPublishedFields {
    data class Address(val module: Int, val field: Int)
    private data class Value(val integer: Int? = null, val module: Int? = null)
    private val callbackArrays = mapOf("Lf0/tp;->e:[Li1/v;" to 7, "Lp0/b;->c:[Li1/v;" to 0)
    private val directPublishers = mapOf(
        "Li1/v;->m([Li1/v;II)V" to 1,
        "Li1/v;->n([Li1/v;III)V" to 1,
        "Li1/v;->o([Li1/v;IILjava/lang/String;)V" to 1,
        "Li1/v;->p([Li1/v;ILjava/lang/String;)V" to 1,
        "Li1/v;->q([Li1/v;I[I)V" to 1,
        "Li1/v;->s([Li1/v;[III)V" to 2,
        "Li1/v;->t([Li1/v;[II[ILjava/lang/String;)V" to 2,
    )

    /** Static inventory only: helpers, constructors and Runnable bodies are never executed. */
    class Inventory(private val methods: Map<String, Method>) {
        private val publishers = setOf("Lf0/wp;->g0(II)V", "Lf0/wp;->h0(ILjava/lang/String;)V", "Lf0/wp;->i0(I[ILjava/lang/String;)V")
            .filter { key -> methods[key]?.let(FytCodeDetector::isFieldPublisher) == true }.toSet()
        private val byClass = methods.values.distinctBy { ReferenceUtil.getMethodDescriptor(it) }.groupBy { it.definingClass }
        private val scanned = mutableMapOf<String, Set<Address>>()
        private val cached = mutableMapOf<String, Map<Int, Set<Int>>>()

        fun discover(root: Method, ownerType: String = root.definingClass): Map<Int, Set<Int>> = cached.getOrPut(ownerType) {
            val pending = ArrayDeque<Method>().apply {
                addAll(byClass[ownerType].orEmpty())
                if (ownerType != root.definingClass) addAll(byClass[root.definingClass].orEmpty())
            }
            val visited = mutableSetOf<String>()
            val result = mutableSetOf<Address>()
            while (pending.isNotEmpty()) {
                val method = pending.removeFirst()
                val key = ReferenceUtil.getMethodDescriptor(method)
                if (!visited.add(key)) continue
                require(visited.size <= 2048) { "inventory_method_limit" }
                result.addAll(scanned.getOrPut(key) { scanAddresses(method, publishers) })
                method.implementation?.instructions?.forEach { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    if (reference is MethodReference && reference.definingClass != "Li1/v;") {
                        methods[ReferenceUtil.getMethodDescriptor(reference)]?.let { target ->
                            if (ReferenceUtil.getMethodDescriptor(target) !in visited) pending.add(target)
                        }
                    }
                    // Vendor receivers often create obfuscated Runnable classes in their constructors.
                    if (instruction.opcode.name == "new-instance" && reference is org.jf.dexlib2.iface.reference.TypeReference) {
                        byClass[reference.type].orEmpty().filter { it.name == "run" && it.parameterTypes.isEmpty() }
                            .forEach { if (ReferenceUtil.getMethodDescriptor(it) !in visited) pending.add(it) }
                    }
                }
            }
            result.groupBy { it.module }.mapValues { (_, addresses) -> addresses.map { it.field }.toSortedSet() }
        }
    }

    fun discover(root: Method, methods: Map<String, Method>): Set<Int> = Inventory(methods).discover(root)[7].orEmpty()

    internal fun scan(method: Method, publishers: Set<String> = setOf("Lf0/wp;->g0(II)V")): Set<Int> =
        scanAddresses(method, publishers).filter { it.module == 7 }.map { it.field }.toSet()

    private fun scanAddresses(method: Method, publishers: Set<String>): Set<Address> {
        val body = method.implementation ?: return emptySet()
        val code = linkedMapOf<Int, Instruction>()
        var offset = 0
        body.instructions.forEach { code[offset] = it; offset += it.codeUnits }
        if (code.isEmpty()) return emptySet()
        val states = mutableMapOf(0 to List<Value?>(body.registerCount) { null })
        val queue = ArrayDeque<Int>().apply { add(0) }
        var steps = 0
        while (queue.isNotEmpty()) {
            require(++steps <= 100_000) { "inventory_limit" }
            val pc = queue.removeFirst()
            val i = code[pc] ?: continue
            if (i is SwitchPayload || i is org.jf.dexlib2.iface.instruction.formats.ArrayPayload) continue
            val next = states.getValue(pc).toMutableList()
            val op = i.opcode.name
            val a = (i as? OneRegisterInstruction)?.registerA
            when {
                op.startsWith("const") && i is NarrowLiteralInstruction && a != null -> next[a] = Value(integer = i.narrowLiteral)
                op.startsWith("move") && i is TwoRegisterInstruction -> next[i.registerA] = next[i.registerB]
                op == "sget-object" && a != null && i is ReferenceInstruction ->
                    next[a] = callbackArrays[ReferenceUtil.getReferenceString(i.reference)]?.let { Value(module = it) }
                a != null && i.opcode.setsRegister() -> next[a] = null
            }
            val successors = when {
                op.startsWith("return") || op == "throw" -> emptyList()
                op.startsWith("goto") -> listOf(pc + (i as OffsetInstruction).codeOffset)
                op.startsWith("if-") -> listOf(pc + i.codeUnits, pc + (i as OffsetInstruction).codeOffset)
                op == "packed-switch" || op == "sparse-switch" -> {
                    val payload = code[pc + (i as OffsetInstruction).codeOffset] as? SwitchPayload
                    listOf(pc + i.codeUnits) + payload?.switchElements.orEmpty().map { pc + it.offset }
                }
                else -> listOf(pc + i.codeUnits)
            }
            successors.filter { it in code }.forEach { dest ->
                val previous = states[dest]
                val merged = previous?.mapIndexed { index, value -> if (value == next[index]) value else null } ?: next.toList()
                if (previous != merged) { states[dest] = merged; queue.add(dest) }
            }
        }
        return code.mapNotNull { (pc, instruction) ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
            val key = ReferenceUtil.getMethodDescriptor(ref)
            val args = when (instruction) {
                is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD, instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                is RegisterRangeInstruction -> (instruction.startRegister until instruction.startRegister + instruction.registerCount).toList()
                else -> return@mapNotNull null
            }.map { states[pc]?.get(it) }
            val index = directPublishers[key]
            val module = if (key in publishers) 7 else if (index != null) args.firstOrNull()?.module else null
            val field = args.getOrNull(if (key in publishers) 0 else index ?: return@mapNotNull null)?.integer
            if (module == null || field == null || field !in 0..1199 || module == 0 && field > 255 || module == 7 && field == 1000) null
            else Address(module, field)
        }.toSet()
    }
}
