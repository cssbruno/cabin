package com.cabin.platform

import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.*
import org.jf.dexlib2.util.ReferenceUtil

/** Associate an onNotify payload with its actual display helper, rather than guessing from its name. */
internal object FytSyuNotifyRoutes {
    private data class Field(val id: Int)
    private object Data
    private object Payload
    private object Unknown

    fun forwarded(method: Method, profile: Int, fields: Set<Int>, handlers: Set<String>? = null): Set<Int> = inspect(method, profile, fields, true, handlers)["forwarded"].orEmpty()

    fun helpers(method: Method, profile: Int, fields: Set<Int>): Map<String, Set<Int>> = inspect(method, profile, fields, false)

    /** Check that HandlerCanbus stores the original scalar at the original update ID. */
    fun copiesScalar(method: Method): Boolean = try {
        require(method.name == "update" && method.accessFlags and 8 != 0)
        val types = method.parameterTypes.map { it.toString() }
        require(types in listOf(listOf("I", "[I"), listOf("I", "I"), listOf("I", "[I", "[F", "[Ljava/lang/String;")))
        val body = requireNotNull(method.implementation)
        require(body.tryBlocks.isEmpty())
        val r = MutableList<Any>(body.registerCount) { Unknown }
        r[r.size - types.size] = "index"
        r[r.size - types.size + 1] = if (types[1] == "I") "value" else Payload
        var stores = 0
        body.instructions.forEach { i ->
            val op = i.opcode.name
            val a = (i as? OneRegisterInstruction)?.registerA ?: 0
            val b = (i as? TwoRegisterInstruction)?.registerB ?: 0
            val c = (i as? ThreeRegisterInstruction)?.registerC ?: 0
            when {
                op == "nop" || op.startsWith("if-") || op.startsWith("goto") || op.startsWith("return") -> Unit
                op.startsWith("const") && i is WideLiteralInstruction -> r[a] = i.wideLiteral.toInt()
                op.startsWith("move-result") -> r[a] = Unknown
                op.startsWith("move") -> r[a] = r[b]
                op == "array-length" -> r[a] = Unknown
                op == "sget-object" -> {
                    val ref = (i as ReferenceInstruction).reference as FieldReference
                    r[a] = if (ref.definingClass == "Lcom/syu/module/canbus/DataCanbus;" && ref.name == "DATA") Data else Unknown
                }
                op.startsWith("aget") -> r[a] = if (r[b] === Payload && r[c] == 0) "value" else Unknown
                op == "aput" -> { require(r[a] == "value" && r[b] === Data && r[c] == "index"); stores++ }
                op.startsWith("invoke-") -> {
                    val ref = (i as ReferenceInstruction).reference as MethodReference
                    require(ref.name == "onNotify" && ref.definingClass == "Lcom/syu/module/UiNotifyEvent;")
                }
                else -> error("transformed_handler")
            }
        }
        stores > 0
    } catch (_: Exception) { false }

    private fun inspect(method: Method, profile: Int, fields: Set<Int>, forwarding: Boolean, handlers: Set<String>? = null): Map<String, Set<Int>> {
        if (method.name != (if (forwarding) "update" else "onNotify") || method.parameterTypes.map { it.toString() } != listOf("I", "[I", "[F", "[Ljava/lang/String;")) return emptyMap()
        val body = method.implementation ?: return emptyMap()
        if (body.tryBlocks.isNotEmpty()) return emptyMap()
        val code = linkedMapOf<Int, Instruction>()
        var pc = 0
        body.instructions.forEach { code[pc] = it; pc += it.codeUnits }
        val targets = linkedMapOf<String, MutableSet<Int>>()
        fields.filter { it in 0..1199 && it != 1000 }.forEach { field ->
            val calls = try {
                val r = MutableList<Any>(body.registerCount) { Unknown }
                r[r.size - 4] = field
                r[r.size - 3] = Payload
                val found = mutableSetOf<Pair<String, Int>>()
                var address = 0
                var steps = 0
                while (true) {
                    require(++steps <= 4096)
                    val i = code[address] ?: error("branch")
                    val op = i.opcode.name
                    val a = (i as? OneRegisterInstruction)?.registerA ?: 0
                    val b = (i as? TwoRegisterInstruction)?.registerB ?: 0
                    val c = (i as? ThreeRegisterInstruction)?.registerC ?: 0
                    var next = address + i.codeUnits
                    when {
                        op.startsWith("return") -> break
                        op == "nop" || op == "check-cast" -> Unit
                        op.startsWith("const") && i is WideLiteralInstruction -> r[a] = i.wideLiteral.toInt()
                        op.startsWith("move-result") -> r[a] = Unknown
                        op.startsWith("move") -> r[a] = r[b]
                        op.startsWith("iget") -> r[a] = Unknown
                        op.startsWith("sget") -> {
                            val ref = (i as ReferenceInstruction).reference as FieldReference
                            r[a] = if (ref.definingClass == "Lcom/syu/module/canbus/DataCanbus;" && ref.name == "DATA") Data else Unknown
                        }
                        op == "array-length" -> { require(r[b] === Payload); r[a] = 1 }
                        op == "aget" -> r[a] = when {
                            r[b] === Data && r[c] == 1000 -> profile
                            r[b] === Data && r[c] is Int -> Field(r[c] as Int)
                            r[b] === Payload && r[c] == 0 -> Field(field)
                            else -> Unknown
                        }
                        op == "packed-switch" || op == "sparse-switch" -> {
                            val payload = code[address + (i as OffsetInstruction).codeOffset] as SwitchPayload
                            val value = r[a] as? Int ?: error("live_switch")
                            payload.switchElements.firstOrNull { it.key == value }?.let { next = address + it.offset }
                        }
                        op.startsWith("goto") -> next = address + (i as OffsetInstruction).codeOffset
                        op.startsWith("if-") -> {
                            val left = if (r[a] === Payload) 1 else r[a] as? Int ?: error("live_guard")
                            val right = if (op.endsWith("z")) 0 else if (r[b] === Payload) 1 else r[b] as? Int ?: error("live_guard")
                            val yes = when (op.removePrefix("if-").removeSuffix("z")) {
                                "eq" -> left == right; "ne" -> left != right; "lt" -> left < right
                                "le" -> left <= right; "gt" -> left > right; "ge" -> left >= right
                                else -> error("guard")
                            }
                            if (yes) next = address + (i as OffsetInstruction).codeOffset
                        }
                        op.startsWith("invoke-") -> {
                            val ref = (i as ReferenceInstruction).reference as? MethodReference ?: error("invoke")
                            val args = FytSyuReadProgram.argumentRegisters(i).map { r[it] }
                            if (forwarding) {
                                require(ref.definingClass == "Lcom/syu/module/canbus/HandlerCanbus;" && ref.name == "update")
                                require(handlers == null || ReferenceUtil.getMethodDescriptor(ref) in handlers)
                                require(args[0] == field && (args[1] === Payload || args[1] == Field(field)))
                                found.add("forwarded" to field)
                            } else if (!op.startsWith("invoke-static") && ref.parameterTypes.map { it.toString() } == listOf("I") &&
                                ref.definingClass.startsWith("Lcom/syu/carinfo/") && args.lastOrNull() is Field) {
                                found.add(ReferenceUtil.getMethodDescriptor(ref) to (args.last() as Field).id)
                            }
                        }
                        else -> error("unsupported_notify")
                    }
                    address = next
                }
                found
            } catch (_: Exception) { emptySet() }
            calls.forEach { (target, id) -> if (id in fields) targets.getOrPut(target) { mutableSetOf() }.add(id) }
        }
        return targets
    }
}
