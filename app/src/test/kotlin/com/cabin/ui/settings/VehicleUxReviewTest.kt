package com.cabin.ui.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import com.cabin.R
import com.cabin.platform.*
import com.cabin.ui.ClimateToolsPanel
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w1000dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleUxReviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private lateinit var history: TripHistory
    private lateinit var profiles: TeyesFeaturePreferences
    private lateinit var documents: Documents

    @Before fun reset() {
        PortableConfigurationBackup.stores.values.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        listOf(TeyesFeaturePreferences::class.java, ProjectionPreferences::class.java, MeasurementPreferences::class.java).forEach {
            it.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        }
        history = TripHistory(context)
        profiles = TeyesFeaturePreferences.get(context)
        documents = Documents()
    }

    @Test fun `maintenance deletion names the item preserves completed records and closes when vehicle changes`() {
        val ledger = MaintenanceLedger(history.prefs, 17)
        val other = MaintenanceLedger(history.prefs, 18)
        ledger.save(item("oil", "Oil")); ledger.complete("oil", "Completed record", 20.0)
        other.save(item("other", "Other vehicle"))
        var vehicle by mutableIntStateOf(17)
        show { MaintenanceItemsPanel(vehicle, history, rememberAutomationValues("cabin_trips")) }
        click(R.string.tools_maintenance); click(R.string.action_delete)
        compose.onNodeWithText(context.getString(R.string.uxv_delete_maintenance, "Oil")).assertIsDisplayed()
        dialogClick(R.string.action_cancel)
        assertEquals(1, ledger.items().size)
        click(R.string.action_delete)
        compose.runOnIdle { vehicle = 18 }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals("Other vehicle", other.items().single().name)
        compose.runOnIdle { vehicle = 17 }
        click(R.string.action_delete); dialogClick(R.string.action_delete)
        assertTrue(ledger.items().isEmpty())
        assertEquals("Completed record", ledger.history().single().note)
        compose.onNodeWithText(label(R.string.uxv_maintenance_empty)).performScrollTo().assertIsDisplayed()
    }

    @Test fun `service export uses the reviewed vehicle even if vehicle changes in the document picker`() {
        listOf(17, 18).forEach { profile -> MaintenanceLedger(history.prefs, profile).apply { save(item("service", "Vehicle $profile")); complete("service", "Note $profile", null) } }
        var vehicle by mutableIntStateOf(17)
        show { MaintenanceItemsPanel(vehicle, history, rememberAutomationValues("cabin_trips")) }
        click(R.string.tools_maintenance); click(R.string.action_export)
        compose.runOnIdle { vehicle = 18 }
        val output = File(context.cacheDir, "vehicle-ux-service.csv").apply { writeText("") }
        compose.runOnIdle { documents.dispatchResult(checkNotNull(documents.request), Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))) }
        compose.waitUntil(10_000) { output.length() > 0 }
        assertTrue(output.readText().contains("Vehicle 17"))
        assertFalse(output.readText().contains("Vehicle 18"))
        compose.onNodeWithText(label(R.string.uxv_service_export_saved)).performScrollTo().assertIsDisplayed()
    }

    @Test fun `missing document picker gives useful backup recovery without losing password or settings`() {
        documents.fail = true
        profiles.update { it.copy(name = "Keep driver") }
        show { TeyesConfigurationTools(profiles) }
        click(R.string.bu_backup_title)
        compose.onNode(hasText(label(R.string.gv_password)) and hasSetTextAction()).performScrollTo().performTextReplacement("saved in memory")
        click(R.string.bu_save_backup)
        compose.onNodeWithText(label(R.string.uxv_document_picker_missing)).performScrollTo().assertIsDisplayed()
        click(R.string.bu_restore_backup)
        compose.onNodeWithText(label(R.string.uxv_document_picker_missing)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(label(R.string.gv_password)) and hasSetTextAction()).assertTextContains("saved in memory")
        assertEquals("Keep driver", profiles.profile.value.name)
    }

    @Test fun `restoring the screen during an encrypted export never falls back to a plain backup`() {
        val restoration = StateRestorationTester(compose)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = documents }
        restoration.setContent { CabinTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TeyesConfigurationTools(profiles) }
        } } }
        click(R.string.bu_backup_title)
        compose.onNodeWithContentDescription(label(R.string.gv_encrypt)).performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.gv_password)) and hasSetTextAction()).performScrollTo().performTextReplacement("private password")
        click(R.string.bu_save_backup)
        restoration.emulateSavedInstanceStateRestore()
        val output = File(context.cacheDir, "vehicle-ux-encrypted.json").apply { writeText("") }
        compose.runOnIdle { documents.dispatchResult(checkNotNull(documents.request), Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.bu_backup_save_failed)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0L, output.length())
        compose.onNodeWithContentDescription(label(R.string.gv_encrypt)).assertIsOn()
    }

    @Test fun `comfort confirmation is readable and cannot reset a newly selected driver`() {
        profiles.update { it.copy(appearance = TeyesAppearance.DAY, mediaGain = .5f) }
        show { TeyesConfigurationTools(profiles) }
        click(R.string.gv_comfort); click(R.string.gv_reset_comfort)
        compose.onNodeWithText("Day → Night", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.teyes_projection_music, 50) + " → 100%").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { profiles.select(1) }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(.5f, profiles.profiles()[0].mediaGain)
        click(R.string.gv_copy)
        // Target is reinitialized to a different driver when the source changes.
        compose.onNode(hasText(profiles.profiles()[2].name) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test fun `changing trip period removes hidden selections before export or delete`() {
        history.restore(17, trip(LocalDate.now().minusDays(2)))
        show { TripHistoryBrowser(history, 17, rememberAutomationValues("cabin_trips")) }
        compose.onNode(hasContentDescription("Select trip from", substring = true)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.gv_selected, 1)).performScrollTo().assertIsDisplayed()
        click(R.string.utility_all); click(R.string.utility_today)
        compose.onNodeWithText(label(R.string.tools_no_trips)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.gv_selected, 1)).assertDoesNotExist()
        button(R.string.utility_export).assertIsNotEnabled()
        assertEquals(1, history.read(17).size)
    }

    @Test fun `forget parking confirms the scope and clears its reminder without touching another vehicle`() {
        val prefs = automationPreferences(context)
        prefs.edit().putBoolean("parked", true).putString("parking.17", "1,2").putString("parkingNote.17", "Garage").putLong("parkingExpires.17", System.currentTimeMillis() + 60_000)
            .putInt("saveParking", 17).putLong("saveParkingTime", System.currentTimeMillis()).putString("parking.18", "3,4").commit()
        show { CarAutomationPanel(TeyesClimateState(profileId = 17)) }
        click(R.string.tools_automation); click(R.string.tools_forget_parked)
        dialogClick(R.string.action_cancel)
        assertEquals("1,2", prefs.getString("parking.17", ""))
        click(R.string.tools_forget_parked); dialogClick(R.string.action_delete)
        assertFalse(prefs.contains("parking.17")); assertFalse(prefs.contains("parkingNote.17")); assertFalse(prefs.contains("parkingExpires.17")); assertFalse(prefs.contains("saveParking"))
        assertEquals("3,4", prefs.getString("parking.18", ""))
    }

    @Test fun `climate favorites explain their limit and free a slot when one is removed`() {
        val prefs = vehicleToolsPreferences(context)
        prefs.edit().putString("climateFavorites", "ac,fan,POWER,AUTO,DUAL,RECIRCULATION").commit()
        show { ClimateToolsPanel(TeyesClimateState(profileId = 1048874, controlsSupported = true), null, null, null) }
        click(R.string.gv_favorites)
        compose.onNodeWithText(context.getString(R.string.uxv_favorite_limit, 6)).performScrollTo().assertIsDisplayed()
        button(R.string.gv_front_defrost).assertIsNotEnabled()
        click(R.string.gv_dual)
        button(R.string.gv_front_defrost).assertIsEnabled().performScrollTo().performClick()
        assertEquals(6, prefs.getString("climateFavorites", "")!!.split(',').size)
        assertTrue(prefs.getString("climateFavorites", "")!!.contains("FRONT_DEFROST"))
        click(R.string.launcher_done)
        button(R.string.gv_favorites).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-w600dp-h360dp-land-mdpi")
    fun `maintenance dialog fields and confirmation remain reachable at two hundred percent text`() {
        MaintenanceLedger(history.prefs, 17).save(item("oil", "Oil"))
        show(largeText = true) { MaintenanceItemsPanel(17, history, rememberAutomationValues("cabin_trips")) }
        click(R.string.tools_maintenance); click(R.string.utility_complete_service)
        compose.onNode(hasText(label(R.string.utility_cost_sort)) and hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextReplacement("25")
        compose.onNode(hasText(label(R.string.action_apply)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsDisplayed().performClick()
        assertEquals(25.0, MaintenanceLedger(history.prefs, 17).history().single().cost!!, 0.0)
    }

    private fun item(id: String, name: String) = MaintenanceItem(id, name, LocalDate.now().plusDays(30), 30)
    private fun trip(day: LocalDate): RecordedTrip { val time = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); return RecordedTrip(time, time + 60_000, 1.0, 60, null) }
    private fun show(largeText: Boolean = false, content: @Composable () -> Unit) {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = documents }
        compose.setContent { CabinTheme { val density = LocalDensity.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner, LocalDensity provides Density(density.density, if(largeText) 2f else density.fontScale)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
        } }
    }
    private fun label(id: Int) = context.getString(id)
    private fun button(id: Int) = compose.onNode(hasText(label(id)) and hasClickAction() and !hasAnyAncestor(isDialog()))
    private fun click(id: Int) { button(id).performScrollTo().performClick() }
    private fun dialogClick(id: Int) { compose.onNode(hasText(label(id)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick() }
    private class Documents : ActivityResultRegistry() {
        var request: Int? = null
        var fail = false
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            if(fail) throw ActivityNotFoundException("No document picker")
            request = requestCode
        }
    }
}
