package com.cabin.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cabin.R
import com.cabin.launcher.TireHistoryWidget
import com.cabin.platform.*
import com.cabin.ui.rememberClimateCloseTimer
import com.cabin.ui.theme.CabinTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.DateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import kotlin.math.abs

/** Goal acceptance workflows use actual composables, persisted stores and controlled document results. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w1000dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleAcceptanceGoalsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private lateinit var history: TripHistory
    private lateinit var profiles: TeyesFeaturePreferences
    private lateinit var documents: Documents
    private val checkBox = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)

    @Before fun reset() {
        (PortableConfigurationBackup.stores.values + listOf("cabin_tire_history", "connection_summaries_v1", "cabin_log_bookmarks")).forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
        listOf(TeyesFeaturePreferences::class.java, ProjectionPreferences::class.java, MeasurementPreferences::class.java).forEach {
            it.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
        history = TripHistory(context)
        profiles = TeyesFeaturePreferences.get(context)
        MeasurementPreferences.get(context).select(MeasurementUnit.METRIC)
        documents = Documents(context)
    }

    @Test fun `climate timeout choices persist and pause never consumes the countdown`() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this) }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        var closes = 0
        compose.mainClock.autoAdvance = false
        show { CompositionLocalProvider(LocalLifecycleOwner provides owner) {
            rememberClimateCloseTimer { closes++ }
            VehicleReadingsTools(TeyesClimateState())
        } }
        fun choose(old: String, option: String) {
            button(old).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            // Auto-advance is deliberately off so the timer can be measured. Flush the
            // popup composition and animation before invoking its accessible action.
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(350)
            compose.waitForIdle()
            compose.onNodeWithText(option).performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.mainClock.advanceTimeBy(32)
        }
        choose("10", "20 s")
        assertEquals(20, context.getSharedPreferences("cabin_vehicle_tools", 0).getInt("climateTimeout", -1))
        compose.mainClock.advanceTimeBy(19_000); compose.runOnIdle { assertEquals(0, closes) }
        compose.mainClock.advanceTimeBy(1_100); compose.runOnIdle { assertEquals(1, closes) }
        choose("20", "30 s")
        compose.mainClock.advanceTimeBy(29_000); compose.runOnIdle { assertEquals(1, closes) }
        compose.mainClock.advanceTimeBy(1_100); compose.runOnIdle { assertEquals(2, closes) }
        choose("30", label(R.string.gv_never))
        compose.mainClock.advanceTimeBy(60_000); compose.runOnIdle { assertEquals(2, closes) }
        choose("0", "10 s")
        compose.mainClock.advanceTimeBy(4_000)
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(30_000); compose.runOnIdle { assertEquals(2, closes) }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(9_000); compose.runOnIdle { assertEquals(2, closes) }
        compose.mainClock.advanceTimeBy(1_200); compose.runOnIdle { assertEquals(3, closes) }
    }

    @Test fun `four tire histories share time axis preserve a missing sample gap and switch units`() {
        val start = System.currentTimeMillis() - 120_000
        val pressures = listOf(listOf(240.0, 230.0, 220.0, 210.0), listOf(null, 230.0, 220.0, 210.0), listOf(240.0, 230.0, 220.0, 210.0))
        val rows = JSONArray().apply { pressures.forEachIndexed { i, values -> put(JSONObject().put("time", start + i * 60_000).put("tires", JSONArray().apply {
            values.forEach { put(JSONArray().put(it ?: JSONObject.NULL).put(JSONObject.NULL)) }
        })) } }
        TireHistory(context).prefs.edit().putString("17", rows.toString()).commit()
        var primary = 0
        var continuous = 0
        compose.setContent { CabinTheme { primary = MaterialTheme.colorScheme.primary.toArgb(); continuous = MaterialTheme.colorScheme.tertiary.toArgb()
            Box(Modifier.size(900.dp, 500.dp)) { TireHistoryWidget(TeyesClimateState(profileId = 17)) }
        } }
        val wheels = listOf(R.string.vehicle_wheel_fl, R.string.vehicle_wheel_fr, R.string.vehicle_wheel_rl, R.string.vehicle_wheel_rr)
        awaitText(label(wheels[0]) + ": 240 kPa")
        wheels.forEachIndexed { i, id -> compose.onNodeWithText(label(id) + ": ${240 - 10 * i} kPa").assertIsDisplayed() }
        compose.onNodeWithText(label(R.string.gv_saved_history)).assertIsDisplayed()
        val timeLabel = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(start)) + " – " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(start + 120_000))
        val top = wheels.maxOf { id -> compose.onNodeWithText(label(id) + ": ${240 - wheels.indexOf(id) * 10} kPa").fetchSemanticsNode().boundsInRoot.bottom }.toInt() + 8
        val bottom = compose.onNodeWithText(timeLabel).fetchSemanticsNode().boundsInRoot.top.toInt() - 8
        compose.runOnIdle {
            val view = context.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            fun count(color: Int, left: Int, right: Int) = (top until bottom).sumOf { y -> (left until right).count { x -> similar(bitmap.getPixel(x, y), color) } }
            assertTrue("FL endpoints remain drawn", count(primary, 8, 893) > 0)
            assertEquals("Missing middle FL sample must not be connected", 0, count(primary, 200, 700))
            assertTrue("FR data remains continuous on the same timeline", count(continuous, 200, 700) > 100)
            bitmap.recycle()
        }
        compose.runOnIdle { MeasurementPreferences.get(context).select(MeasurementUnit.IMPERIAL) }
        compose.onNodeWithText(label(wheels[0]) + ": 34.8 psi").assertIsDisplayed()
        compose.onNodeWithText(label(wheels[3]) + ": 30.5 psi").assertIsDisplayed()
        compose.onNodeWithText(timeLabel).assertIsDisplayed()
    }

    @Test fun `custom date and label filters share the displayed totals and explicitly opted in CSV notes`() {
        val day = LocalDate.now().minusDays(2)
        val selected = trip(day, 1.5)
        history.restore(17, trip(day.minusDays(1), 8.0)); history.restore(17, selected); history.restore(17, trip(day.plusDays(1), 4.0))
        showTrips()
        edit(label(R.string.gv_from_date), day.plusDays(1).toString())
        edit(label(R.string.gv_to_date), day.toString())
        button(label(R.string.action_apply)).assertIsNotEnabled()
        edit(label(R.string.gv_from_date), day.toString()); click(R.string.action_apply)
        compose.onNodeWithText(context.getString(R.string.utility_summary, 1)).performScrollTo().assertIsDisplayed()
        click(R.string.gv_note)
        dialogField(label(R.string.gv_note)).performTextReplacement("x".repeat(600))
        dialogField(label(R.string.gv_note)).assertTextContains("x".repeat(500))
        dialogField(label(R.string.gv_note)).performTextReplacement("Business visit")
        compose.onNode(hasText(label(R.string.gv_all_labels)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        click(R.string.gv_business)
        assertEquals("", history.read(17).first { it.start == selected.start }.note)
        dialogClick(R.string.action_apply)
        assertEquals("business", history.read(17).first { it.start == selected.start }.label)
        click(R.string.gv_all_labels); click(R.string.gv_personal)
        compose.onNodeWithText(label(R.string.tools_no_trips)).performScrollTo().assertIsDisplayed()
        click(R.string.gv_personal); click(R.string.gv_business)
        compose.onNodeWithText(context.getString(R.string.utility_summary, 1)).performScrollTo().assertIsDisplayed()
        click(R.string.utility_export)
        dialogCheckbox(R.string.gv_include_notes).assertIsOff()
        dialogClick(R.string.action_export)
        val plain = finishDocument("trip-private.csv")
        awaitText(label(R.string.utility_export_done))
        assertEquals(2, plain.readLines().size)
        assertFalse(plain.readText().contains("Business visit"))
        assertTrue(plain.readText().contains(java.time.Instant.ofEpochMilli(selected.start).toString()))
        click(R.string.utility_export); dialogCheckbox(R.string.gv_include_notes).performClick(); dialogClick(R.string.action_export)
        val withNotes = finishDocument("trip-notes.csv")
        awaitText(label(R.string.utility_export_done))
        compose.waitUntil(10_000) { withNotes.length() > 0 }
        assertTrue(withNotes.readText().contains("Business visit"))
        assertEquals(2, withNotes.readLines().size)
    }

    @Test fun `selected trip export and reviewed deletion preserve unrelated deletion during undo`() {
        val day = LocalDate.now()
        val trips = (1..3).map { trip(day.minusDays(it.toLong()), it.toDouble()) }
        trips.forEach { history.restore(17, it) }
        showTrips()
        compose.onAllNodes(checkBox)[0].performScrollTo().performClick()
        compose.onAllNodes(checkBox)[1].performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.gv_selected, 2)).performScrollTo().assertIsDisplayed()
        click(R.string.utility_export)
        compose.onNodeWithText(context.getString(R.string.utility_export_detail, 2)).assertIsDisplayed()
        dialogClick(R.string.action_cancel)
        fun deleteSelected() { compose.onAllNodes(hasText(label(R.string.action_delete)) and hasClickAction()).onLast().performScrollTo().performClick() }
        deleteSelected(); dialogClick(R.string.action_cancel); assertEquals(3, history.read(17).size)
        deleteSelected(); dialogClick(R.string.action_apply); assertEquals(1, history.read(17).size)
        val unrelated = history.read(17).single()
        compose.runOnIdle { history.delete(17, unrelated.start) }
        click(R.string.utility_undo)
        assertEquals(trips.map { it.start }.toSet() - unrelated.start, history.read(17).map { it.start }.toSet())
    }

    @Test fun `monthly comparison separates currencies and exposes missing estimates and retention limits`() {
        val first = YearMonth.now().minusMonths(1); val second = first.plusMonths(1)
        history.restore(17, trip(first.atDay(2), 10.0).copy(estimatedCost = 12.0, currency = "USD"))
        history.restore(17, trip(first.atDay(3), 5.0).copy(currency = "USD"))
        history.restore(17, trip(first.atDay(4), 2.0).copy(estimatedCost = 4.0, currency = "EUR"))
        history.restore(17, trip(second.atDay(1), 3.0))
        showTrips(); click(R.string.gv_compare_months)
        compose.onNodeWithText(label(R.string.gv_retained_only)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("$first · 3 · 17.0 km · 0 h 3 min 0 s").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("$second · 1 · 3.0 km · 0 h 1 min 0 s").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.utility_known_cost, "12 USD", 1, 2)).onLast().performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(context.getString(R.string.utility_known_cost, "4 EUR", 1, 1)).onLast().performScrollTo().assertIsDisplayed()
        assertEquals(0, monthSummary(history.read(17), second).costCount)
    }

    @Test fun `fuel drafts require apply and recalculation previews all affected records before changing costs`() {
        history.restore(17, trip(LocalDate.now(), 100.0)); history.restore(18, trip(LocalDate.now(), 20.0))
        show { TripToolsPanel(TeyesClimateState(profileId = 17)) }
        click(R.string.tools_trips)
        edit(label(R.string.tools_fuel_rate), "10"); edit(label(R.string.tools_fuel_price), "5"); edit(label(R.string.gv_currency), "BRL")
        assertNull(history.fuelValue(17, "fuelRate"))
        compose.onAllNodes(hasText(label(R.string.action_apply)) and hasClickAction())[0].performScrollTo().performClick()
        assertEquals("BRL", history.fuelValue(17, "fuelCurrency")); assertNull(history.fuelValue(18, "fuelCurrency"))
        assertNull(history.read(17).single().estimatedCost)
        click(R.string.utility_recalculate)
        compose.onNodeWithText(context.getString(R.string.gv_selected, 1)).assertIsDisplayed()
        compose.onNode(hasText("→ 50.0 BRL", substring = true) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        dialogClick(R.string.action_cancel); assertNull(history.read(17).single().estimatedCost)
        click(R.string.utility_recalculate); dialogClick(R.string.action_apply)
        assertEquals(50.0, history.read(17).single().estimatedCost!!, 0.0)
        assertEquals(10.0, history.read(17).single().fuelRate!!, 0.0)
        assertNull(history.read(18).single().estimatedCost)
        edit(label(R.string.tools_fuel_price), "bad")
        compose.onAllNodes(hasText(label(R.string.action_apply)) and hasClickAction())[0].assertIsNotEnabled()
        assertEquals("5", history.fuelValue(17, "fuelPrice"))
    }

    @Test fun `maintenance manual mileage completion correction and CSV remain independent per item and vehicle`() {
        val ledger = MaintenanceLedger(history.prefs, 17); val other = MaintenanceLedger(history.prefs, 18)
        val day = LocalDate.now(); other.save(MaintenanceItem("other", "Other car", day, 90))
        show { MaintenanceItemsPanel(17, history, rememberAutomationValues("cabin_trips")) }
        click(R.string.tools_maintenance)
        edit("km", "1000"); click(R.string.action_apply)
        assertEquals(1000.0, ledger.odometer()!!.kilometers, 0.0)
        edit("km", "999"); click(R.string.action_apply)
        compose.onNodeWithText(label(R.string.uxvf_odometer_order)).performScrollTo().assertIsDisplayed()
        assertEquals(1000.0, ledger.odometer()!!.kilometers, 0.0)
        edit(label(R.string.gv_name), "Oil"); edit(label(R.string.gv_interval_days), "30"); edit(label(R.string.gv_interval_km), "500")
        click(R.string.gv_add_item)
        val oil = ledger.items().single(); assertEquals(1500.0, oil.dueKm!!, 0.0)
        compose.onNodeWithText(context.getString(R.string.gv_due_km, utilityNumber(1500.0))).performScrollTo().assertIsDisplayed()
        edit(label(R.string.gv_name), "Tires"); edit(label(R.string.gv_interval_km), ""); click(R.string.gv_add_item)
        val tires = ledger.items().first { it.name == "Tires" }
        compose.onAllNodesWithText(context.getString(R.string.utility_snooze, 7))[0].performScrollTo().performClick()
        assertEquals(oil.due.plusDays(7), ledger.items().first { it.id == oil.id }.due)
        assertEquals(tires.due, ledger.items().first { it.id == tires.id }.due)
        fun completeOil() { compose.onAllNodes(hasText(label(R.string.utility_complete_service)) and hasClickAction())[ledger.items().indexOfFirst { it.id == oil.id }].performScrollTo().performClick() }
        completeOil()
        dialogField(label(R.string.gv_note)).performTextReplacement("Changed filter")
        dialogField(label(R.string.utility_cost_sort)).performTextReplacement("25")
        dialogClick(R.string.action_cancel); assertTrue(ledger.history().isEmpty())
        completeOil()
        dialogField(label(R.string.gv_note)).performTextReplacement("Changed filter")
        dialogField(label(R.string.utility_cost_sort)).performTextReplacement("25")
        dialogClick(R.string.action_apply)
        assertEquals(1, ledger.history().size); assertEquals(day.plusDays(30), ledger.items().first { it.id == oil.id }.due)
        assertEquals(tires.due, ledger.items().first { it.id == tires.id }.due)
        click(R.string.gv_correct); dialogField(label(R.string.utility_cost_sort)).performTextReplacement("30"); dialogClick(R.string.action_apply)
        assertEquals(30.0, ledger.history().single().cost!!, 0.0)
        assertEquals(day, other.items().single().due); assertTrue(other.history().isEmpty())
        click(R.string.action_export)
        val csv = finishDocument("service.csv"); compose.waitUntil(10_000) { csv.length() > 0 }
        assertTrue(csv.readText().contains("Changed filter")); assertTrue(csv.readText().contains("30.0"))
        compose.onNodeWithText(label(R.string.gv_manual_odometer)).performScrollTo().assertIsDisplayed()
    }

    @Test fun `comfort copy reset and guest require explicit UI actions without touching phone or vehicle settings`() {
        profiles.update { it.copy(appearance = TeyesAppearance.DAY, nightBrightness = .6f, mediaGain = .3f, navigationGain = .4f, resumeOnWake = true, recoverOverlays = false, compactOnLaunch = true, preferredPhone = "AA:BB:CC:DD:EE:FF") }
        context.getSharedPreferences("cabin_vehicle_tools", 0).edit().putBoolean("readOnly", true).commit()
        val original = profiles.profile.value
        show { TeyesConfigurationTools(profiles) }; click(R.string.gv_comfort)
        click(R.string.gv_copy)
        compose.onNodeWithText("Night → Day", substring = true).assertIsDisplayed()
        dialogClick(R.string.action_cancel); assertEquals(1f, profiles.profiles()[1].mediaGain)
        click(R.string.gv_copy); dialogClick(R.string.action_apply)
        val copied = profiles.profiles()[1]
        assertEquals(original.copy(slot = 1, name = copied.name, preferredPhone = ""), copied)
        click(R.string.gv_reset_comfort); dialogClick(R.string.action_cancel); assertEquals(original, profiles.profile.value)
        click(R.string.gv_reset_comfort); dialogClick(R.string.action_apply)
        assertEquals(TeyesDriverProfile(name = original.name, preferredPhone = original.preferredPhone), profiles.profile.value)
        assertEquals(copied, profiles.profiles()[1]); assertTrue(context.getSharedPreferences("cabin_vehicle_tools", 0).getBoolean("readOnly", false))
        val saved = profiles.profile.value
        click(R.string.gv_guest); compose.onNodeWithText(label(R.string.gv_guest_detail)).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { profiles.update { it.copy(appearance = TeyesAppearance.DAY, mediaGain = .2f, navigationGain = .1f) } }
        button(label(R.string.gv_copy)).assertIsNotEnabled(); button(label(R.string.gv_reset_comfort)).assertIsNotEnabled()
        assertEquals(saved, TeyesFeaturePreferences(context).profile.value)
        click(R.string.gv_end_guest); assertFalse(profiles.guestActive.value); assertEquals(saved, profiles.profile.value)
    }

    @Test fun `encrypted backup UI rejects bad password previews diffs and only restores the chosen section`() {
        profiles.update { it.copy(name = "Snapshot driver") }
        val automation = automationPreferences(context); automation.edit().putBoolean("quiet", true).commit()
        show { TeyesConfigurationTools(profiles) }; click(R.string.bu_backup_title)
        checkbox(R.string.gv_encrypt).performClick()
        button(label(R.string.bu_save_backup)).assertIsNotEnabled()
        edit(label(R.string.gv_password), "correct password"); click(R.string.bu_save_backup)
        val file = finishDocument("encrypted-settings.json")
        awaitText(label(R.string.bu_backup_saved)); assertEquals("cabin-encrypted-backup", JSONObject(file.readText()).getString("format"))
        assertFalse(file.readText().contains("Snapshot driver"))
        compose.runOnIdle { profiles.update { it.copy(name = "Current driver") }; automation.edit().putBoolean("quiet", false).commit() }
        edit(label(R.string.gv_password), "wrong password"); click(R.string.bu_restore_backup); returnDocument(file)
        awaitText(label(R.string.uxv_backup_rejected)); assertEquals("Current driver", profiles.profile.value.name); assertFalse(automation.getBoolean("quiet", true))
        fun importValid() { edit(label(R.string.gv_password), "correct password"); click(R.string.bu_restore_backup); returnDocument(file); awaitText(label(R.string.gv_restore_sections)) }
        importValid()
        compose.onNodeWithText("driver.0.name: Current driver → Snapshot driver").performScrollTo().assertIsDisplayed()
        dialogClick(R.string.action_cancel); assertEquals("Current driver", profiles.profile.value.name); assertFalse(automation.getBoolean("quiet", true))
        importValid()
        val choices = compose.onAllNodes(checkBox and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size
        repeat(choices) { i -> compose.onAllNodes(checkBox and hasAnyAncestor(isDialog()))[i].performScrollTo().performClick() }
        compose.onNode(hasContentDescription(label(R.string.tools_automation)) and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        dialogClick(R.string.action_apply); awaitText(label(R.string.bu_restore_saved))
        assertEquals("Current driver", profiles.profile.value.name); assertTrue(automation.getBoolean("quiet", false))
        assertTrue(PortableConfigurationBackup.stores.values.none { name -> context.getSharedPreferences(name, 0).all.values.any { it.toString().contains("correct password") || it.toString().contains("wrong password") } })
    }

    @Test fun `parking retention persists choices and privacy deletion requires confirmation for only one category`() {
        val parking = automationPreferences(context)
        parking.edit().putString("parking.17", "1,2").putLong("parkingTime.17", System.currentTimeMillis() - 2 * 86_400_000L).putString("parkingNote.17", "Garage").commit()
        history.restore(17, trip(LocalDate.now(), 1.0))
        show { ParkingReminderControls(17, rememberAutomationValues()); PrivacyInventory(17) }
        click(R.string.gv_until_cleared); click(context.getString(R.string.utility_days, 7))
        assertEquals(7, parking.getInt("parkingRetention.17", -1)); assertTrue(parking.contains("parking.17"))
        click(context.getString(R.string.utility_days, 7)); click(context.getString(R.string.utility_days, 1))
        assertFalse(parking.contains("parking.17")); assertFalse(parking.contains("parkingNote.17"))
        compose.runOnIdle { parking.edit().putString("parking.17", "3,4").putLong("parkingTime.17", System.currentTimeMillis()).commit() }
        click(context.getString(R.string.utility_days, 1)); click(R.string.gv_until_cleared)
        assertEquals(emptySet<Int>(), expireParking(context.getSharedPreferences("cabin_automation", 0), System.currentTimeMillis() + 90L * 86_400_000))
        click(R.string.gv_privacy)
        compose.onNodeWithText(context.getString(R.string.gv_privacy_trips, 1, 100)).performScrollTo().assertIsDisplayed()
        click(R.string.tools_clear_trips); dialogClick(R.string.action_cancel); assertEquals(1, history.read(17).size)
        click(R.string.tools_clear_trips); dialogClick(R.string.action_delete)
        assertTrue(history.read(17).isEmpty()); assertEquals("3,4", parking.getString("parking.17", ""))
        compose.onNodeWithText(context.getString(R.string.gv_privacy_trips, 0, 100)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.gv_privacy_profiles)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.gv_privacy_logs)).performScrollTo().assertIsDisplayed()
    }

    @Test fun `privacy launcher count recording and confirmed deletion affect only the selected driver`() {
        val first = com.cabin.launcher.LauncherAppLibrary(context, 0)
        val second = com.cabin.launcher.LauncherAppLibrary(context, 1)
        first.recordHistory(true); first.recordLaunch("com.example/.First")
        second.recordHistory(true); second.recordLaunch("com.example/.Second")
        show { PrivacyInventory(17) }; click(R.string.gv_privacy)
        compose.onNodeWithText(context.getString(R.string.gv_privacy_launcher, profiles.profile.value.name, 1)).performScrollTo().assertIsDisplayed()
        val recording = isToggleable() and hasContentDescription(label(R.string.gv_record_launcher))
        compose.onNode(recording).performScrollTo().assertIsOn().performClick()
        dialogClick(R.string.action_cancel)
        assertEquals(1, com.cabin.launcher.LauncherAppLibrary(context, 0).state.value.usage.size)
        compose.onNode(recording).performClick(); dialogClick(R.string.action_delete)
        assertTrue(com.cabin.launcher.LauncherAppLibrary(context, 0).state.value.usage.isEmpty())
        assertEquals(1, com.cabin.launcher.LauncherAppLibrary(context, 1).state.value.usage.size)
        compose.onNode(recording).performClick()
        compose.runOnIdle { first.recordLaunch("com.example/.First") }
        click(R.string.gv_clear_launcher); dialogClick(R.string.action_cancel)
        assertEquals(1, com.cabin.launcher.LauncherAppLibrary(context, 0).state.value.usage.size)
        click(R.string.gv_clear_launcher); dialogClick(R.string.action_delete)
        assertTrue(com.cabin.launcher.LauncherAppLibrary(context, 0).state.value.usage.isEmpty())
        assertTrue(com.cabin.launcher.LauncherAppLibrary(context, 0).state.value.recordHistory)
        compose.runOnIdle { profiles.select(1) }
        compose.onNodeWithText(context.getString(R.string.gv_privacy_launcher, profiles.profile.value.name, 1)).performScrollTo().assertIsDisplayed()
        click(R.string.gv_clear_launcher)
        compose.runOnIdle { profiles.select(0) }
        compose.onNode(hasText(label(R.string.action_delete)) and hasAnyAncestor(isDialog())).assertDoesNotExist()
        assertEquals(1, com.cabin.launcher.LauncherAppLibrary(context, 1).state.value.usage.size)
    }

    @Test fun `opening automation after downtime expires persisted parking without touching another vehicle`() {
        val prefs = automationPreferences(context)
        prefs.edit().putString("parking.17", "expired").putLong("parkingTime.17", System.currentTimeMillis() - 2 * 86_400_000L)
            .putString("parkingNote.17", "Private note").putInt("parkingRetention.17", 1)
            .putString("parking.18", "retained").putLong("parkingTime.18", System.currentTimeMillis() - 2 * 86_400_000L).commit()
        show { rememberCarAutomation(TeyesClimateState(profileId = 17), false); ParkingReminderControls(17, rememberAutomationValues()) }
        compose.waitUntil(10_000) { !prefs.contains("parking.17") }
        assertFalse(prefs.contains("parkingTime.17")); assertFalse(prefs.contains("parkingNote.17"))
        assertEquals("retained", prefs.getString("parking.18", ""))
        assertEquals(1, prefs.getInt("parkingRetention.17", -1))
        compose.onNodeWithText(context.getString(R.string.utility_days, 1)).performScrollTo().assertIsDisplayed()
    }

    @Test fun `portable backup round trips populated newer configuration sections while excluding personal histories`() {
        val sample = mapOf(
            "projection" to mapOf("driver.0.hide_seconds" to 20),
            "units" to mapOf("unit" to "IMPERIAL"),
            "launcher" to mapOf("favorites.0" to "[\"com.example/.Main\"]"),
            "library" to mapOf("driver.0" to "{\"showPackages\":true,\"folders\":{\"travel\":\"Travel\"},\"usage\":{}}"),
            "dashboard" to mapOf("0.17.LEGACY" to "{\"gridVersion\":2,\"pages\":1,\"tiles\":[{\"id\":1,\"kind\":\"CLOCK\",\"page\":0,\"x\":0,\"y\":0,\"w\":2,\"h\":2}],\"pageNames\":{\"0\":\"Travel\"}}"),
            "automation" to mapOf("quiet" to true, "parkingRetention.17" to 7),
            "appearance" to mapOf("17.body" to VehicleBodyStyle.entries.last().name, "17.paint" to VehiclePaint.entries.last().name),
            "accessibility" to mapOf("high_contrast" to true),
            "audio" to mapOf("presets.0" to "[{\"name\":\"Quiet\",\"media\":0.2,\"nav\":0.8}]"),
            "vehicle" to mapOf("readOnly" to true, "climateTimeout" to 30),
            "trips" to mapOf("fuelRate.17" to "8", "fuelPrice.17" to "3", "fuelCurrency.17" to "EUR", "recording.17" to false)
        )
        sample.forEach { (section, values) -> val editor = context.getSharedPreferences(PortableConfigurationBackup.stores.getValue(section), 0).edit()
            values.forEach { (key, value) -> when(value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value); is Int -> editor.putInt(key, value) } }; editor.commit()
        }
        profiles.update { it.copy(name = "Portable driver", mediaGain = .4f) }
        val ledger = MaintenanceLedger(history.prefs, 17); ledger.save(MaintenanceItem("oil", "Oil", LocalDate.now(), 90)); ledger.complete("oil", "Private service note", 25.0)
        automationPreferences(context).edit().putString("parking.17", "PRIVATE_COORDINATES").commit()
        val store = PortableConfigurationBackup(context); val text = store.snapshot()
        assertFalse(text.contains("Private service note")); assertFalse(text.contains("PRIVATE_COORDINATES")); assertTrue(text.contains("maintenanceItems.17"))
        PortableConfigurationBackup.stores.values.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }; profiles.refresh()
        assertTrue(store.restore(text))
        sample.forEach { (section, values) -> val restored = context.getSharedPreferences(PortableConfigurationBackup.stores.getValue(section), 0).all
            values.forEach { (key, value) -> if(section != "dashboard") assertEquals("$section/$key", value, restored[key]) else assertEquals("Travel", JSONObject(restored[key] as String).getJSONObject("pageNames").getString("0")) }
        }
        assertEquals("Portable driver", profiles.profile.value.name); assertEquals(.4f, profiles.profile.value.mediaGain)
        assertEquals("Oil", ledger.items().single().name); assertTrue(ledger.history().isEmpty())
    }

    private fun showTrips() = show { TripHistoryBrowser(history, 17, rememberAutomationValues("cabin_trips")) }
    private fun show(content: @Composable () -> Unit) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = documents }
        compose.setContent { CabinTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        } } }
    }
    private fun label(id: Int) = context.getString(id)
    private fun button(text: String) = compose.onNode(hasText(text) and hasClickAction() and !hasAnyAncestor(isDialog()))
    private fun click(id: Int) = click(label(id))
    private fun click(text: String) { button(text).performScrollTo().performClick() }
    private fun edit(label: String, value: String) { compose.onNode(hasText(label) and hasSetTextAction() and !hasAnyAncestor(isDialog())).performScrollTo().performTextReplacement(value) }
    private fun dialogField(label: String) = compose.onNode(hasText(label) and hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun dialogClick(id: Int) { compose.onNode(hasText(label(id)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick() }
    private fun checkbox(id: Int) = compose.onNode(checkBox and hasAnySibling(hasText(label(id))) and !hasAnyAncestor(isDialog()))
    private fun dialogCheckbox(id: Int) = compose.onNode(checkBox and hasAnySibling(hasText(label(id))) and hasAnyAncestor(isDialog()))
    private fun awaitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun trip(day: LocalDate, km: Double): RecordedTrip { val start = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); return RecordedTrip(start, start + 60_000, km, 60, null) }
    private fun finishDocument(name: String): File = File(context.cacheDir, name).apply { writeText(""); returnDocument(this) }
    private fun returnDocument(file: File) { compose.runOnIdle { assertNotNull(documents.request); documents.dispatchResult(documents.request!!, Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))); documents.request = null } }
    private fun similar(a: Int, b: Int): Boolean = listOf(0, 8, 16).all { shift -> abs(((a shr shift) and 255) - ((b shr shift) and 255)) <= 3 }
    private class Documents(private val context: Context) : ActivityResultRegistry() {
        var request: Int? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            assertTrue(contract.createIntent(context, input).action in setOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT, "androidx.activity.result.contract.action.REQUEST_PERMISSIONS"))
            request = requestCode
        }
    }
}
