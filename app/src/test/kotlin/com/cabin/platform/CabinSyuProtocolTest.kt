package com.cabin.platform

import java.io.File
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CabinSyuProtocolTest {
    @Test fun `WC GM settings require enabled capability bytes and send only target values`() {
        val gm = registry().profile(36).syuClient.display as CabinSyuDecoder
        val raw = CabinGmWcSettings.fields.associateWith { 0x100 }
        assertEquals(28, gm.read(raw).count { it.options.isNotEmpty() })
        assertEquals(4 to listOf(3, 3), gm.command(21, 3, raw))
        assertEquals(5 to listOf(3, 2), gm.command(29, 2, raw))
        assertEquals(7 to listOf(2, 3), gm.command(44, 3, raw))
        assertEquals(6 to listOf(10, 2), gm.command(40, 2, raw))
        assertEquals(6 to listOf(9, 1), gm.command(41, 1, raw))
        assertEquals(6 to listOf(12, 1), gm.command(70, 1, raw))
        assertEquals("Off", gm.read(mapOf(32 to 0x103)).single().text)
        assertEquals("Driver door", gm.read(mapOf(34 to 0x100)).single().text)
        assertNull(gm.command(32, 0, mapOf(32 to 3)))
        assertNull(gm.command(44, 4, raw))
        assertNull(gm.command(44, 1, mapOf(44 to 1)))
        assertNull(gm.command(44, 1, mapOf(44 to 0x201)))
        assertNull(gm.command(44, 1, mapOf(44 to 0x104)))
        assertEquals("120 s", gm.read(mapOf(44 to 0x103)).single().text)
        assertTrue(gm.read(mapOf(44 to 0, 43 to 1, 27 to 0x201)).isEmpty())
        assertNull((registry().profile(61).syuClient.display as CabinSyuDecoder).command(44, 1, raw))
    }

    @Test fun `WC GM basic information uses verified fields and decimal scales`() {
        val gm = registry().profile(36).syuClient.display as CabinSyuDecoder
        val rows = gm.read(mapOf(9 to 85, 13 to 60, 107 to 2400, 144 to 1, 145 to 126, 146 to 123456, 147 to 75)).associateBy { it.viewId }
        assertEquals("8.5 L/100 km", rows[9]?.text)
        assertEquals("60 km/h", rows[13]?.text)
        assertEquals("2400 RPM", rows[107]?.text)
        assertEquals("Engaged", rows[144]?.text)
        assertEquals("12.6 V", rows[145]?.text)
        assertEquals("123456 km", rows[146]?.text)
        assertEquals("-2.5°C", rows[147]?.text)
        assertEquals("0.0°C", gm.read(mapOf(147 to 80)).single().text)
        assertEquals(setOf(13, 107), gm.motionFields)
        assertTrue(gm.cachedRefreshExcludedFields.containsAll(listOf(13, 107)))
        assertTrue(gm.read(mapOf(9 to 0, 13 to 0, 107 to 0, 145 to 0, 146 to 0)).isEmpty())
        assertTrue((registry().profile(61).syuClient.display as CabinSyuDecoder).motionValues(mapOf(13 to 60)).isEmpty())
    }

    @Test fun `Ford media decodes string presets packed time and source-specific commands`() {
        val media = registry().profile(1048910).syuClient.display as CabinSyuDecoder
        val radio = mapOf(113 to 1, 114 to 1, 115 to 8805, 131 to 2)
        val readings = media.readPayloads(radio, mapOf(116 to FytRawSample(strings = listOf("88.05")))).associateBy { it.viewId }
        assertEquals("88.05 MHz", readings[115]?.text)
        assertEquals("88.05", readings[116]?.text)
        assertEquals(9 to listOf(169, 4), media.command(114, 16, radio))
        assertEquals(9 to listOf(169, 15), media.command(131, 6, radio))
        assertEquals(9 to listOf(169, 0), media.releaseFrame(131))
        assertNull(media.command(114, 4, radio))
        assertNull(media.command(127, 1, radio + (127 to 0)))
        val cd = mapOf(113 to 2, 122 to 3, 123 to 12, 124 to 0x010203, 125 to 0x021e05,
            126 to 2, 127 to 1, 128 to 0, 129 to 1)
        val cdRows = media.read(cd).associateBy { it.viewId }
        assertEquals("3 / 12", cdRows[122]?.text)
        assertEquals("01:02:03", cdRows[124]?.text)
        assertEquals("02:30:05", cdRows[125]?.text)
        assertEquals(9 to listOf(169, 35), media.command(127, 0, cd))
        assertEquals(9 to listOf(169, 39), media.command(128, 1, cd))
        assertEquals(9 to listOf(169, 38), media.command(129, 0, cd))
        assertNull(media.command(128, 2, cd))
        assertNull(media.command(114, 1, cd + (114 to 16)))
        assertFalse(media.read(cd + (124 to 0x013c00)).any { it.viewId == 124 })
        assertTrue(CabinFordMedia.read(334, radio, emptyMap(), false).isEmpty())
        assertFalse(CabinFordMedia.read(1900878, radio, emptyMap(), false).any { it.viewId == 115 })
    }

    @Test fun `Explorer seats distinguish mode choices massage levels and momentary support`() {
        val seat = registry().profile(590158).syuClient.display as CabinSyuDecoder
        assertEquals(11 to listOf(167, 10, 2), seat.command(100, 2, mapOf(100 to 0)))
        assertEquals(11 to listOf(167, 6, 1), seat.command(105, 1, mapOf(100 to 2, 105 to 0)))
        assertNull(seat.command(105, 1, mapOf(100 to 1, 105 to 0)))
        assertNull(seat.command(105, 3, mapOf(100 to 2, 105 to 0)))
        assertEquals(11 to listOf(167, 0, 1), seat.command(102, 1, mapOf(100 to 1, 102 to 5)))
        assertEquals(11 to listOf(167, 0, 0), seat.releaseFrame(102))
        assertEquals(11 to listOf(167, 5, 2), seat.command(109, 2, mapOf(101 to 1, 109 to 5)))
        assertEquals(11 to listOf(167, 5, 0), seat.releaseFrame(109))
        assertNull(seat.command(102, 1, mapOf(102 to 5)))
        assertNull(seat.releaseFrame(105))
        val rows = seat.read(mapOf(100 to 1, 101 to 2, 102 to 5, 105 to 1, 107 to 7, 110 to 2))
        assertTrue(rows.any { it.viewId == 102 && it.text == "5" && it.options.size == 2 })
        assertTrue(rows.any { it.viewId == 110 && it.text == "High" })
        assertFalse(rows.any { it.viewId in setOf(105, 107) })
        assertTrue(seat.initialReadRequests(CabinFordSeats.fields).contains(0 to listOf(100, 0)))
        assertNull(CabinFordSeats.command(334, 100, 1, mapOf(100 to 0)))
    }

    @Test fun `Ford amplifier preserves factory ranges and separate volume command`() {
        val amp = registry().profile(1048910).syuClient.display as CabinSyuDecoder
        val rows = amp.read((91..98).associateWith { 0 } + (130 to 1)).associateBy { it.viewId }
        assertEquals("-7", rows[91]?.text)
        assertEquals("Driver", rows[130]?.text)
        assertEquals(8 to listOf(0, 14), amp.command(91, 14, mapOf(91 to 7)))
        assertEquals(8 to listOf(7, 1), amp.command(130, 1, mapOf(130 to 0)))
        assertEquals(7 to listOf(30), amp.command(98, 30, mapOf(98 to 15)))
        assertNull(amp.command(91, 15, mapOf(91 to 7)))
        assertNull(amp.command(96, 4, mapOf(96 to 0)))
        assertNull(amp.command(98, 5, emptyMap()))
        assertNull(CabinFordAmplifier.command(334, 91, 5, mapOf(91 to 7)))
        assertTrue(amp.initialReadRequests(CabinFordAmplifier.fields).contains(0 to listOf(98, 0)))
    }

    @Test fun `Ford trip values use packet scales and Transit average speed is not live speed`() {
        val ford = registry().profile(334).syuClient.display as CabinSyuDecoder
        val transit = registry().profile(1114446).syuClient.display as CabinSyuDecoder
        val readings = transit.read(mapOf(74 to 123456, 75 to 500, 76 to 37, 77 to 81,
            176 to 45, 177 to 123, 178 to 90, 179 to 150)).associateBy { it.viewId }
        assertEquals("123456 km", readings[74]?.text)
        assertEquals("500 km", readings[75]?.text)
        assertEquals("7.5 L/100 km", readings[76]?.text)
        assertEquals("40 L", readings[77]?.text)
        assertEquals("45 km/h", readings[176]?.text)
        assertEquals("Average speed", readings[176]?.label)
        assertEquals("61 km", readings[177]?.text)
        assertEquals("90%", readings[178]?.text)
        assertEquals("12.3 V", readings[179]?.text)
        assertFalse(ford.read(mapOf(176 to 45)).any { it.viewId == 176 })
        assertTrue(CabinFordTrip.read(590158, mapOf(74 to 100), false).isEmpty())
        assertTrue(CabinFordTrip.read(334, mapOf(75 to 1000, 76 to 150, 77 to 202), false).isEmpty())
        assertTrue(CabinFordTrip.read(1114446, mapOf(176 to 255, 178 to 101), false).isEmpty())
        assertTrue(transit.initialReadRequests((74..179).toSet()).containsAll(
            listOf(0 to listOf(99, 0), 0 to listOf(66, 0), 0 to listOf(105, 0))))
    }

    @Test fun `earlier Ford settings preserve paired ambient values and select their own command families`() {
        val ford = registry().profile(334).syuClient.display as CabinSyuDecoder
        val alternate = registry().profile(459086).syuClient.display as CabinSyuDecoder
        assertEquals(4 to listOf(3, 80), ford.command(57, 3, mapOf(57 to 1, 58 to 80)))
        assertEquals(4 to listOf(1, 60), ford.command(58, 60, mapOf(57 to 1, 58 to 80)))
        assertNull(ford.command(57, 3, mapOf(57 to 1)))
        assertNull(ford.command(58, 60, mapOf(58 to 80)))
        assertNull(ford.command(57, 8, mapOf(57 to 1, 58 to 80)))
        assertEquals(1 to listOf(163, 19), ford.command(45, 1, mapOf(45 to 0)))
        assertEquals(1 to listOf(163, 20), ford.command(45, 0, mapOf(45 to 1)))
        assertEquals(1 to listOf(173, 1), ford.command(43, 1, mapOf(43 to 0)))
        assertNull(ford.command(43, 2, mapOf(43 to 0)))
        assertEquals(1 to listOf(254, 1), ford.command(180, 1, mapOf(180 to 0)))
        assertEquals(5 to listOf(0, 2), alternate.command(64, 2, mapOf(64 to 0)))
        assertNull(ford.command(64, 2, mapOf(64 to 0)))
        assertNull(alternate.command(45, 1, mapOf(45 to 0)))
        assertTrue(alternate.read(mapOf(45 to 1)).none { 45 in it.fields })
        assertTrue(ford.read(mapOf(41 to 1, 43 to 2)).none { 41 in it.fields || 43 in it.fields })
        assertEquals("3 flashes", ford.read(mapOf(44 to 1)).single { it.viewId == 44 }.text)
        assertEquals("15 min", alternate.read(mapOf(70 to 2)).single { it.viewId == 70 }.text)
        assertTrue(ford.read(mapOf(57 to 1)).single { it.viewId == 57 }.options.isEmpty())
        assertEquals(25, ford.choices.getValue(FytVehicleChoice.LANGUAGE).size)
        assertEquals(11 to listOf(164, 23, 0), ford.choiceFrame(FytVehicleChoice.LANGUAGE, 23))
        assertNull(ford.choiceFrame(FytVehicleChoice.LANGUAGE, 13))
        assertTrue((registry().profile(917838).syuClient.display as CabinSyuDecoder).choices.isEmpty())
    }

    @Test fun `Escape and Transit settings preserve sparse units and discrete lighting delays`() {
        for (profile in listOf(917838, 1114446)) {
            val decoder = registry().profile(profile).syuClient.display as CabinSyuDecoder
            assertEquals(10 to listOf(113, 3), decoder.command(173, 3, mapOf(173 to 0)))
            assertNull(decoder.command(173, 1, mapOf(173 to 0)))
            assertNull(decoder.command(173, 3, mapOf(173 to 1)))
            assertEquals(10 to listOf(33, 120), decoder.command(147, 120, mapOf(147 to 10)))
            assertNull(decoder.command(147, 30, mapOf(147 to 10)))
            assertEquals("120 s", decoder.read(mapOf(147 to 120)).single { it.viewId == 147 }.text)
            assertEquals("All doors", decoder.read(mapOf(157 to 0)).single { it.viewId == 157 }.text)
            assertEquals("kPa", decoder.read(mapOf(172 to 1)).single { it.viewId == 172 }.text)
            assertEquals(10 to listOf(32, 100), decoder.command(146, 100, mapOf(146 to 50)))
            assertNull(decoder.command(146, 101, mapOf(146 to 50)))
            assertNull(decoder.command(146, 100, emptyMap()))
            assertEquals(10 to listOf(19, 1), decoder.command(144, 1, mapOf(144 to 0)))
            assertTrue(decoder.initialReadRequests((133..174).toSet()).contains(0 to listOf(40, 0)))
            assertFalse(decoder.initialReadRequests(setOf(133)).contains(0 to listOf(40, 0)))
            assertEquals(42, decoder.read((133..174).associateWith { 0 }).count { it.viewId in 133..174 })
        }
        val otherFord = registry().profile(334).syuClient.display as CabinSyuDecoder
        assertNull(otherFord.command(144, 1, mapOf(144 to 0)))
        assertFalse(otherFord.initialReadRequests((0..200).toSet()).contains(0 to listOf(40, 0)))
    }

    @Test fun `WC charging uses nineteen bytes and preserves each record without RZC global aliases`() {
        val decoder = registry().profile(655377).syuClient.display as CabinSyuDecoder
        val raw = mutableMapOf(404 to 1, 405 to 0, 406 to 1)
        for (start in listOf(407, 426, 445)) {
            for (offset in (0..17).filter { it != 6 }) raw[start + offset] = 0
            raw[start] = 7; raw[start + 1] = 35; raw[start + 2] = 1
            raw[start + 3] = 1; raw[start + 5] = 1; raw[start + 7] = 1
            raw[start + 14] = 22; raw[start + 15] = 1; raw[start + 16] = 6
        }
        val unchanged = listOf(7, 35, 160, 45, 12, 192)
        for ((index, start) in listOf(407, 426, 445).withIndex()) {
            val expected = mutableListOf(160 or listOf(12, 20, 24)[index])
            repeat(3) { slot -> expected += if (slot == index) listOf(9, 35, 160, 45, 12, 192) else unchanged }
            assertEquals(147 to expected, decoder.command(start, 9, raw))
            for (missing in raw.keys) assertNull(decoder.command(start, 9, raw - missing))
        }
        assertEquals(147 to (listOf(252) + unchanged + unchanged + unchanged), decoder.command(405, 1, raw))
        assertNull(decoder.command(422, 30, raw))
        assertEquals("30", decoder.read(raw).single { it.viewId == 422 }.text)
        assertEquals(setOf(0, 1), decoder.read(raw).single { it.viewId == 422 }.options.keys)
        assertTrue(decoder.read(mapOf(422 to 30)).none { it.viewId == 422 })
        assertNull(decoder.command(413, 2, raw))
        assertNull(decoder.command(425, 90, raw))
    }

    @Test fun `RZC charging records preserve all companions across all three schedules`() {
        val decoder = registry().profile(655520).syuClient.display as CabinSyuDecoder
        val values = listOf(7, 35, 1, 1, 0, 1, 2, 1, 0, 1, 0, 1, 0, 1, 22, 30, 6, 0, 80)
        for ((slot, start) in listOf(407, 426, 445).withIndex()) {
            val raw = values.mapIndexed { i, value -> start + i to value }.toMap()
            assertEquals(143 to listOf(slot + 1, 9, 35, 210, 170, 22, 30, 6, 0, 80), decoder.command(start, 9, raw))
            assertEquals(143 to listOf(slot + 1, 7, 35, 210, 170, 22, 30, 6, 0, 90), decoder.command(start + 18, 90, raw))
            for (missing in raw.keys) assertNull(decoder.command(start, 9, raw - missing))
            assertNull(decoder.command(start, 24, raw))
            assertNull(decoder.command(start + 1, 59, raw))
            assertNull(decoder.command(start + 15, 15, raw))
            assertNull(decoder.command(start + 18, 101, raw))
            assertNull(decoder.command(start, 9, raw + (start + 3 to 2)))
            val partial = decoder.read(mapOf(start to 7)).single { it.viewId == start }
            assertTrue(partial.options.isEmpty())
            assertFalse(decoder.read(raw).single { it.viewId == start }.options.isEmpty())
        }
        assertEquals(142 to listOf(0, 7), decoder.command(405, 1, mapOf(404 to 1, 405 to 0, 406 to 1)))
        assertNull(decoder.command(405, 1, mapOf(405 to 0)))
        assertNull((registry().profile(655377).syuClient.display as CabinSyuDecoder).command(407, 9, values.mapIndexed { i,v -> 407+i to v }.toMap()))
    }

    @Test fun `RZC hybrid readings use its own scale and temperature encoding`() {
        val decoder = registry().profile(655520).syuClient.display as CabinSyuDecoder
        val readings = decoder.read(mapOf(373 to 1234, 378 to 75, 360 to 125, 464 to 9, 465 to 5)).associateBy { it.viewId }
        assertEquals("123 km", readings.getValue(373).text)
        assertEquals("75%", readings.getValue(378).text)
        assertEquals("22.5 °C", readings.getValue(360).text)
        assertEquals("09:05", readings.getValue(464).text)
        assertEquals("LO", decoder.read(mapOf(360 to 59)).single { it.viewId == 360 }.text)
        assertEquals("HI", decoder.read(mapOf(360 to 196)).single { it.viewId == 360 }.text)
        assertTrue(decoder.read(mapOf(378 to 255, 373 to 65535, 464 to 9)).isEmpty())
    }

    @Test fun `RZC ambient scene and two colors use exact profile and bit encoding`() {
        fun decoder(profile: Int) = registry().profile(profile).syuClient.display as CabinSyuDecoder
        val scene = decoder(3342496)
        assertEquals(160 to listOf(78, 10), scene.command(401, 10, mapOf(401 to 1)))
        assertEquals(160 to listOf(78, 138), scene.command(402, 10, mapOf(402 to 1)))
        assertEquals(160 to listOf(101, 8), scene.command(400, 8, mapOf(400 to 1)))
        assertNull(scene.command(400, 2, mapOf(400 to 1)))
        assertEquals(160 to listOf(101, 3), scene.command(400, 3, mapOf(400 to 2)))
        val off = scene.read(mapOf(400 to 2)).single { it.viewId == 400 }
        assertEquals("Off", off.text)
        assertFalse(off.options.containsKey(2))
        assertNull(scene.command(402, 11, mapOf(402 to 1)))
        assertNull(scene.command(402, 10, emptyMap()))
        assertNull(scene.command(339, 1, mapOf(339 to 0)))
        assertNull(decoder(1310880).command(402, 10, mapOf(402 to 1)))
        assertNull(decoder(17).command(402, 10, mapOf(402 to 1)))
        val three = decoder(2818208)
        assertEquals(160 to listOf(76, 4), three.command(372, 4, mapOf(372 to 1)))
        assertNull(three.command(372, 2, mapOf(372 to 1)))
        assertEquals(160 to listOf(77, 100), three.command(395, 100, mapOf(395 to 53)))
        assertEquals("53%", three.read(mapOf(395 to 53)).single { it.viewId == 395 }.text)
        assertNull(decoder(1310880).command(395, 100, mapOf(395 to 53)))
        assertEquals(160 to listOf(74, 2), decoder(1310880).command(339, 2, mapOf(339 to 0)))
        assertEquals(160 to listOf(75, 3), decoder(1310880).command(385, 3, mapOf(385 to 1)))
    }

    @Test fun `Golf ambient colors preserve WC numbering and RZC profile limits`() {
        fun decoder(profile: Int) = registry().profile(profile).syuClient.display as CabinSyuDecoder
        val wc = decoder(17)
        val rzc = decoder(1310880)
        assertEquals("Color 1", wc.read(mapOf(139 to 260)).single { it.viewId == 139 }.text)
        assertEquals("Color 30", wc.read(mapOf(139 to 289)).single { it.viewId == 139 }.text)
        assertEquals("Red", rzc.read(mapOf(139 to 4)).single { it.viewId == 139 }.text)
        assertEquals(105 to listOf(12, 33), wc.command(139, 33, mapOf(139 to 257)))
        assertNull(wc.command(139, 34, mapOf(139 to 257)))
        assertNull(wc.command(139, 4, mapOf(139 to 1)))
        assertNull(rzc.command(139, 11, mapOf(139 to 1)))
        assertEquals(105 to listOf(12, 20), decoder(3604640).command(139, 20, mapOf(139 to 1)))
        assertNull(decoder(3604640).command(139, 21, mapOf(139 to 1)))
        assertNull(decoder(3342496).command(139, 1, mapOf(139 to 1)))
        assertTrue(decoder(3342496).read(mapOf(139 to 1)).none { 139 in it.fields })
        assertTrue(wc.read(mapOf(139 to 511)).none { 139 in it.fields })
        assertTrue(rzc.read(mapOf(139 to 255)).none { 139 in it.fields })
    }

    @Test fun `Golf additional lighting honors dialect availability and exact command selectors`() {
        val wc = registry().profile(17).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(1310880).syuClient.display as CabinSyuDecoder
        val fields = mapOf(140 to 13, 141 to 14, 142 to 1, 143 to 2)
        for ((field, selector) in fields) {
            val target = if (field <= 141) 70 else 1
            assertEquals(105 to listOf(selector, target), wc.command(field, target, mapOf(field to 256)))
            assertEquals(105 to listOf(selector, target), rzc.command(field, target, mapOf(field to 0)))
            assertNull(wc.command(field, target, mapOf(field to 0)))
            assertNull(wc.command(field, target, emptyMap()))
            assertNull(rzc.command(field, 101, mapOf(field to 0)))
        }
        assertEquals(105 to listOf(15, 60), wc.command(267, 60, mapOf(267 to 306)))
        assertEquals(105 to listOf(15, 60), rzc.command(144, 60, mapOf(144 to 50)))
        assertNull(wc.command(144, 60, mapOf(144 to 306)))
        assertNull(rzc.command(267, 60, mapOf(267 to 306)))
        assertTrue(wc.read(mapOf(144 to 306)).none { 144 in it.fields })
        assertTrue(rzc.read(mapOf(267 to 306)).none { 267 in it.fields })
        assertEquals("70%", wc.read(mapOf(140 to 326)).single { it.viewId == 140 }.text)
        assertEquals(133 to listOf(6), rzc.command(235, 6, mapOf(235 to 3)))
        assertNull(rzc.command(235, 7, mapOf(235 to 3)))
        assertEquals(135 to listOf(5), wc.command(258, 5, mapOf(258 to 257)))
        assertNull(wc.command(258, 0, mapOf(258 to 257)))
        assertNull(rzc.command(258, 5, mapOf(258 to 1)))
        assertTrue(wc.read(mapOf(140 to 511, 142 to 258, 235 to 263, 258 to 256)).isEmpty())
    }

    @Test fun `Honda EV reset timing and RZC door mode use actual meanings`() {
        val ev = registry().profile(1966378).syuClient.display as CabinSyuDecoder
        assertEquals("When charging", ev.read(mapOf(58 to 0)).single { it.viewId == 58 }.text)
        val rzc = registry().profile(786730).syuClient.display as CabinSyuDecoder
        val mode = rzc.read(mapOf(65 to 0)).single { it.viewId == 65 }
        assertEquals(105 to listOf(48, 0), rzc.actionFrame(FytVehicleAction.INITIALIZE_PANORAMA))
        assertNull((registry().profile(321).syuClient.display as CabinSyuDecoder).actionFrame(FytVehicleAction.INITIALIZE_PANORAMA))
        assertEquals("Driver door", mode.text)
        assertNull(mode.checked)
        assertEquals(105 to listOf(9, 1), rzc.command(65, 1, mapOf(65 to 0)))
    }

    @Test fun `language choices preserve WC and RZC wire numbering without feedback`() {
        val wc = registry().profile(321).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(786730).syuClient.display as CabinSyuDecoder
        assertEquals(3, wc.choices.getValue(FytVehicleChoice.LANGUAGE).size)
        assertEquals(33, rzc.choices.getValue(FytVehicleChoice.LANGUAGE).size)
        assertEquals(112 to listOf(1, 1), wc.choiceFrame(FytVehicleChoice.LANGUAGE, 1))
        assertEquals(105 to listOf(85, 15), rzc.choiceFrame(FytVehicleChoice.LANGUAGE, 15))
        assertEquals("Slovenčina", rzc.choices.getValue(FytVehicleChoice.LANGUAGE)[31])
        assertNull(wc.choiceFrame(FytVehicleChoice.LANGUAGE, 0))
        assertNull(rzc.choiceFrame(FytVehicleChoice.LANGUAGE, 33))
        assertTrue((registry().profile(17).syuClient.display as CabinSyuDecoder).choices.isEmpty())
        assertTrue(rzc.read(emptyMap()).isEmpty())
    }

    private fun registry() = FytProtocolRegistry.parse(File("src/main/assets/syu/protocols-2023.json").readText())

    @Test fun `RZC extra settings distinguish reverse tone from BNR selector and invalid values`() {
        val rzc = registry().profile(786730).syuClient.display as CabinSyuDecoder
        val bnr = registry().profile(590122).syuClient.display as CabinSyuDecoder
        assertEquals(105 to listOf(50, 1), rzc.command(109, 1, mapOf(109 to 0)))
        assertEquals(105 to listOf(36, 1), bnr.command(109, 1, mapOf(109 to 0)))
        assertEquals(1, rzc.read(mapOf(109 to 1)).count { it.viewId == 109 })
        assertEquals(105 to listOf(44, 1), rzc.command(156, 1, mapOf(156 to 0)))
        assertNull(bnr.command(156, 1, mapOf(156 to 0)))
        assertNull(rzc.command(156, 1, mapOf(156 to 255)))
        assertNull(rzc.command(156, 2, mapOf(156 to 0)))
        assertEquals(105 to listOf(97, 0), rzc.command(196, 0, mapOf(196 to 1)))
        assertEquals(105 to listOf(79, 3), rzc.command(193, 3, mapOf(193 to 0)))
        assertNull(rzc.command(193, 4, mapOf(193 to 0)))
        assertEquals("+15 km/h", rzc.read(mapOf(193 to 3)).single { it.viewId == 193 }.text)
        assertEquals("Automatic", rzc.read(mapOf(177 to 1)).single { it.viewId == 177 }.text)
        assertNull(rzc.read(mapOf(177 to 1)).single { it.viewId == 177 }.checked)
        assertEquals(105 to listOf(82, 1), rzc.command(197, 1, mapOf(197 to 0)))
    }

    @Test fun `Honda compass bounds zones and cannot send calibration on WC`() {
        val decoder = registry().profile(262442).syuClient.display as CabinSyuDecoder
        assertEquals(102 to listOf(15), decoder.command(18, 15, mapOf(18 to 1)))
        assertNull(decoder.command(18, 0, mapOf(18 to 1)))
        assertNull(decoder.command(18, 16, mapOf(18 to 1)))
        assertNull(decoder.command(18, 1, emptyMap()))
        assertEquals(104 to listOf(1), decoder.command(50, 1, mapOf(50 to 0)))
        assertEquals(103 to emptyList<Int>(), decoder.actionFrame(FytVehicleAction.CALIBRATE_COMPASS))
        val wc = registry().profile(321).syuClient.display as CabinSyuDecoder
        assertNull(wc.actionFrame(FytVehicleAction.CALIBRATE_COMPASS))
        assertEquals(102 to listOf(4, 1), wc.command(50, 1, mapOf(50 to 0)))
        val bnr = registry().profile(590122).syuClient.display as CabinSyuDecoder
        assertNull(bnr.actionFrame(FytVehicleAction.CALIBRATE_COMPASS))
        val rzc = registry().profile(786730).syuClient.display as CabinSyuDecoder
        assertEquals(105 to listOf(14, 0), rzc.actionFrame(FytVehicleAction.RESET_SERVICE_INTERVAL))
        assertEquals(105 to listOf(15, 0), rzc.actionFrame(FytVehicleAction.RESET_VEHICLE_SETTINGS))
        assertEquals(105 to listOf(17, 0), rzc.actionFrame(FytVehicleAction.CALIBRATE_TIRE_PRESSURE))
        assertNull(decoder.actionFrame(FytVehicleAction.RESET_SERVICE_INTERVAL))
    }

    @Test fun `invalid native Honda values do not fall back to unrelated enum rendering`() {
        val fallback = FytSyuDisplay { listOf(FytSyuReading("reference", 25, setOf(25), text = "wrong")) }
        val decoder = CabinSyuDecoder(262442, "honda_0298", enums = fallback)
        assertTrue(decoder.read(mapOf(25 to 42, 33 to 255)).none { 25 in it.fields })
    }

    @Test fun `Honda panorama uses its rear feedback rather than the stock erroneous field`() {
        val decoder = registry().profile(786730).syuClient.display as CabinSyuDecoder
        assertEquals(105 to listOf(54, 3), decoder.command(168, 3, mapOf(168 to 2, 105 to 255)))
        assertNull(decoder.command(168, 3, mapOf(105 to 2)))
        assertNull(decoder.command(168, 4, mapOf(168 to 2)))
        assertEquals("Wide rear view", decoder.read(mapOf(168 to 2)).single { it.viewId == 168 }.text)
        assertEquals(105 to listOf(58, 1), decoder.command(172, 1, mapOf(172 to 0)))
        assertNull((registry().profile(262442).syuClient.display as CabinSyuDecoder).command(168, 3, mapOf(168 to 2)))
    }

    @Test fun `Honda RZC grouped voice commands preserve every other fresh value`() {
        val decoder = registry().profile(3014954).syuClient.display as CabinSyuDecoder
        val values = mapOf(227 to 1, 228 to 0, 229 to 1, 230 to 1, 231 to 0)
        assertEquals(152 to listOf(1, 1, 1, 1, 0), decoder.command(228, 1, values))
        assertNull(decoder.command(228, 1, values - 231))
        assertNull(decoder.command(228, 1, values + (230 to 255)))
        assertTrue(decoder.read(values - 231).filter { it.viewId in 227..231 }.all { it.options.isEmpty() })
        assertEquals(151 to listOf(2, 3), decoder.command(188, 3, mapOf(188 to 0)))
        assertNull(decoder.command(188, 4, mapOf(188 to 0)))
        val other = registry().profile(262442).syuClient.display as CabinSyuDecoder
        assertNull(other.command(228, 1, values))
    }

    @Test fun `Honda amplifier is restricted to routed profiles and uses bounded native values`() {
        val decoder = registry().profile(590122).syuClient.display as CabinSyuDecoder
        assertEquals(108 to listOf(2, 12), decoder.command(141, 12, mapOf(141 to 9)))
        assertEquals(108 to listOf(9, 40), decoder.command(139, 40, mapOf(139 to 20)))
        assertNull(decoder.command(139, 41, mapOf(139 to 20)))
        assertNull(decoder.command(141, 12, mapOf(141 to 255)))
        assertNull(decoder.command(141, 12, emptyMap()))
        assertEquals("Front 3", decoder.read(mapOf(140 to 6)).single { it.viewId == 140 }.text)
        assertEquals("Right 3", decoder.read(mapOf(141 to 12)).single { it.viewId == 141 }.text)
        assertEquals("-6", decoder.read(mapOf(142 to 0)).single { it.viewId == 142 }.text)
        assertEquals(108 to listOf(10, 0), decoder.actionFrame(FytVehicleAction.RESET_HONDA_AMPLIFIER))
        val other = registry().profile(262442).syuClient.display as CabinSyuDecoder
        assertNull(other.command(141, 12, mapOf(141 to 9)))
        assertNull(other.actionFrame(FytVehicleAction.RESET_HONDA_AMPLIFIER))
        val mapped = decoder.widgetValues(mapOf(122 to 4, 123 to 3, 124 to 2), mapOf(61 to 1, 62 to 2))
        assertEquals(1, mapped[122]); assertEquals(2, mapped[123]); assertNull(mapped[124])
        val wc = registry().profile(393537).syuClient.display as CabinSyuDecoder
        assertTrue(wc.widgetValues(mapOf(201 to 9, 202 to 9), emptyMap()).isEmpty())
    }

    @Test fun `Honda WC shares trip data but never sends RZC commands`() {
        val registry = registry()
        val wc = registry.profile(321).syuClient.display as CabinSyuDecoder
        assertTrue(wc.hasHondaTrip)
        assertTrue(wc.ownsReadRequests)
        assertTrue(wc.initialReadRequests((0..226).toSet()).isEmpty())
        val rows = wc.read(mapOf(1 to 123, 7 to 2, 12 to 12345, 9 to 1, 103 to 2, 104 to 5))
        assertEquals("12.3 L/100 km", rows.single { it.viewId == 1 }.text)
        assertEquals("1234.5 mi", rows.single { it.viewId == 12 }.text)
        assertEquals("2:05", rows.single { it.viewId == 103 }.text)
        assertEquals(102 to listOf(4, 3), wc.command(50, 3, mapOf(50 to 2)))
        assertEquals(103 to listOf(4, 1), wc.command(54, 1, mapOf(54 to 0)))
        assertEquals(104 to listOf(5, 2), wc.command(86, 2, mapOf(86 to 1)))
        assertNull(wc.command(61, 1, mapOf(61 to 0)))
        assertEquals(105 to listOf(4, 2), wc.command(61, 2, mapOf(61 to 1)))
        assertEquals(110 to listOf(12, 5), wc.command(94, 1, mapOf(94 to 0)))
        assertEquals(110 to listOf(12, 0), wc.command(93, 0, mapOf(93 to 1)))
        assertEquals(106 to listOf(2, 3), wc.command(71, 3, mapOf(71 to 1)))
        assertEquals(106 to listOf(1, 7), wc.command(72, 7, mapOf(72 to 4)))
        assertEquals("-3", wc.read(mapOf(72 to 1)).single { it.viewId == 72 }.text)
        assertNull(wc.command(72, 0, mapOf(72 to 4)))
        assertNull(wc.command(109, 1, mapOf(109 to 0)))
        val panel = registry.profile(262465).syuClient.display as CabinSyuDecoder
        assertEquals(106 to listOf(16, 1), panel.command(109, 1, mapOf(109 to 0)))
        assertEquals("Type 3", panel.read(mapOf(111 to 2)).single { it.viewId == 111 }.text)
        assertNull(wc.command(50, 5, mapOf(50 to 2)))
        assertNull(wc.command(53, 0, mapOf(53 to 1)))
        assertNull(wc.command(53, 1, mapOf(53 to 0)))
        assertNull(wc.command(54, 1, emptyMap()))
        assertTrue(wc.read(mapOf(53 to 0)).none { it.viewId == 53 })
        assertEquals(110 to listOf(13, 7), wc.command(105, 1, mapOf(105 to 0)))
        assertEquals(110 to listOf(13, 2), wc.command(107, 0, mapOf(107 to 1)))
        assertEquals(0 to listOf(1), wc.command(47, 1, mapOf(47 to 0)))
        assertEquals(109 to listOf(0), wc.command(85, 0, mapOf(85 to 1)))
        assertEquals(2 to listOf(7, 3), wc.command(91, 3, mapOf(91 to 1)))
        assertNull(wc.command(89, 1, mapOf(89 to 0)))
        val tailgate = registry.profile(393537).syuClient.display as CabinSyuDecoder
        assertEquals(1 to listOf(1, 1), tailgate.command(89, 1, mapOf(89 to 0)))
        assertEquals(1 to listOf(2, 1), tailgate.command(90, 1, mapOf(90 to 0)))
        val restricted = registry.profile(328001).syuClient.display as CabinSyuDecoder
        assertNull(restricted.command(50, 3, mapOf(50 to 2)))
        assertTrue(restricted.read(mapOf(50 to 2)).none { 50 in it.fields })
        assertEquals(setOf(FytVehicleAction.RESET_HONDA_TRIP_HISTORY, FytVehicleAction.RESET_SERVICE_INTERVAL,
            FytVehicleAction.RESET_VEHICLE_SETTINGS, FytVehicleAction.CALIBRATE_TIRE_PRESSURE), wc.actions)
        assertEquals(105 to listOf(6, 1), wc.actionFrame(FytVehicleAction.RESET_SERVICE_INTERVAL))
        assertEquals(105 to listOf(5, 1), wc.actionFrame(FytVehicleAction.RESET_VEHICLE_SETTINGS))
        assertEquals(108 to listOf(0), wc.actionFrame(FytVehicleAction.CALIBRATE_TIRE_PRESSURE))
        assertEquals(101 to listOf(3), wc.actionFrame(FytVehicleAction.RESET_HONDA_TRIP_HISTORY))
        val rzc = registry.profile(262442).syuClient.display as CabinSyuDecoder
        val golf = registry.profile(17).syuClient.display as CabinSyuDecoder
        assertNull(rzc.actionFrame(FytVehicleAction.RESET_HONDA_TRIP_HISTORY))
        assertNull(golf.actionFrame(FytVehicleAction.RESET_HONDA_TRIP_HISTORY))
        assertNull(wc.actionFrame(FytVehicleAction.RESET_TRIP_SINCE_START))
    }

    @Test fun `Honda A and B histories keep independent units and reject both distance sentinels`() {
        val xp = registry().profile(262442).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(786730).syuClient.display as CabinSyuDecoder
        val a = xp.read(mapOf(4 to 12345, 9 to 0, 3 to 87, 8 to 2, 12 to 65535, 14 to 16777215))
        assertEquals("1234.5 km", a.single { it.viewId == 4 }.text)
        assertEquals("8.7 L/100 km", a.single { it.viewId == 3 }.text)
        assertTrue(a.none { it.viewId == 12 || it.viewId == 14 })
        val b = rzc.read(mapOf(213 to 2222, 222 to 1, 214 to 333, 223 to 0, 5 to 400, 221 to 0, 10 to 1))
        assertEquals("222.2 mi", b.single { it.viewId == 213 }.text)
        assertEquals("33.3 mpg", b.single { it.viewId == 214 }.text)
        assertEquals(setOf("400 mi", "400 km"), b.filter { it.viewId == 5 }.mapNotNull { it.text }.toSet())
        assertTrue(xp.read(mapOf(213 to 2222, 222 to 1)).none { it.viewId == 213 })
        assertEquals(listOf(100 to listOf(1), 100 to listOf(2)), xp.initialReadRequests((0..226).toSet()))
        assertEquals(listOf(100 to listOf(1), 100 to listOf(2), 100 to listOf(4)), rzc.initialReadRequests((0..226).toSet()))
    }

    @Test fun `Ford tires use actual pressure warning and temperature fields independently`() {
        val ford = registry().profile(334).syuClient.display as CabinSyuDecoder
        assertTrue(ford.hasFordTires)
        assertEquals("220 kPa", ford.read(mapOf(78 to 80, 180 to 0)).single { it.viewId == 78 }.text)
        assertEquals("31.9 psi", ford.read(mapOf(78 to 80, 180 to 1)).single { it.viewId == 78 }.text)
        assertEquals("2.2 bar", ford.read(mapOf(78 to 80, 180 to 2)).single { it.viewId == 78 }.text)
        assertTrue(ford.read(mapOf(78 to 80)).none { it.viewId == 78 })
        assertTrue(ford.read(mapOf(78 to 255, 180 to 0)).none { it.viewId == 78 })
        assertEquals("20°C", ford.read(mapOf(86 to 80)).single { it.viewId == 86 }.text)
        assertTrue(ford.read(mapOf(86 to 0)).none { it.viewId == 86 })
        val everest = registry().profile(1376590).syuClient.display as CabinSyuDecoder
        assertTrue(everest.read(mapOf(86 to 80)).none { it.viewId == 86 })
        assertEquals("Sensor signal lost", ford.read(mapOf(82 to 6)).single { it.viewId == 82 }.text)
        assertEquals(listOf(0 to listOf(99, 0)), ford.initialReadRequests((78..85).toSet()))
        val values = CabinFordTires.telemetry(334, SyuVehicleTelemetry(), mapOf(78 to 80, 82 to 1, 146 to 120))
        assertEquals(220.0, values.tires[0].pressureKpa!!, 0.0001)
        assertEquals(1, values.tires[0].warning)
        assertNull(values.tires[1].pressureKpa)
        val history = TireHistory(ApplicationProvider.getApplicationContext())
        history.clear(334)
        history.record(TeyesClimateState(connected = true, profileId = 334, syuVehicle = values), 1000, 1000)
        assertEquals(220.0, history.read(334).single().tires[0].pressureKpa!!, 0.0001)
        history.clear(334)
    }

    @Test fun `Honda trip data requires correct units and never reads old consumption fields`() {
        val decoder = registry().profile(262442).syuClient.display as CabinSyuDecoder
        assertTrue(decoder.read(mapOf(1 to 123)).none { it.screen == "honda_trip_2023" && it.viewId == 1 })
        assertEquals("12.3 L/100 km", decoder.read(mapOf(1 to 123, 7 to 2)).single { it.screen == "honda_trip_2023" && it.viewId == 1 }.text)
        assertTrue(decoder.read(mapOf(1 to 65535, 7 to 2)).none { it.screen == "honda_trip_2023" && it.viewId == 1 })
        assertTrue(decoder.read(mapOf(1 to 123, 7 to 3)).none { it.screen == "honda_trip_2023" && it.viewId == 1 })
        assertEquals("250 mi", decoder.read(mapOf(5 to 250, 10 to 1)).single { it.screen == "honda_trip_2023" && it.viewId == 5 }.text)
        assertTrue(decoder.read(mapOf(0 to 255)).none { it.screen == "honda_trip_2023" && it.viewId == 0 })
        assertEquals("21/21", decoder.read(mapOf(0 to 30)).single { it.screen == "honda_trip_2023" && it.viewId == 0 }.text)
        assertEquals("2:05", decoder.read(mapOf(103 to 2, 104 to 5)).single { it.screen == "honda_trip_2023" && it.viewId == 103 }.text)
        assertEquals(listOf(100 to listOf(1)), decoder.initialReadRequests(setOf(1, 2, 7)))
        assertTrue(decoder.initialReadRequests(setOf(61)).isEmpty())
        val old = SyuVehicleTelemetry(averageConsumption = 99.0)
        assertNull(CabinHondaTrip.telemetry(old, mapOf(99 to 990, 105 to 2)).averageConsumption)
        assertEquals(12.3, CabinHondaTrip.telemetry(old, mapOf(1 to 123, 7 to 2)).averageConsumption!!, 0.0001)
    }

    @Test fun `Golf lighting validates ranges and variant traffic direction`() {
        val wc = registry().profile(17).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(1310880).syuClient.display as CabinSyuDecoder
        assertEquals("Left-hand traffic", wc.read(mapOf(45 to 256)).single { it.viewId == 45 }.text)
        assertEquals("Right-hand traffic", rzc.read(mapOf(45 to 0)).single { it.viewId == 45 }.text)
        assertEquals(62 to listOf(100), wc.command(46, 100, mapOf(46 to 306)))
        assertNull(wc.command(46, 101, mapOf(46 to 306)))
        assertNull(wc.command(46, 50, mapOf(46 to 50)))
        assertEquals(65 to listOf(30), rzc.command(49, 30, mapOf(49 to 5)))
        assertNull(rzc.command(49, 35, mapOf(49 to 5)))
        assertEquals("25 s", rzc.read(mapOf(50 to 25)).single { it.viewId == 50 }.text)
        assertEquals(13 to listOf(0), wc.command(78, 0, mapOf(78 to 257)))
    }

    @Test fun `Golf hybrid uses fresh capability and actual battery and charging fields`() {
        val decoder = registry().profile(655377).syuClient.display as CabinSyuDecoder
        assertNull(decoder.command(272, 13, mapOf(272 to 5)))
        assertNull(decoder.command(272, 13, mapOf(271 to 0, 272 to 5)))
        assertEquals(145 to listOf(1, 13), decoder.command(272, 13, mapOf(271 to 128, 272 to 5)))
        assertEquals(145 to listOf(2, 33), decoder.command(273, 33, mapOf(271 to 64, 273 to 32)))
        assertNull(decoder.command(273, 60, mapOf(271 to 64, 273 to 32)))
        assertEquals("16.5 °C", decoder.read(mapOf(271 to 64, 273 to 33)).single { it.viewId == 273 }.text)
        assertEquals("70%", decoder.read(mapOf(303 to 70)).single { it.viewId == 303 }.text)
        assertTrue(decoder.read(mapOf(293 to 120)).none { it.viewId == 293 })
        assertEquals("120 km", decoder.read(mapOf(177 to 1, 293 to 120)).single { it.viewId == 293 }.text)
        assertTrue(decoder.read(mapOf(177 to 1, 293 to 65535)).none { it.viewId == 293 })
        val mapped = CabinGolfHybrid.widgetValues(655377, "golf_wc_2023",
            mapOf(299 to 255, 300 to 255, 321 to 99), mapOf(271 to 128, 272 to 13, 303 to 70, 294 to 2))
        assertEquals(mapOf(299 to 128, 300 to 13, 321 to 70, 312 to 2), mapped)
        val telemetry = SyuVehicleProtocol.decode(655377, mapped)
        assertEquals(70, telemetry.energy?.batteryPercent)
        assertEquals(SyuEnergyDirection.DISCHARGING, telemetry.energy?.direction)
        assertEquals(2, telemetry.factoryControls[SyuFactoryControl.CHARGE_CURRENT])
        assertFalse(SyuFactoryControl.CHARGE_TEMPERATURE in telemetry.factoryControls)
    }

    @Test fun `Golf trip display toggles are settings rather than invented measurements`() {
        val wc = registry().profile(17).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(1310880).syuClient.display as CabinSyuDecoder
        for (field in 56..64) {
            assertEquals((field + 19) to listOf(1), wc.command(field, 1, mapOf(field to 256)))
            assertNull(wc.command(field, 1, mapOf(field to 0)))
            assertEquals((field + 19) to listOf(0), rzc.command(field, 0, mapOf(field to 1)))
        }
        val row = wc.read(mapOf(85 to 257)).single { it.viewId == 85 }
        assertEquals("Display oil temperature", row.label)
        assertEquals("On", row.text)
        assertEquals(0 to listOf(0), wc.command(85, 0, mapOf(85 to 257)))
        assertNull(wc.command(85, 1, mapOf(85 to 285)))
    }

    @Test fun `Golf units map labels and inverted WC command values independently`() {
        val wc = registry().profile(17).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(1310880).syuClient.display as CabinSyuDecoder
        assertEquals("mi", wc.read(mapOf(83 to 0)).single { it.viewId == 83 }.text)
        assertEquals("km", rzc.read(mapOf(83 to 0)).single { it.viewId == 83 }.text)
        assertEquals(1 to listOf(0), wc.command(83, 1, mapOf(83 to 0)))
        assertEquals(1 to listOf(1), rzc.command(83, 1, mapOf(83 to 0)))
        assertEquals("°F", wc.read(mapOf(66 to 256)).single { it.viewId == 66 }.text)
        assertEquals("°C", rzc.read(mapOf(66 to 0)).single { it.viewId == 66 }.text)
        assertEquals(87 to listOf(0), wc.command(66, 1, mapOf(66 to 256)))
        assertNull(wc.command(66, 1, mapOf(66 to 0)))
        assertEquals("gal (US)", wc.read(mapOf(67 to 257)).single { it.viewId == 67 }.text)
        assertEquals("gal (UK)", rzc.read(mapOf(67 to 1)).single { it.viewId == 67 }.text)
        assertEquals("kPa", wc.read(mapOf(69 to 256)).single { it.viewId == 69 }.text)
        assertEquals("bar", rzc.read(mapOf(69 to 0)).single { it.viewId == 69 }.text)
        assertNull(wc.command(68, 4, mapOf(68 to 256)))
        assertNull(wc.command(276, 1, mapOf(276 to 256)))
        val hybrid = registry().profile(655377).syuClient.display as CabinSyuDecoder
        assertEquals(146 to listOf(1), hybrid.command(276, 1, mapOf(276 to 256)))
        val electric = registry().profile(655520).syuClient.display as CabinSyuDecoder
        assertEquals(160 to listOf(150, 1), electric.command(343, 1, mapOf(343 to 0)))
    }

    @Test fun `Golf parking and opening preserve variant values and parameterized commands`() {
        val wc = registry().profile(17).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(1310880).syuClient.display as CabinSyuDecoder
        assertEquals("0", wc.read(mapOf(20 to 256)).single { it.viewId == 20 }.text)
        assertEquals("1", rzc.read(mapOf(20 to 0)).single { it.viewId == 20 }.text)
        assertEquals(40 to listOf(8), wc.command(20, 8, mapOf(20 to 256)))
        assertNull(wc.command(20, 9, mapOf(20 to 256)))
        assertNull(wc.command(20, 2, mapOf(20 to 0)))
        assertEquals(39 to listOf(1), rzc.command(19, 1, mapOf(19 to 0)))
        assertEquals(160 to listOf(58, 1), rzc.command(336, 1, mapOf(336 to 0)))
        assertEquals(160 to listOf(59, 0), rzc.command(337, 0, mapOf(337 to 1)))
        assertNull(rzc.command(335, 1, mapOf(335 to 0)))
        assertEquals(132 to listOf(1), wc.command(234, 1, mapOf(234 to 256)))
        assertEquals("All doors", wc.read(mapOf(40 to 256)).single { it.viewId == 40 }.text)
        assertEquals("Vehicle side", rzc.read(mapOf(40 to 0)).single { it.viewId == 40 }.text)
        assertEquals("All windows", wc.read(mapOf(39 to 256)).single { it.viewId == 39 }.text)
        assertEquals("Off", rzc.read(mapOf(39 to 0)).single { it.viewId == 39 }.text)
        assertEquals(73 to listOf(2), rzc.command(40, 2, mapOf(40 to 0)))
        assertEquals(106 to listOf(5, 1), wc.command(146, 1, mapOf(146 to 256)))
        assertEquals(160 to listOf(117, 0), rzc.command(369, 0, mapOf(369 to 1)))
        assertNull(wc.command(369, 0, mapOf(369 to 257)))
        assertEquals(mapOf(116 to 257, 117 to 264), CabinGolfSettings.widgetValues("golf_wc_2023",
            mapOf(116 to 256, 117 to 256, 118 to 256), mapOf(19 to 257, 20 to 264)))
    }

    @Test fun `Golf registry selects exact variants and owns mirror frames`() {
        val wc = registry().profile(17).syuClient.display as CabinSyuDecoder
        val rzc = registry().profile(1310880).syuClient.display as CabinSyuDecoder
        assertEquals(67 to listOf(1), wc.command(51, 1, mapOf(51 to 256)))
        assertNull(wc.command(51, 1, mapOf(51 to 0)))
        assertEquals(69 to listOf(1), wc.command(53, 1, mapOf(53 to 0)))
        assertEquals(71 to listOf(0), rzc.command(55, 0, mapOf(55 to 1)))
        assertNull(rzc.command(55, 0, mapOf(55 to 257)))
        assertNull(rzc.command(55, 2, mapOf(55 to 1)))
        assertNull(rzc.command(55, 0, emptyMap()))
        assertNull(wc.command(148, 1, mapOf(148 to 257)))
        assertNull(CabinGolfSettings.dialect(262442, "Lcom/syu/module/canbus/Callback_0017_WC2_GaoErFu7;"))
        assertNull(CabinGolfSettings.dialect(17, "Lcom/syu/module/canbus/Callback_0160_RZC_XP1_DaZhong_GaoErFu7;"))
        assertEquals("Mirror synchronization", wc.read(mapOf(51 to 257)).single { it.viewId == 51 }.label)
        assertTrue(wc.read(mapOf(51 to 1)).none { 51 in it.fields })
        assertEquals(mapOf(148 to 257), CabinGolfSettings.widgetValues("golf_wc_2023", mapOf(148 to 0, 149 to 1), mapOf(51 to 257)))
    }

    @Test fun `all catalog profiles use packaged facts without vendor APKs`() {
        val registry = registry()
        val catalog = JSONObject(File("src/main/assets/syu/profile-catalog.json").readText()).getJSONObject("profiles")
        assertEquals(3349, catalog.length())
        for (key in catalog.keys()) assertNotEquals("profile_not_supported", registry.profile(key.toInt()).reason)
        assertEquals("profile_not_supported", registry.profile(Int.MAX_VALUE).reason)
        val honda = registry.profile(262442)
        assertEquals(135, honda.fields[179]); assertEquals(136, honda.fields[180]); assertEquals(137, honda.fields[181])
        assertFalse(89 in honda.fields)
        assertFalse(90 in honda.fields)
    }

    @Test fun `detection requires only service metadata not a readable APK or installed canbus client`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.syu.ms"
            versionName = "2.23.0718.1700"
            applicationInfo = ApplicationInfo().apply { packageName = "com.syu.ms"; sourceDir = "/does-not-exist/vendor.apk" }
        })
        val detected = detectFytFirmware(context)
        assertEquals("2.23.0718.1700", detected.version)
        assertNotNull(detected.resolveProfile)
        assertEquals(137, detected.resolveProfile!!(262442).fields[181])
        assertEquals("", detected.sha256)
        assertFalse(FytProtocolRegistry.supports("2.24.0718.1700"))
        assertFalse(FytProtocolRegistry.supports("unknown"))
    }

    @Test fun `Cabin owns the Honda enums and labels`() {
        val decoder = CabinSyuDecoder(262442, "honda_0298", true)
        val rows = decoder.read(mapOf(58 to 0, 60 to 7, 61 to 2, 71 to 0, 85 to 2, 114 to 1))
        fun value(id: Int) = rows.single { it.viewId == id }.text
        assertEquals("Ao abastecer", value(58))
        assertEquals("2", value(60))
        assertEquals("Médio", value(61))
        assertEquals("Todas as portas", value(71))
        assertEquals("Perto", value(85))
        assertEquals("Aviso visual", value(114))
        assertFalse(decoder.read(mapOf(61 to 20)).any { it.viewId == 61 })
    }

    @Test fun `temperature and maintenance require fresh metadata and keep half degrees`() {
        val decoder = CabinSyuDecoder(262442, "honda_0298")
        assertTrue(decoder.read(mapOf(25 to 45, 137 to 2500)).isEmpty())
        val rows = decoder.read(mapOf(25 to 45, 31 to -3, 33 to 0, 135 to 1, 136 to 1, 137 to 2500, 181 to 13))
        assertEquals("22.5°C", rows.single { it.viewId == 25 }.text)
        assertEquals("HIGH", rows.single { it.viewId == 31 }.text)
        assertEquals("-2500 mi", rows.single { it.viewId == 137 }.text)
        assertFalse(rows.any { it.viewId == 181 })
        assertEquals("50.0000 km/h", CabinSyuDecoder(286, "bagoo_audi").read(mapOf(1 to 800)).single().text)
    }

    @Test fun `own commands preserve the profile specific protocol and reject missing feedback`() {
        val civic = CabinSyuDecoder(262442, "honda_0298")
        assertEquals(105 to listOf(6, 3), civic.command(61, 3, mapOf(61 to 2)))
        assertNull(civic.command(61, 3, emptyMap()))
        assertNull(civic.command(61, 5, mapOf(61 to 2)))
        assertNull(civic.command(61, 3, mapOf(61 to 99)))
        assertEquals(105 to listOf(36, 2), civic.command(114, 2, mapOf(114 to 1)))
        assertEquals(105 to listOf(41, 2), CabinSyuDecoder(983338, "honda_0298").command(114, 2, mapOf(114 to 1)))
        assertNull(civic.command(109, 1, mapOf(109 to 0)))
        assertEquals(105 to listOf(36, 1), CabinSyuDecoder(590122, "honda_0298").command(109, 1, mapOf(109 to 0)))
        assertNull(CabinSyuDecoder(286, "bagoo_audi").command(61, 3, mapOf(61 to 2)))
    }

    @Test fun `finite state tables reject values outside their reviewed range and do not retain expired data`() {
        val table = CabinSyuEnumTable.parse(JSONArray("""[{"field":12,"values":[{"min":0,"max":0,"text":"Closed","pt":"Fechado"},{"min":1,"max":1,"text":"Open","pt":"Aberto"}]}]"""), true)
        assertEquals("Aberto", table.read(mapOf(12 to 1)).single().text)
        assertTrue(table.read(mapOf(12 to 257)).isEmpty())
        assertTrue(table.read(emptyMap()).isEmpty())
    }
}
