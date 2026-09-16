package com.cabin.platform

import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.*

/** Profile-specific stock launcher routes. Unknown configuration branches retain both alternatives. */
internal object FytSyuScreens {
    private object Unknown
    private object CanData
    private data class State(val pc: Int, val regs: List<Any>, val result: Any = Unknown)

    fun roots(method: Method, profile: Int): Set<String> = references(method, profile, false)

    private fun references(method: Method, profile: Int, navigation: Boolean): Set<String> = try {
        val body = requireNotNull(method.implementation)
        require(body.tryBlocks.isEmpty())
        val code = linkedMapOf<Int, Instruction>()
        var address = 0
        body.instructions.forEach { code[address] = it; address += it.codeUnits }
        val pending = ArrayDeque<State>()
        pending.add(State(0, List(body.registerCount) { Unknown }))
        val seen = mutableSetOf<State>()
        val classes = mutableSetOf<String>()
        var steps = 0
        while (pending.isNotEmpty()) {
            val state = pending.removeLast()
            if (!seen.add(state)) continue
            require(++steps <= 20_000 && pending.size < 128)
            val i = code[state.pc] ?: error("branch")
            val op = i.opcode.name
            val r = state.regs.toMutableList()
            val a = (i as? OneRegisterInstruction)?.registerA ?: 0
            val b = (i as? TwoRegisterInstruction)?.registerB ?: 0
            val c = (i as? ThreeRegisterInstruction)?.registerC ?: 0
            var next = state.pc + i.codeUnits
            var result: Any = state.result
            when {
                op.startsWith("return") -> continue
                op == "nop" || op == "check-cast" -> Unit
                op == "const-class" -> {
                    val type = ((i as ReferenceInstruction).reference as TypeReference).type
                    r[a] = type
                    if (type.startsWith("Lcom/syu/carinfo/")) classes.add(type)
                }
                op.startsWith("const-string") -> {
                    val value = ((i as ReferenceInstruction).reference as StringReference).string
                    r[a] = value
                    if (value.startsWith("com.syu.carinfo.")) classes.add("L${value.replace('.', '/')};")
                }
                op.startsWith("const") && i is WideLiteralInstruction -> r[a] = i.wideLiteral.toInt()
                op.startsWith("move-result") -> r[a] = result
                op.startsWith("move") -> r[a] = r[b]
                op.startsWith("sget") -> {
                    val ref = (i as ReferenceInstruction).reference as FieldReference
                    r[a] = if (ref.definingClass == "Lcom/syu/module/canbus/DataCanbus;") when (ref.name) {
                        "DATA" -> CanData; "sCanbusId" -> profile; else -> Unknown
                    } else Unknown
                }
                op == "aget" -> r[a] = if (r[b] === CanData && r[c] == 1000) profile else Unknown
                op == "packed-switch" || op == "sparse-switch" -> {
                    val payload = code[state.pc + (i as OffsetInstruction).codeOffset] as SwitchPayload
                    val value = r[a] as? Int
                    if (value == null) {
                        require(navigation)
                        payload.switchElements.forEach { pending.add(State(state.pc + it.offset, r.toList(), result)) }
                    } else payload.switchElements.firstOrNull { it.key == value }?.let { next = state.pc + it.offset }
                }
                op.startsWith("goto") -> next = state.pc + (i as OffsetInstruction).codeOffset
                op.startsWith("if-") -> {
                    val left = r[a]
                    val right = if (op.endsWith("z")) 0 else r[b]
                    val target = state.pc + (i as OffsetInstruction).codeOffset
                    if (left === Unknown || right === Unknown) pending.add(State(target, r.toList(), result))
                    else {
                        val yes = when (op.removePrefix("if-").removeSuffix("z")) {
                            "eq" -> left == right; "ne" -> left != right
                            "lt" -> (left as Int) < (right as Int); "le" -> (left as Int) <= (right as Int)
                            "gt" -> (left as Int) > (right as Int); "ge" -> (left as Int) >= (right as Int)
                            else -> error("condition")
                        }
                        if (yes) next = target
                    }
                }
                op.startsWith("invoke-") -> result = Unknown
                op == "new-instance" -> {
                    val type = ((i as ReferenceInstruction).reference as TypeReference).type
                    r[a] = Unknown
                    if (type.startsWith("Lcom/syu/carinfo/")) classes.add(type)
                }
                navigation && op.startsWith("iget") -> r[a] = Unknown
                navigation && (op.startsWith("iput") || op.startsWith("sput")) -> Unit
                navigation && op in setOf("add-int/lit8", "and-int/lit16", "and-int/lit8") -> {
                    val x = r[b] as? Int
                    val y = (i as NarrowLiteralInstruction).narrowLiteral
                    r[a] = if (x == null) Unknown else if (op.startsWith("add")) x + y else x and y
                }
                else -> error("unsupported_route:$op")
            }
            pending.add(State(next, r.toList(), result))
        }
        classes
    } catch (_: Exception) { emptySet() }

    /** Follow explicit screen references only; never assume that a manufacturer's whole package applies. */
    fun reachable(roots: Set<String>, classes: Map<String, ClassDef>, profile: Int): Set<String> {
        val pending = ArrayDeque(roots)
        val seen = linkedSetOf<String>()
        while (pending.isNotEmpty()) {
            val type = pending.removeFirst()
            if (!type.startsWith("Lcom/syu/carinfo/") || !seen.add(type)) continue
            if (seen.size > 256) return emptySet()
            classes[type]?.methods?.forEach { method ->
                // Only navigation-bearing methods are interpreted. Full profile comparisons stay concrete.
                val hasDestination = method.implementation?.instructions?.any { instruction ->
                    val ref = (instruction as? ReferenceInstruction)?.reference
                    ref is TypeReference && ref.type.startsWith("Lcom/syu/carinfo/") && instruction.opcode.name in setOf("const-class", "new-instance") ||
                        ref is StringReference && ref.string.startsWith("com.syu.carinfo.")
                } == true
                if (hasDestination) references(method, profile, true).filterNot { it in seen }.forEach(pending::add)
            }
        }
        return seen
    }
}
