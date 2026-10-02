package com.cabin.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VehicleGoalsTest {
    private lateinit var context: Context
    private lateinit var history: TripHistory
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        listOf(TeyesFeaturePreferences::class.java, ProjectionPreferences::class.java, MeasurementPreferences::class.java).forEach { c -> c.getDeclaredField("instance").apply { isAccessible = true }.set(null, null) }
        PortableConfigurationBackup.stores.values.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        history = TripHistory(context)
    }
    private fun trip(start: Long, km: Double = 1.0) = RecordedTrip(start, start + 60_000, km, 60, null)
    @Test fun `inclusive date boundaries respect DST and include entire end date`() {
        val zone = ZoneId.of("America/New_York")
        val range = TripDateRange(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-08"))
        val start = range.start.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = range.end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(23 * 3600_000L, end - start)
        assertTrue(range.contains(start, zone)); assertTrue(range.contains(end - 1, zone)); assertFalse(range.contains(end, zone))
        assertThrows(IllegalArgumentException::class.java) { TripDateRange(range.end, range.start.minusDays(1)) }
    }
    @Test fun `trip metadata survives recorder updates and notes export is explicit`() {
        history.save(17, trip(1_000)); history.annotate(17, 1_000, "business", "=SUM(A1,A2)")
        history.save(17, trip(1_000, 2.0))
        val saved = history.read(17).single()
        assertEquals("business", saved.label); assertEquals("=SUM(A1,A2)", saved.note)
        assertFalse(tripCsvDetailed(listOf(saved), false).contains("SUM"))
        assertTrue(tripCsvDetailed(listOf(saved), true).contains("'="))
    }
    @Test fun `bulk undo restores only selected records and recording switch stops persistence`() {
        (1..3).forEach { history.save(17, trip(it * 1_000L)) }
        val removed = history.deleteSelected(17, setOf(1_000, 2_000))
        history.delete(17, 3_000)
        history.restoreSelected(17, removed)
        assertEquals(setOf(1_000L, 2_000L), history.read(17).map { it.start }.toSet())
        history.setRecording(17, false); history.save(17, trip(4_000))
        assertEquals(2, history.read(17).size)
    }
    @Test fun `estimates retain inputs and use vehicle specific configuration`() {
        history.prefs.edit().putString("fuelRate.17", "10").putString("fuelPrice.17", "5").putString("fuelCurrency.17", "BRL").commit()
        history.save(17, trip(1_000, 100.0)); history.save(18, trip(1_000, 100.0))
        assertEquals(50.0, history.read(17).single().estimatedCost!!, 0.001)
        assertEquals(10.0, history.read(17).single().fuelRate!!, 0.001)
        assertEquals("BRL", history.read(17).single().currency)
        assertNull(history.read(18).single().estimatedCost)
        history.prefs.edit().putString("fuelPrice.17", "6").commit()
        assertEquals(5.0, history.read(17).single().fuelPrice!!, 0.001)
        assertTrue(history.recalculateCosts(17)); assertEquals(6.0, history.read(17).single().fuelPrice!!, 0.001)
    }
    @Test fun `maintenance completion affects only one item and appends correctable ledger`() {
        val ledger = MaintenanceLedger(history.prefs, 17); val day = LocalDate.now()
        ledger.save(MaintenanceItem("oil", "Oil", day, 90)); ledger.save(MaintenanceItem("tires", "Tires", day, 30))
        ledger.complete("oil", "Changed filter", 20.0, day)
        assertEquals(day, ledger.items().first { it.id == "tires" }.due)
        assertEquals(day.plusDays(90), ledger.items().first { it.id == "oil" }.due)
        val entry = ledger.history().single(); ledger.correct(entry.copy(cost = 25.0))
        assertEquals(25.0, ledger.history().single().cost!!, 0.0)
        assertTrue(ledger.historyCsv().contains("Changed filter"))
    }
    @Test fun `completing an item removed during editing fails without creating history`() {
        val ledger = MaintenanceLedger(history.prefs, 17)
        ledger.save(MaintenanceItem("oil", "Oil", LocalDate.now(), 90)); ledger.delete("oil")
        assertThrows(IllegalArgumentException::class.java) { ledger.complete("oil", "Late completion", 10.0) }
        assertTrue(ledger.history().isEmpty()); assertTrue(ledger.items().isEmpty())
    }
    @Test fun `manual odometer rejects decreasing and backdated readings`() {
        val ledger = MaintenanceLedger(history.prefs, 17); val day = LocalDate.now()
        ledger.recordOdometer(ManualOdometer(day, 1000.0))
        assertThrows(IllegalArgumentException::class.java) { ledger.recordOdometer(ManualOdometer(day, 999.0)) }
        assertThrows(IllegalArgumentException::class.java) { ledger.recordOdometer(ManualOdometer(day.minusDays(1), 1001.0)) }
        assertEquals(1000.0, ledger.odometer()!!.kilometers, 0.0)
    }
    @Test fun `only fresh matching feedback confirms a command and no timeout assumes success`() {
        val tracker = ClimateCommandTracker()
        tracker.begin(17, 100, mapOf(24 to 1))
        assertEquals(ClimateCommandStatus.WAITING, tracker.observe(17, 200, mapOf(24 to 1), mapOf(24 to 99)))
        assertEquals(ClimateCommandStatus.CONFIRMED, tracker.observe(17, 300, mapOf(24 to 1), mapOf(24 to 250)))
        tracker.begin(17, 400, mapOf(24 to 0))
        assertEquals(ClimateCommandStatus.TIMED_OUT, tracker.observe(17, 5_400, emptyMap(), emptyMap()))
        tracker.begin(17, 10_000, mapOf(24 to 0))
        assertEquals(ClimateCommandStatus.CANCELLED, tracker.observe(18, 10_100, mapOf(24 to 0), mapOf(24 to 10_050)))
    }
    @Test fun `each telemetry field retains its own receipt time`() {
        val samples = TeyesTelemetryFreshness(); samples.update(89, 40, 100); samples.update(90, 1000, 2000)
        assertEquals(100L, samples.receivedTimes()[89]); assertEquals(2000L, samples.receivedTimes()[90])
        assertFalse(89 in samples.snapshot(5_101)); assertTrue(90 in samples.snapshot(5_101))
    }
    @Test fun `parking expiry removes position timestamp accuracy and note only for due profile`() {
        val prefs = automationPreferences(context)
        prefs.edit().putString("parking.17", "1,2").putLong("parkingTime.17", 1_000).putString("parkingNote.17", "A").putFloat("parkingAccuracy.17", 5f).putInt("parkingRetention.17", 1)
            .putString("parking.18", "3,4").putLong("parkingTime.18", 1_000).commit()
        assertEquals(setOf(17), expireParking(prefs, 86_401_000))
        assertFalse(prefs.contains("parking.17")); assertFalse(prefs.contains("parkingTime.17")); assertFalse(prefs.contains("parkingNote.17")); assertFalse(prefs.contains("parkingAccuracy.17"))
        assertTrue(prefs.contains("parking.18"))
    }
    @Test fun `comfort copying does not copy phone links and guest writes never persist`() {
        val profiles = TeyesFeaturePreferences.get(context)
        profiles.update { it.copy(mediaGain = .3f, preferredPhone = "AA:BB:CC:DD:EE:FF") }
        profiles.copyComfort(0, 1, setOf("audio")); profiles.select(1)
        assertEquals(.3f, profiles.profile.value.mediaGain); assertEquals("", profiles.profile.value.preferredPhone)
        profiles.beginGuest(); profiles.update { it.copy(mediaGain = .1f, appearance = TeyesAppearance.DAY) }
        assertEquals(.3f, TeyesFeaturePreferences(context).profile.value.mediaGain)
        profiles.endGuest(); assertEquals(.3f, profiles.profile.value.mediaGain)
        profiles.resetComfort(1); assertEquals(1f, profiles.profile.value.mediaGain)
        profiles.select(0); assertEquals(.3f, profiles.profile.value.mediaGain)
    }
    @Test fun `portable backup excludes histories and locations and selected restore preserves other sections`() {
        val profiles = TeyesFeaturePreferences.get(context); profiles.update { it.copy(name = "Before") }
        val automation = automationPreferences(context); automation.edit().putBoolean("quiet", true).putString("parking.17", "1,2").commit()
        history.save(17, trip(1000)); history.prefs.edit().putString("fuelRate.17", "10").commit()
        val store = PortableConfigurationBackup(context); val backup = store.snapshot()
        assertFalse(backup.contains("parking.17")); assertFalse(backup.contains("trips.17")); assertTrue(backup.contains("fuelRate.17"))
        automation.edit().putBoolean("quiet", false).commit(); profiles.update { it.copy(name = "After") }
        assertTrue(store.restore(backup, setOf("automation")))
        assertTrue(automation.getBoolean("quiet", false)); assertEquals("After", profiles.profile.value.name)
        assertEquals("1,2", automation.getString("parking.17", ""))
    }
    @Test fun `failed multi store restore rolls everything back`() {
        val profiles = TeyesFeaturePreferences.get(context); profiles.update { it.copy(name = "Snapshot") }
        val automation = automationPreferences(context); automation.edit().putBoolean("quiet", true).commit()
        val store = PortableConfigurationBackup(context); val backup = store.snapshot()
        profiles.update { it.copy(name = "Current") }; automation.edit().putBoolean("quiet", false).commit()
        store.writeInterceptor = { if(it == "automation") error("Injected disk write failure") }
        assertFalse(store.restore(backup, linkedSetOf("drivers", "automation")))
        assertEquals("Current", profiles.profile.value.name); assertFalse(automation.getBoolean("quiet", true))
    }
    @Test fun `process interruption recovers the complete old configuration before readers start`() {
        val profiles = TeyesFeaturePreferences.get(context); profiles.update { it.copy(name = "Snapshot") }
        val store = PortableConfigurationBackup(context); val backup = store.snapshot()
        profiles.update { it.copy(name = "Current") }
        store.writeInterceptor = { if(it == "automation") throw AssertionError("Simulated process death") }
        assertThrows(AssertionError::class.java) { store.restore(backup, linkedSetOf("drivers", "automation")) }
        PortableConfigurationBackup.recoverPending(context); profiles.refresh()
        assertEquals("Current", profiles.profile.value.name)
    }
    @Test fun `encrypted backup authentication rejects wrong passwords and tampering`() {
        val plain = PortableConfigurationBackup(context).snapshot()
        val encrypted = PasswordBackup.encrypt(plain, "password one".toCharArray())
        assertEquals(plain, PasswordBackup.decrypt(encrypted, "password one".toCharArray()))
        assertThrows(Exception::class.java) { PasswordBackup.decrypt(encrypted, "password two".toCharArray()) }
        val json = JSONObject(encrypted); val data = json.getString("data"); json.put("data", (if(data[0] == 'A') "B" else "A") + data.substring(1))
        assertThrows(Exception::class.java) { PasswordBackup.decrypt(json.toString(), "password one".toCharArray()) }
    }
    @Test fun `wrong preference types unknown keys and foreign widget identities reject before writing`() {
        val store = PortableConfigurationBackup(context)
        fun changed(section: String, key: String, value: JSONObject): String = JSONObject(store.snapshot()).apply {
            getJSONObject("sections").getJSONObject(section).put(key, value)
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { store.restore(changed("projection", "focus_controls", JSONObject().put("t", "s").put("v", "true"))) }
        assertThrows(IllegalArgumentException::class.java) { store.restore(changed("units", "unit", JSONObject().put("t", "i").put("v", 1))) }
        assertThrows(IllegalArgumentException::class.java) { store.restore(changed("appearance", "unknown", JSONObject().put("t", "s").put("v", "x"))) }
        val layout = JSONObject().put("gridVersion", 2).put("pages", 1).put("tiles", org.json.JSONArray().put(JSONObject().put("id", 1).put("kind", "WIDGET").put("page", 0).put("x", 0).put("y", 0).put("w", 2).put("h", 2).put("widget", 44)))
        assertThrows(IllegalArgumentException::class.java) { store.restore(changed("dashboard", "0.17.LEGACY", JSONObject().put("t", "s").put("v", layout.toString()))) }
    }
    @Test fun `duplicate and deeply nested JSON rejected with bounded validation`() {
        assertThrows(IllegalArgumentException::class.java) { validateBackupJson("{\"x\":1,\"x\":2}") }
        assertThrows(IllegalArgumentException::class.java) { validateBackupJson("[".repeat(30) + "0" + "]".repeat(30)) }
    }
    @Test fun `portable launcher snapshot strips embedded usage but preserves folders`() {
        val library = com.cabin.launcher.LauncherAppLibrary(context, 0)
        library.recordHistory(true); library.recordLaunch("com.example/.Main", 1000)
        library.createFolder("Travel")
        val store = PortableConfigurationBackup(context); val text = store.snapshot()
        val raw = JSONObject(text).getJSONObject("sections").getJSONObject("library").getJSONObject("driver.0").getString("v")
        assertEquals(0, JSONObject(raw).getJSONObject("usage").length())
        assertTrue(raw.contains("Travel")); assertTrue(store.restore(text, setOf("library")))
    }

}
