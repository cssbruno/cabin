package com.cabin.platform

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21s
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x

class FytCodeDetectorTest {
    @Test fun `semantic mapping requires a unique source and keeps other fields`() {
        val expected = FytFieldSemantics.expected
        val changed = expected.mapValues { (_, fields) -> fields.mapKeys { if (it.key == 24) 324 else it.key } }
        assertEquals(324, FytFieldSemantics.match(262442, changed).fields[24])
        val ambiguous = changed + (0x21 to (changed.getValue(0x21) + (325 to expected.getValue(0x21).getValue(24))))
        val result = FytFieldSemantics.match(262442, ambiguous)
        assertFalse(result.fields.containsKey(24))
        assertEquals(29, result.fields[29])
        assertEquals("partial_match", result.reason)
        assertTrue(24 in result.unmatched)
    }

    @Test fun `live normalization uses discovered IDs and rejects stale profile and invalid values`() {
        val detected = FytDetectedProfile(262442, mapOf(24 to 324, 29 to 329), "matched")
        assertEquals(mapOf(1000 to 262442, 24 to 1, 29 to 5), detected.normalize(mapOf(1000 to 262442, 324 to 1, 329 to 5)))
        assertTrue(detected.normalize(mapOf(1000 to 1048874, 324 to 1)).isEmpty())
        assertEquals(mapOf(1000 to 262442), detected.normalize(mapOf(1000 to 262442, 324 to 3, 329 to 8)))
    }

    @Test fun `real code detects moved field IDs and unrelated instruction changes`() {
        val apk = referenceApk()
        assumeTrue(apk.exists())
        val methods = FytCodeDetector.readMethods(listOf(apk))
        val key = "Lmodule/canbus/v;->M2([BII)V"
        val receiver = methods.getValue(key)
        val body = receiver.implementation!!
        val moved = body.instructions.map { instruction ->
            if (instruction.opcode == Opcode.CONST_16 && (instruction as org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral == 24)
                ImmutableInstruction21s(Opcode.CONST_16, (instruction as org.jf.dexlib2.iface.instruction.OneRegisterInstruction).registerA, 324)
            else instruction
        }
        val implementation = ImmutableMethodImplementation(body.registerCount,
            listOf(ImmutableInstruction10x(Opcode.NOP), ImmutableInstruction10x(Opcode.NOP)) + moved, body.tryBlocks, emptyList())
        val changed = object : org.jf.dexlib2.iface.Method by receiver {
            override fun getImplementation() = implementation
        }
        val detected = FytCodeDetector.detectMethods(methods + (key to changed)).getValue(262442)
        assertEquals("matched", detected.reason)
        assertEquals(324, detected.fields[24])
        assertEquals(29, detected.fields[29])
        assertEquals(37, detected.fields[37])
        val missing = FytCodeDetector.detectMethods(methods - "Lf0/wp;->g0(II)V").getValue(262442)
        assertEquals("publisher_not_recognized", missing.reason)
        assertTrue(missing.fields.isEmpty())
    }

    @Test fun `all reference profiles are dispatched without a Honda whitelist`() {
        val apk = referenceApk()
        assumeTrue(apk.exists())
        val catalog = File("src/main/assets/syu/profile-catalog.json").readText()
        val profiles = Regex("\"([0-9]+)\"\\s*:").findAll(catalog).map { it.groupValues[1].toInt() }.toSet()
        assertEquals(3349, profiles.size)
        val results = FytCodeDetector.detectMethods(FytCodeDetector.readMethods(listOf(apk)), profiles)
        File("build/reports/fyt-profile-coverage.txt").apply { parentFile?.mkdirs() }.writeText(
            results.entries.joinToString("\n") { (id, result) -> "$id ${result.reason} ${result.receiver} fields=${result.moduleFields.values.sumOf { it.size }} modules=${result.moduleFields.keys}" })
        println("FYT profile coverage: " + results.values.groupingBy { it.reason }.eachCount())
        assertEquals(profiles, results.keys)
        assertTrue(results.values.any { it.profile !in FytFieldSemantics.profiles && it.publishedFields.isNotEmpty() })
        assertTrue(results.values.count { it.moduleFields.isNotEmpty() } >= 3328)
        assertTrue(results.values.none { it.reason == "no_static_fields" })
        assertEquals("no_packet_reader", results.getValue(439).reason)
        assertTrue(results.getValue(2425284).moduleFields[0].orEmpty().isNotEmpty())
        assertTrue(results.getValue(393514).fields.isNotEmpty())
        results.values.filter { it.receiver != "Lmodule/canbus/v;" }.forEach { assertTrue(it.fields.isEmpty()) }
    }

    @Test fun `unsupported semantic instruction retains raw discovered fields`() {
        val apk = referenceApk()
        assumeTrue(apk.exists())
        val methods = FytCodeDetector.readMethods(listOf(apk))
        val key = "Lmodule/canbus/v;->M2([BII)V"
        val receiver = methods.getValue(key)
        val body = receiver.implementation!!
        val prefix = List(2) { org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x(Opcode.MONITOR_ENTER, 0) }
        val changed = object : org.jf.dexlib2.iface.Method by receiver {
            override fun getImplementation() = ImmutableMethodImplementation(body.registerCount,
                prefix + body.instructions.toList(), body.tryBlocks, emptyList())
        }
        val result = FytCodeDetector.detectMethods(methods + (key to changed), setOf(262442)).getValue(262442)
        assertEquals("raw_fields_only", result.reason)
        assertTrue(result.fields.isEmpty())
        assertTrue(result.publishedFields.containsAll(setOf(24, 29, 37)))
    }

    private fun referenceApk() = File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.ms/190000000_com.syu.ms.apk")

    @Test fun `invalid installed code remains unknown`() {
        val file = File.createTempFile("fyt-invalid", ".apk")
        try {
            file.writeText("not an APK")
            assertEquals(TeyesVehicleDataLayout.UNKNOWN, FytCodeDetector.layout(listOf(file)))
        } finally { file.delete() }
    }

    /** Local integration against user-supplied firmware; proprietary APK is not committed to CI. */
    @Test fun `reference bytecode is detected even after APK resources change`() {
        val apk = File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.ms/190000000_com.syu.ms.apk")
        assumeTrue(apk.exists())
        assertEquals(TeyesVehicleDataLayout.JOYING_2023, FytCodeDetector.layout(listOf(apk)))
        val profile = FytCodeDetector.detect(listOf(apk)).getValue(262442)
        assertEquals("matched", profile.reason)
        assertEquals(FytFieldSemantics.expected.values.flatMap { it.keys }.associateWith { it }, profile.fields)
        val repacked = File.createTempFile("fyt-repacked", ".apk")
        try {
            ZipOutputStream(repacked.outputStream()).use { out ->
                ZipFile(apk).use { zip ->
                    zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
                        // Moving classes.dex to another multidex index must not change detection.
                        out.putNextEntry(ZipEntry(if (entry.name == "classes.dex") "classes9.dex" else entry.name))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                out.putNextEntry(ZipEntry("assets/different-version.txt"))
                out.write("2.23.0718.1700".toByteArray())
                out.closeEntry()
            }
            assertEquals(TeyesVehicleDataLayout.JOYING_2023, FytCodeDetector.layout(listOf(repacked)))
        } finally { repacked.delete() }
    }
}
