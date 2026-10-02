package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.cabin.R
import com.cabin.platform.*
import com.cabin.ui.theme.CabinTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w1000dp-h800dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleFormFollowupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private lateinit var history: TripHistory
    private lateinit var ledger: MaintenanceLedger

    @Before fun reset() {
        listOf("cabin_trips", "cabin_automation", "cabin_vehicle_tools", "teyes_features_v1").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        history = TripHistory(context); ledger = MaintenanceLedger(history.prefs, 17)
    }

    @Test @Config(qualifiers = "pt-rBR-w1000dp-h800dp-land-mdpi")
    fun `Brazilian decimal keyboard values persist in odometer interval and service cost`() {
        showMaintenance()
        edit("km", "1000,5"); click(R.string.action_apply)
        assertEquals(1000.5, ledger.odometer()!!.kilometers, 0.0)
        edit(label(R.string.gv_name), "Óleo")
        edit(label(R.string.gv_interval_km), "500,25"); click(R.string.gv_add_item)
        assertEquals(1500.75, ledger.items().single().dueKm!!, 0.0)
        click(R.string.utility_complete_service)
        dialogField(R.string.utility_cost_sort).performScrollTo().performTextReplacement("125,90")
        dialogClick(R.string.action_apply)
        assertEquals(125.9, ledger.history().single().cost!!, 0.0)
        assertTrue(ledger.historyCsv().contains("125.9"))
    }

    @Test fun `invalid dates and decreasing readings explain the exact field and keep the entered draft`() {
        val day = LocalDate.now().minusDays(2)
        ledger.recordOdometer(ManualOdometer(day, 1000.0))
        showMaintenance()
        edit("km", "999,5"); click(R.string.action_apply)
        field("km").assertIsFocused().assertTextContains("999,5")
        compose.onNodeWithText(label(R.string.uxvf_odometer_order)).assertIsDisplayed()
        edit("km", "1000,5"); edit(label(R.string.uxvf_odometer_date), "2026-02-30"); click(R.string.action_apply)
        field(label(R.string.uxvf_odometer_date)).assertIsFocused().assertTextContains("2026-02-30")
        compose.onNodeWithText(label(R.string.uxvf_date_error)).assertIsDisplayed()
        edit(label(R.string.uxvf_odometer_date), day.minusDays(1).toString()); click(R.string.action_apply)
        compose.onNodeWithText(label(R.string.uxvf_reading_date_order)).assertIsDisplayed()
        edit(label(R.string.uxvf_odometer_date), LocalDate.now().plusDays(1).toString()); click(R.string.action_apply)
        compose.onNodeWithText(label(R.string.uxvf_future_reading)).assertIsDisplayed()
        assertEquals(ManualOdometer(day, 1000.0), ledger.odometer())
        edit(label(R.string.uxvf_odometer_date), LocalDate.now().toString()); click(R.string.action_apply)
        assertEquals(1000.5, ledger.odometer()!!.kilometers, 0.0)
    }

    @Test fun `new maintenance item keeps drafts and focuses the invalid date before explaining missing odometer`() {
        showMaintenance()
        edit(label(R.string.gv_name), "Oil"); edit(label(R.string.uxvf_due_date), "2026-02-30")
        edit(label(R.string.gv_interval_km), "500,5"); click(R.string.gv_add_item)
        field(label(R.string.uxvf_due_date)).assertIsFocused()
        field(label(R.string.gv_name)).assertTextContains("Oil")
        assertTrue(ledger.items().isEmpty())
        edit(label(R.string.uxvf_due_date), LocalDate.now().plusDays(30).toString()); click(R.string.gv_add_item)
        field(label(R.string.gv_interval_km)).assertIsFocused()
        compose.onNodeWithText(label(R.string.uxvf_odometer_needed)).assertIsDisplayed()
        edit(label(R.string.gv_interval_km), ""); click(R.string.gv_add_item)
        assertEquals("Oil", ledger.items().single().name)
        assertNull(ledger.items().single().dueKm)
    }

    @Test fun `deleted service correction cannot become a new completion`() {
        ledger.save(MaintenanceItem("oil", "Oil", LocalDate.now(), 30)); ledger.complete("oil", "Original", 10.0)
        val scheduled = ledger.items().single()
        showMaintenance(); click(R.string.gv_correct)
        dialogField(R.string.utility_cost_sort).performScrollTo().performTextReplacement("20,5")
        compose.runOnIdle { ledger.clearHistory() }
        compose.onNodeWithText(label(R.string.uxvf_record_missing)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(label(R.string.action_apply)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsNotEnabled()
        dialogClick(R.string.action_cancel)
        assertTrue(ledger.history().isEmpty()); assertEquals(scheduled, ledger.items().single())
    }

    @Test fun `forget parking clears the private draft and it stays gone after state restoration`() {
        val prefs = automationPreferences(context)
        prefs.edit().putBoolean("parked", true).putString("parking.17", "1,2").putString("parkingNote.17", "Saved garage").commit()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Page { CarAutomationPanel(TeyesClimateState(profileId = 17)) } }
        click(R.string.tools_automation)
        edit(label(R.string.utility_parking_note), "Private unsaved garage")
        click(R.string.tools_forget_parked); dialogClick(R.string.action_delete)
        field(label(R.string.utility_parking_note)).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        restoration.emulateSavedInstanceStateRestore()
        field(label(R.string.utility_parking_note)).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertFalse(prefs.contains("parkingNote.17"))
    }

    @Test fun `new parking capture preserves an unsaved note while deletion elsewhere clears it`() {
        val prefs = automationPreferences(context)
        prefs.edit().putBoolean("parked", true).commit()
        show { CarAutomationPanel(TeyesClimateState(profileId = 17)) }; click(R.string.tools_automation)
        edit(label(R.string.utility_parking_note), "Draft garage")
        compose.runOnIdle { prefs.edit().putString("parking.17", "1,2").commit() }
        field(label(R.string.utility_parking_note)).assertTextContains("Draft garage")
        compose.runOnIdle { prefs.edit().remove("parking.17").remove("parkingNote.17").commit() }
        field(label(R.string.utility_parking_note)).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Composable private fun Page(content: @Composable () -> Unit) { CabinTheme {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    } }
    private fun show(content: @Composable () -> Unit) { compose.setContent { Page(content) } }
    private fun showMaintenance() { show { MaintenanceItemsPanel(17, history, rememberAutomationValues("cabin_trips")) }; click(R.string.tools_maintenance) }
    private fun label(id: Int) = context.getString(id)
    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction() and !hasAnyAncestor(isDialog()))
    private fun edit(label: String, text: String) { field(label).performScrollTo().performTextReplacement(text) }
    private fun click(id: Int) { compose.onNode(hasText(label(id)) and hasClickAction() and !hasAnyAncestor(isDialog())).performScrollTo().performClick() }
    private fun dialogField(id: Int) = compose.onNode(hasText(label(id)) and hasSetTextAction() and hasAnyAncestor(isDialog()))
    private fun dialogClick(id: Int) { compose.onNode(hasText(label(id)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick() }
}
