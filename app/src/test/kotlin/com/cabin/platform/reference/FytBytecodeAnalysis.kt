package com.cabin.platform

import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.FieldReference
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.iface.reference.TypeReference
import org.jf.dexlib2.util.ReferenceUtil

/** Bounded static interpretation. No vendor class, constructor or native method is executed. */
internal object FytBytecodeAnalysis {
    data class Value(val text: String, val number: Int? = null) {
        companion object { fun int(n: Int) = Value(n.toString(), n) }
    }
    data class Publication(val field: Int, val expression: String, val conditions: Set<String>)
    data class Result(val returns: Set<String>, val publications: Set<Publication>)
    private data class State(val pc: Int, val regs: List<Value>, val conditions: Set<String>, val result: Value = Value("unknown"), val fields: Map<String, Value> = emptyMap())

    fun analyze(method: Method, profile: Int, packet: Int? = null): Result {
        val body = requireNotNull(method.implementation) { "missing_method_body" }
        require(body.tryBlocks.isEmpty()) { "exception_paths_unsupported" }
        val instructions = linkedMapOf<Int, Instruction>()
        var address = 0
        body.instructions.forEach { instructions[address] = it; address += it.codeUnits }
        val regs = MutableList(body.registerCount) { Value("unset:$it") }
        if (packet == null) regs[regs.lastIndex] = Value.int(profile)
        else {
            regs[regs.size - 4] = Value("this")
            regs[regs.size - 3] = Value("packet")
            regs[regs.size - 2] = Value.int(0)
            regs[regs.size - 1] = Value("packet_length")
        }
        val pending = ArrayDeque<State>()
        pending.add(State(0, regs, emptySet()))
        val seen = HashSet<State>()
        val returns = mutableSetOf<String>()
        val publications = mutableSetOf<Publication>()
        var steps = 0
        while (pending.isNotEmpty()) {
            val state = pending.removeLast()
            if (!seen.add(state)) continue
            require(++steps <= 100_000 && pending.size < 4096) { "analysis_limit" }
            val i = instructions[state.pc] ?: error("invalid_branch")
            val op = i.opcode.name
            val r = state.regs.toMutableList()
            var result = state.result
            val fields = state.fields.toMutableMap()
            var next = state.pc + i.codeUnits
            val a = (i as? OneRegisterInstruction)?.registerA ?: 0
            val b = (i as? TwoRegisterInstruction)?.registerB ?: 0
            val c = (i as? ThreeRegisterInstruction)?.registerC ?: 0
            fun add(pc: Int, cond: Set<String> = state.conditions) { pending.add(State(pc, r.toList(), cond, result, fields.toMap())) }
            when {
                op == "nop" -> Unit
                op.startsWith("const-string") -> r[a] = Value("string:" + ((i as ReferenceInstruction).reference as StringReference).string)
                op.startsWith("const") && i is WideLiteralInstruction -> r[a] = Value.int(i.wideLiteral.toInt())
                op.startsWith("move-result") -> r[a] = result
                op.startsWith("move") -> r[a] = r[b]
                op == "new-instance" -> r[a] = Value("object:" + ((i as ReferenceInstruction).reference as TypeReference).type)
                op.startsWith("return") -> { if (op != "return-void") returns.add(r[a].text); continue }
                op.startsWith("goto") -> next = state.pc + (i as OffsetInstruction).codeOffset
                op == "packed-switch" || op == "sparse-switch" -> {
                    val value = r[a].number ?: error("symbolic_switch_unsupported")
                    val payload = instructions[state.pc + (i as OffsetInstruction).codeOffset] as? SwitchPayload ?: error("invalid_switch")
                    payload.switchElements.firstOrNull { it.key == value }?.let { next = state.pc + it.offset }
                }
                op.startsWith("if-") -> {
                    val left = r[a]
                    val right = if (op.endsWith("z")) Value.int(0) else r[b]
                    val comparison = op.removePrefix("if-").removeSuffix("z")
                    val known = if (left.number != null && right.number != null) compare(comparison, left.number, right.number) else null
                    val target = state.pc + (i as OffsetInstruction).codeOffset
                    if (known != null) next = if (known) target else next
                    else {
                        val condition = "$comparison(${left.text},${right.text})"
                        if ("!$condition" !in state.conditions) add(target, state.conditions + condition)
                        if (condition !in state.conditions) add(next, state.conditions + "!$condition")
                        continue
                    }
                }
                op == "aget-byte" -> {
                    require(r[b].text == "packet") { "non_packet_array" }
                    val index = r[c].number ?: error("symbolic_packet_index")
                    r[a] = if (index == 0 && packet != null) Value.int(packet) else Value("byte[$index]")
                }
                op.startsWith("sget") || op.startsWith("iget") -> {
                    val ref = ReferenceUtil.getFieldDescriptor((i as ReferenceInstruction).reference as FieldReference)
                    r[a] = fields[ref] ?: if (ref == "Lf0/tp;->a:I") Value.int(profile) else Value("field:$ref")
                }
                op.startsWith("sput") || op.startsWith("iput") -> {
                    val ref = ReferenceUtil.getFieldDescriptor((i as ReferenceInstruction).reference as FieldReference)
                    fields[ref] = r[a] // Model the value; never apply a write to the installed service.
                }
                op.startsWith("invoke-") -> {
                    val ref = (i as ReferenceInstruction).reference as? MethodReference ?: error("unsupported_call")
                    val descriptor = ReferenceUtil.getMethodDescriptor(ref)
                    val arguments = when (i) {
                        is FiveRegisterInstruction -> listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
                        is RegisterRangeInstruction -> (i.startRegister until i.startRegister + i.registerCount).toList()
                        else -> error("unsupported_call_format")
                    }.map { r[it] }
                    if (descriptor == "Lf0/wp;->g0(II)V") {
                        val field = arguments[0].number ?: error("dynamic_field_id")
                        require(field in 0..1199) { "field_out_of_range" }
                        publications.add(Publication(field, arguments[1].text, state.conditions))
                    }
                    // Calls have no concrete evaluated result. Dependent comparisons retain the call identity.
                    result = Value("call:$descriptor(${arguments.joinToString { it.text }})")
                }
                op == "int-to-byte" -> r[a] = r[b].number?.let { Value.int(it.toByte().toInt()) } ?: Value("signedByte(${r[b].text})")
                listOf("add-int", "sub-int", "rsub-int", "and-int", "or-int", "xor-int", "shl-int", "shr-int", "ushr-int", "mul-int", "div-int", "rem-int").any { op.startsWith(it) } -> {
                    val name = op.substringBefore('-')
                    val left: Value
                    val right: Value
                    when {
                        i is NarrowLiteralInstruction -> { left = r[b]; right = Value.int(i.narrowLiteral) }
                        op.endsWith("/2addr") -> { left = r[a]; right = r[b] }
                        else -> { left = r[b]; right = r[c] }
                    }
                    r[a] = binary(name, left, right)
                }
                else -> error("unsupported_opcode:$op")
            }
            require(r.none { it.text.length > 8192 }) { "expression_limit" }
            add(next)
        }
        return Result(returns, publications)
    }

    private fun compare(op: String, a: Int, b: Int) = when (op) {
        "eq" -> a == b; "ne" -> a != b; "lt" -> a < b; "le" -> a <= b; "gt" -> a > b; "ge" -> a >= b
        else -> error("unsupported_comparison")
    }

    private fun binary(op: String, a: Value, b: Value): Value {
        if (a.number != null && b.number != null) {
            val x = a.number; val y = b.number
            return Value.int(when (op) {
                "add" -> x + y; "sub" -> x - y; "rsub" -> y - x; "and" -> x and y; "or" -> x or y
                "xor" -> x xor y; "shl" -> x shl y; "shr" -> x shr y; "ushr" -> x ushr y
                "mul" -> x * y; "div" -> x / y; "rem" -> x % y; else -> error("unsupported_arithmetic")
            })
        }
        if (b.number == 0 && op in setOf("add", "sub", "shl", "shr", "ushr", "or", "xor")) return a
        val parts = if (op in setOf("and", "or", "xor", "add", "mul")) listOf(a.text, b.text).sorted() else listOf(a.text, b.text)
        return Value("$op(${parts.joinToString(",")})")
    }

    /** Remove irrelevant branch decisions only when both outcomes publish the same value. */
    fun signatures(publications: Set<Publication>): Map<Int, Set<String>> = publications.groupBy { it.field }.mapValues { (_, events) ->
        events.groupBy { it.expression }.flatMap { (value, rows) ->
            val conditions = rows.map { it.conditions }.toMutableSet()
            var changed = true
            while (changed) {
                changed = false
                val snapshot = conditions.toList()
                outer@ for (a in snapshot) for (b in snapshot) {
                    val onlyA = a - b; val onlyB = b - a
                    if (onlyA.size == 1 && onlyB.size == 1 && (onlyA.single() == "!" + onlyB.single() || onlyB.single() == "!" + onlyA.single())) {
                        conditions.remove(a); conditions.remove(b); conditions.add(a intersect b); changed = true; break@outer
                    }
                }
            }
            conditions.map { value + " when " + it.sorted().joinToString(" && ") }
        }.toSet()
    }
}
