package com.cabin.platform

import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.*
import org.jf.dexlib2.iface.reference.*

/** An inert, bounded interpreter for SYU display calculations. No vendor code is loaded or invoked. */
internal class FytSyuReadProgram(private val method: Method, private val inputField: Int? = null) {
    val referenceKey: String get() = org.jf.dexlib2.util.ReferenceUtil.getMethodDescriptor(method) + ":" + inputField
    val profileDependent: Boolean get() = body.instructions.any {
        (it as? WideLiteralInstruction)?.wideLiteral == 1000L ||
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "sCanbusId"
    }
    private val body = requireNotNull(method.implementation)
    private val code = linkedMapOf<Int, Instruction>().apply {
        var pc = 0
        body.instructions.forEach { put(pc, it); pc += it.codeUnits }
    }
    private data class View(val id: Int)
    private object CanData
    private object Receiver
    private data class Box(val value: Any)
    data class Output(val view: Int, val text: String? = null, val checked: Boolean? = null)
    data class Result(val fields: Set<Int>, val output: List<Output>)

    fun read(profile: Int, values: Map<Int, Int>, string: (Int) -> String?): Result? = try {
        require(body.tryBlocks.isEmpty() && method.returnType == "V")
        require(method.parameterTypes.map { it.toString() } == if (inputField == null) emptyList<String>() else listOf("I"))
        val registers = arrayOfNulls<Any>(body.registerCount)
        registers[registers.lastIndex - if (inputField == null) 0 else 1] = Receiver
        if (inputField != null) registers[registers.lastIndex] = values[inputField] ?: error("missing_live_value")
        val fields = linkedSetOf<Int>().apply { inputField?.let(::add) }
        val output = linkedMapOf<Int, Output>()
        var result: Any? = null
        var pc = 0
        var steps = 0
        fun int(value: Any?) = value as? Int ?: error("not_integer")
        fun floating(value: Any?): Float = when (value) { is Float -> value; is Int -> Float.fromBits(value); else -> error("not_float") }
        fun double(value: Any?): Double = when (value) { is Double -> value; is Long -> Double.fromBits(value); else -> error("not_double") }
        fun typed(value: Any?, type: String): Any = when (type) {
            "F" -> floating(value); "D" -> double(value); "Z" -> int(value) != 0
            "C" -> int(value).toChar(); else -> (value as? Box)?.value ?: value ?: error("unknown_argument")
        }
        fun text(value: Any?): String = when (value) {
            is String -> value
            is Number, is Boolean, is Char -> value.toString()
            else -> error("not_text")
        }
        while (true) {
            require(++steps <= 2048)
            val i = code[pc] ?: error("invalid_branch")
            val op = i.opcode.name
            val a = (i as? OneRegisterInstruction)?.registerA ?: 0
            val b = (i as? TwoRegisterInstruction)?.registerB ?: 0
            val c = (i as? ThreeRegisterInstruction)?.registerC ?: 0
            var next = pc + i.codeUnits
            when {
                op == "return-void" -> break
                op == "nop" || op == "check-cast" -> Unit
                op.startsWith("const-string") -> registers[a] = ((i as ReferenceInstruction).reference as StringReference).string
                op.startsWith("const") && i is WideLiteralInstruction -> registers[a] = if (op.startsWith("const-wide")) i.wideLiteral else i.wideLiteral.toInt()
                op.startsWith("move-result") -> registers[a] = result
                op.startsWith("move") -> registers[a] = registers[b]
                op == "new-instance" -> {
                    require(((i as ReferenceInstruction).reference as TypeReference).type == "Ljava/lang/StringBuilder;")
                    registers[a] = StringBuilder()
                }
                op.startsWith("sget") -> {
                    val ref = (i as ReferenceInstruction).reference as FieldReference
                    require(ref.definingClass == "Lcom/syu/module/canbus/DataCanbus;")
                    registers[a] = when (ref.name) { "DATA" -> CanData; "sCanbusId" -> profile; else -> error("unknown_static") }
                }
                op == "new-array" -> {
                    val size = int(registers[b]); require(size in 0..64)
                    registers[a] = arrayOfNulls<Any>(size)
                }
                op == "aput-object" -> {
                    @Suppress("UNCHECKED_CAST")
                    val array = registers[b] as? Array<Any?> ?: error("not_array")
                    array[int(registers[c])] = registers[a]
                }
                op == "aget" -> {
                    require(registers[b] === CanData)
                    val id = int(registers[c])
                    require(id in 0..1199)
                    registers[a] = if (id == 1000) profile else {
                        fields.add(id)
                        values[id] ?: error("missing_live_value")
                    }
                }
                op.startsWith("if-") -> {
                    val left = registers[a] ?: error("unknown_condition")
                    val right = if (op.endsWith("z")) 0 else registers[b] ?: error("unknown_condition")
                    val comparison = op.removePrefix("if-").removeSuffix("z")
                    val matches = when (comparison) {
                        "eq" -> left == right
                        "ne" -> left != right
                        "lt" -> int(left) < int(right)
                        "le" -> int(left) <= int(right)
                        "gt" -> int(left) > int(right)
                        "ge" -> int(left) >= int(right)
                        else -> error("comparison")
                    }
                    if (matches) next = pc + (i as OffsetInstruction).codeOffset
                }
                op == "packed-switch" || op == "sparse-switch" -> {
                    val payload = code[pc + (i as OffsetInstruction).codeOffset] as SwitchPayload
                    payload.switchElements.firstOrNull { it.key == int(registers[a]) }?.let { next = pc + it.offset }
                }
                op.startsWith("goto") -> next = pc + (i as OffsetInstruction).codeOffset
                op == "int-to-float" -> registers[a] = int(registers[b]).toFloat()
                op == "int-to-double" -> registers[a] = int(registers[b]).toDouble()
                op == "int-to-long" -> registers[a] = int(registers[b]).toLong()
                op == "float-to-int" -> registers[a] = floating(registers[b]).toInt()
                op == "double-to-int" -> registers[a] = double(registers[b]).toInt()
                op == "float-to-double" -> registers[a] = floating(registers[b]).toDouble()
                op == "double-to-float" -> registers[a] = double(registers[b]).toFloat()
                (op.contains("-float") || op.contains("-double")) && op.substringBefore('-') in setOf("add", "sub", "mul", "div", "rem") -> {
                    val wide = op.contains("-double")
                    fun value(index: Int) = if (wide) double(registers[index]) else floating(registers[index]).toDouble()
                    val x = value(if (op.endsWith("/2addr")) a else b)
                    val y = value(if (op.endsWith("/2addr")) b else c)
                    val number = when (op.substringBefore('-')) { "add" -> x+y; "sub" -> x-y; "mul" -> x*y; "div" -> x/y; else -> x%y }
                    require(number.isFinite())
                    registers[a] = if (wide) number else number.toFloat()
                }
                op == "int-to-byte" -> registers[a] = int(registers[b]).toByte().toInt()
                op == "int-to-short" -> registers[a] = int(registers[b]).toShort().toInt()
                op == "int-to-char" -> registers[a] = int(registers[b]) and 65535
                op == "neg-int" -> registers[a] = -int(registers[b])
                op == "not-int" -> registers[a] = int(registers[b]).inv()
                op.contains("-int") && op.substringBefore('-') in integerOps -> {
                    val x = int(registers[if (op.endsWith("/2addr")) a else b])
                    val y = if (i is NarrowLiteralInstruction) i.narrowLiteral else int(registers[if (op.endsWith("/2addr")) b else c])
                    registers[a] = when (op.substringBefore('-')) {
                        "add" -> x + y; "sub" -> x - y; "rsub" -> y - x; "mul" -> x * y
                        "div" -> x / y; "rem" -> x % y; "and" -> x and y; "or" -> x or y
                        "xor" -> x xor y; "shl" -> x shl y; "shr" -> x shr y; "ushr" -> x ushr y
                        else -> error("arithmetic")
                    }
                }
                op.startsWith("invoke-") -> {
                    val ref = (i as ReferenceInstruction).reference as MethodReference
                    val rawArgs = argumentRegisters(i)
                    val args = mutableListOf<Any?>()
                    var index = 0
                    if (!op.startsWith("invoke-static")) args.add(registers[rawArgs[index++]])
                    ref.parameterTypes.forEach { type ->
                        args.add(registers[rawArgs[index]])
                        index += if (type.toString() in setOf("J", "D")) 2 else 1
                    }
                    result = when {
                        ref.name == "findViewById" && args.firstOrNull() === Receiver && ref.parameterTypes.map { it.toString() } == listOf("I") -> View(int(args[1]))
                        ref.name == "getString" && args.firstOrNull() === Receiver && args.size == 2 -> string(int(args[1])) ?: error("missing_resource")
                        ref.definingClass == "Ljava/lang/StringBuilder;" -> {
                            val builder = args[0] as? StringBuilder ?: error("builder")
                            when (ref.name) {
                                "<init>" -> { if (args.size == 2) builder.append(text(args[1])); Unit }
                                "append" -> { require(args.size == 2); builder.append(text(typed(args[1], ref.parameterTypes.single().toString()))); require(builder.length <= 4096); builder }
                                "toString" -> builder.toString()
                                else -> error("builder_call")
                            }
                        }
                        ref.definingClass == "Ljava/lang/String;" && ref.name == "valueOf" && args.size == 1 -> text(typed(args[0], ref.parameterTypes.single().toString()))
                        ref.definingClass in setOf("Ljava/lang/Integer;", "Ljava/lang/Float;", "Ljava/lang/Double;", "Ljava/lang/Long;") && ref.name == "valueOf" && args.size == 1 -> Box(typed(args[0], ref.parameterTypes.single().toString()))
                        ref.definingClass == "Ljava/lang/String;" && ref.name == "format" && args.size == 2 -> {
                            val format = args[0] as? String ?: error("format")
                            // Bound width/precision before allowing Java's formatter to allocate.
                            require(format.length <= 128 && Regex("[0-9]{3,}").find(format) == null)
                            val array = args[1] as? Array<*> ?: error("format_args")
                            require(array.all { it is Box || it is String })
                            String.format(format, *array.map { if (it is Box) it.value else it }.toTypedArray())
                        }
                        ref.name == "setText" && args.firstOrNull() is View && ref.definingClass.startsWith("Landroid/widget/") -> {
                            val view = (args[0] as View).id
                            val value = if (ref.parameterTypes.map { it.toString() } == listOf("I")) string(int(args[1])) ?: error("missing_resource") else text(args[1])
                            require(value.length <= 4096)
                            output[view] = Output(view, text = value)
                            Unit
                        }
                        ref.name == "setChecked" && args.firstOrNull() is View && ref.definingClass.startsWith("Landroid/widget/") -> {
                            val view = (args[0] as View).id
                            output[view] = Output(view, checked = int(args[1]) != 0)
                            Unit
                        }
                        else -> error("unsupported_call") // Includes every Binder command and every vendor helper.
                    }
                }
                else -> error("unsupported_opcode")
            }
            pc = next
        }
        if (fields.isEmpty() || output.isEmpty()) null else Result(fields, output.values.toList())
    } catch (_: Exception) { null }

    companion object {
        private val integerOps = setOf("add", "sub", "rsub", "mul", "div", "rem", "and", "or", "xor", "shl", "shr", "ushr")
        internal fun argumentRegisters(i: Instruction): List<Int> = when (i) {
            is FiveRegisterInstruction -> listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
            is RegisterRangeInstruction -> (i.startRegister until i.startRegister + i.registerCount).toList()
            else -> error("call_registers")
        }
        fun candidate(method: Method): Boolean = method.parameterTypes.isEmpty() && method.returnType == "V" &&
            method.name != "<init>" && method.accessFlags and 8 == 0 && method.implementation?.tryBlocks?.isEmpty() == true &&
            method.implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? FieldReference
                ref?.definingClass == "Lcom/syu/module/canbus/DataCanbus;" && ref.name == "DATA"
            } && method.implementation!!.instructions.any {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.name in setOf("setText", "setChecked")
            }
    }
}
