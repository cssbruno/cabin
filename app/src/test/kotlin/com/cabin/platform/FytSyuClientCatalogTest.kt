package com.cabin.platform

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class FytSyuClientCatalogTest {
    @Test fun `stock client dispatch supplies profile specific field names for all catalog profiles`() {
        val apk = File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.canbus/190000000_com.syu.canbus.apk")
        assumeTrue(apk.exists())
        val resolver = FytSyuClientCatalog.resolver(listOf(apk))
        val civic = resolver(262442)
        assertTrue(civic.callback.endsWith("Callback_0298_XP1_2015SIYU_CRV;"))
        assertTrue(civic.names.getValue(89).contains("U_CUR_SPEED"))
        assertTrue(civic.names.getValue(137).contains("U_CARINFO_MAINTANCE_OIL_SERVICE_LIFE"))
        assertFalse(civic.names.values.flatten().any { it.endsWith("_BEGIN") || it.endsWith("_END") || it == "U_CNT_MAX" })
        val catalog = File("src/main/assets/syu/profile-catalog.json").readText()
        val profiles = Regex("\"([0-9]+)\"\\s*:").findAll(catalog).map { it.groupValues[1].toInt() }.toSet()
        val results = profiles.associateWith(resolver)
        File("build/reports/fyt-client-coverage.txt").apply { parentFile?.mkdirs() }.writeText(results.entries.joinToString("\n") {
            "${it.key} ${it.value.callback} named=${it.value.names.size}"
        })
        println("SYU client dispatch: ${results.values.count { it.callback.isNotEmpty() }} / ${profiles.size}; names: ${results.values.count { it.names.isNotEmpty() }}")
        assertEquals(3349, profiles.size)
        assertTrue(results.values.count { it.names.isNotEmpty() } > 3000)
        assertTrue(resolver(Int.MAX_VALUE).names.isEmpty())
    }

    @Test fun `installed receiver maintenance uses the stock client distance fields not clock or percent`() {
        val apk = File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.ms/190000000_com.syu.ms.apk")
        assumeTrue(apk.exists())
        val methods = FytCodeDetector.readMethods(listOf(apk))
        val receiver = methods.getValue("Lmodule/canbus/v;->M2([BII)V")
        val signatures = FytBytecodeAnalysis.signatures(FytBytecodeAnalysis.analyze(receiver, 262442, 0x21).publications)
        File("build/reports/fyt-climate-expressions.txt").writeText(signatures.filterKeys { it in setOf(25,31,52,53,54,55,56,57,91,92,93,94,95,96,97) }.entries.joinToString("\n") { "${it.key}: ${it.value}" })
        File("build/reports/fyt-maintenance-expressions.txt").writeText(
            FytBytecodeAnalysis.signatures(FytBytecodeAnalysis.analyze(receiver, 262442, 0x32).publications).entries.joinToString("\n") { "${it.key}: ${it.value}" })
        val clientApk = File("../artifacts/joying-uis7862/extracted/applications/app/190000000_com.syu.canbus/190000000_com.syu.canbus.apk")
        assumeTrue(clientApk.exists())
        val profile = FytCodeDetector.detectMethods(methods, setOf(262442), FytSyuClientCatalog.resolver(listOf(clientApk))).getValue(262442)
        assertEquals(135, profile.fields[179]); assertEquals(136, profile.fields[180]); assertEquals(137, profile.fields[181])
        val normalized = profile.normalize(mapOf(1000 to 262442, 135 to 1, 136 to 1, 137 to 2500, 181 to 13))
        assertEquals(2500, normalized[181]); assertEquals(1, normalized[179]); assertEquals(1, normalized[180])
        assertFalse(normalized.containsKey(137))
        assertEquals(25, profile.fields[25]); assertEquals(31, profile.fields[31]); assertEquals(52, profile.fields[52])
        assertEquals(94, profile.fields[94]); assertEquals(97, profile.fields[97])
        val climate = profile.normalize(mapOf(1000 to 262442, 25 to 44, 31 to -3, 52 to -2, 33 to 0, 94 to 2))
        assertEquals(44, climate[25]); assertEquals(-3, climate[31]); assertEquals(-2, climate[52]); assertEquals(2, climate[94])
        assertFalse(profile.normalize(mapOf(1000 to 262442, 25 to 44)).containsKey(25))
        assertFalse(profile.normalize(mapOf(1000 to 262442, 137 to 2500)).containsKey(181))
    }

    @Test fun `missing or unreadable client supplies no guessed names`() {
        assertTrue(FytSyuClientCatalog.resolver(emptyList())(262442).names.isEmpty())
        val file = File.createTempFile("fyt-client", ".apk")
        try {
            file.writeText("invalid")
            assertTrue(FytSyuClientCatalog.resolver(listOf(file))(262442).names.isEmpty())
        } finally { file.delete() }
    }
}
