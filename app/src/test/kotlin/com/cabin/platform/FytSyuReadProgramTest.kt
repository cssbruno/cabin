package com.cabin.platform

import java.io.File
import org.jf.dexlib2.DexFileFactory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FytSyuReadProgramTest {
    private val apk = File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.canbus/190000000_com.syu.canbus.apk")
    private fun methods(type: String) = run {
        assumeTrue(apk.exists())
        val dex = DexFileFactory.loadDexContainer(apk, null)
        dex.dexEntryNames.asSequence().flatMap { dex.getEntry(it)!!.dexFile.classes.asSequence() }
            .first { it.type == type }.methods.associateBy { it.name }
    }
    private fun stock(name: String) = FytSyuReadProgram(methods("Lcom/syu/carinfo/honda/AcrivitySiYuSettings;").getValue(name))

    @Test fun `stock enums and offsets use actual screen bytecode`() {
        val trip = stock("updateTripA")
        fun text(value: Int) = trip.read(262442, mapOf(58 to value)) { "res:$it" }!!.output.single().text
        assertEquals("res:2131298628", text(0))
        assertEquals("res:2131298629", text(1))
        assertEquals("res:2131298630", text(2))
        assertEquals("res:2131298628", text(99)) // The stock default, not an invented enum label.
        val adjustment = stock("updateOutTemp").read(262442, mapOf(60 to 7)) { null }!!
        assertEquals("2", adjustment.output.single().text)
        assertEquals(setOf(60), adjustment.fields)
        assertNull(trip.read(262442, emptyMap()) { "res:$it" })
        assertNull(trip.read(262442, mapOf(58 to 1)) { null })
    }

    @Test fun `stock switch limits and check states preserve unsupported and false values`() {
        val sensitivity = stock("updateAutoLightSens")
        assertEquals("middle", sensitivity.read(262442, mapOf(61 to 2)) { null }!!.output.single().text)
        assertNull(sensitivity.read(262442, mapOf(61 to 9)) { null })
        val checked = stock("updateInOutSeatSport")
        assertEquals(false, checked.read(262442, mapOf(113 to 0)) { null }!!.output.single().checked)
        assertEquals(true, checked.read(262442, mapOf(113 to 1)) { null }!!.output.single().checked)
        assertNull(checked.read(262442, emptyMap()) { null })
    }

    @Test fun `installed launcher selects actual profile screens and their formatters`() {
        val launcher = methods("Lcom/syu/canbus/ActivityLauncher;").getValue("launchCanbus")
        val roots = FytSyuScreens.roots(launcher, 262442)
        assertTrue(roots.toString(), roots.contains("Lcom/syu/carinfo/honda/ActivitySiYuIndex;"))
        assertTrue(FytSyuScreens.roots(launcher, Int.MAX_VALUE).isEmpty())
        val resolver = FytSyuClientCatalog.resolver(listOf(apk)) { "res:$it" }
        val client = resolver(262442)
        assertTrue(client.screens.contains("Lcom/syu/carinfo/honda/AcrivitySiYuSettings;"))
        val rows = client.display!!.read(mapOf(58 to 1, 60 to 7, 61 to 2, 113 to 1, 114 to 1))
        assertTrue(rows.toString(), rows.any { it.fields == setOf(58) && it.text == "res:2131298629" })
        assertTrue(rows.any { it.fields == setOf(61) && it.text == "middle" })
        assertTrue(rows.toString(), rows.any { it.fields == setOf(114) && it.text == "res:2131299464" })
    }
    @Test fun `formatter cache removes expired fields and refreshes changes`() {
        val display = ReferenceSyuDisplay(262442, listOf("screen" to stock("updateAutoLightSens"))) { null }
        assertEquals("middle", display.read(mapOf(61 to 2)).single().text)
        assertEquals("middle", display.read(mapOf(61 to 2, 89 to 30)).single().text)
        assertEquals("high", display.read(mapOf(61 to 3)).single().text)
        assertTrue(display.read(emptyMap()).isEmpty())
        assertEquals("min", display.read(mapOf(61 to 0)).single().text)
    }

    @Test fun `all stock catalog profiles are audited for screen routing without assuming hardware support`() {
        assumeTrue(apk.exists())
        val launcher = methods("Lcom/syu/canbus/ActivityLauncher;").getValue("launchCanbus")
        val catalog = File("src/main/assets/syu/profile-catalog.json").readText()
        val profiles = Regex("\"([0-9]+)\"\\s*:").findAll(catalog).map { it.groupValues[1].toInt() }.toSet()
        val routes = profiles.associateWith { FytSyuScreens.roots(launcher, it) }
        File("build/reports/fyt-syu-screen-routes.txt").apply { parentFile?.mkdirs() }.writeText(routes.entries.joinToString("\n") {
            "${it.key}: ${it.value.sorted().joinToString()}"
        })
        assertEquals(3349, routes.size)
        println("Stock SYU launcher routes: ${routes.values.count { it.isNotEmpty() }} / ${routes.size}")
        assertTrue(routes.values.count { it.isNotEmpty() } > 1000)
        // VW and Honda must not acquire each other's screens through catalog traversal.
        assertFalse(routes.getValue(1).any { "/honda/" in it })
        assertFalse(routes.getValue(262442).any { "/dasauto/" in it })
    }

    @Test fun `stock Audi speed keeps the original floating point conversion and unit`() {
        val program = FytSyuReadProgram(methods("Lcom/syu/carinfo/bagoo/aodi/AudiAct;").getValue("updatercarSpeed"))
        val result = program.read(123, mapOf(1 to 800)) { null }
        assertNotNull(result)
        assertEquals(setOf(1), result!!.fields)
        assertEquals(String.format("%.4f", 50.0f) + "Km/h", result.output.single().text)
    }

    @Test fun `unrecognized side effects and infinite loops discard the entire reading`() {
        val original = methods("Lcom/syu/carinfo/honda/AcrivitySiYuSettings;").getValue("updateOutTemp")
        val body = original.implementation!!
        val writes = org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c(org.jf.dexlib2.Opcode.SPUT, 0,
            org.jf.dexlib2.immutable.reference.ImmutableFieldReference("Lunknown;", "state", "I"))
        val changedBody = org.jf.dexlib2.immutable.ImmutableMethodImplementation(body.registerCount,
            body.instructions.flatMap { if (it.opcode == org.jf.dexlib2.Opcode.RETURN_VOID) listOf(writes, it) else listOf(it) }, emptyList(), emptyList())
        val changed = object : org.jf.dexlib2.iface.Method by original { override fun getImplementation() = changedBody }
        assertNull(FytSyuReadProgram(changed).read(262442, mapOf(60 to 7)) { null })
        val loopBody = org.jf.dexlib2.immutable.ImmutableMethodImplementation(body.registerCount,
            listOf(org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t(org.jf.dexlib2.Opcode.GOTO, 0)), emptyList(), emptyList())
        val loop = object : org.jf.dexlib2.iface.Method by original { override fun getImplementation() = loopBody }
        assertNull(FytSyuReadProgram(loop).read(262442, mapOf(60 to 7)) { null })
    }

    @Test fun `only unchanged callback payloads enter the stock display data array`() {
        val callback = methods("Lcom/syu/module/canbus/Callback_0298_XP1_2015SIYU_CRV;").getValue("update")
        val eligible = FytSyuNotifyRoutes.forwarded(callback, 262442, setOf(35, 58, 60, 61, 114, 118, 500))
        assertTrue(eligible.containsAll(setOf(58, 60, 61, 114, 118, 500)))
        assertFalse(35 in eligible) // This callback is a page-toggle event, not stored data.
    }

    @Test fun `common handler must preserve the scalar before screen formatters can use it`() {
        assumeTrue(apk.exists())
        val dex = DexFileFactory.loadDexContainer(apk, null)
        val handlers = dex.dexEntryNames.asSequence().flatMap { dex.getEntry(it)!!.dexFile.classes.asSequence() }
            .first { it.type == "Lcom/syu/module/canbus/HandlerCanbus;" }.methods.filter { it.name == "update" }
        assertEquals(3, handlers.size)
        handlers.forEach { assertTrue(it.toString(), FytSyuNotifyRoutes.copiesScalar(it)) }
        val original = handlers.first { it.parameterTypes.map { it.toString() } == listOf("I", "I") }
        val body = original.implementation!!
        val scale = org.jf.dexlib2.immutable.instruction.ImmutableInstruction22b(org.jf.dexlib2.Opcode.DIV_INT_LIT8,
            body.registerCount - 1, body.registerCount - 1, 10)
        val changedBody = org.jf.dexlib2.immutable.ImmutableMethodImplementation(body.registerCount,
            listOf(scale) + body.instructions, emptyList(), emptyList())
        val changed = object : org.jf.dexlib2.iface.Method by original { override fun getImplementation() = changedBody }
        assertFalse(FytSyuNotifyRoutes.copiesScalar(changed))
    }

}
