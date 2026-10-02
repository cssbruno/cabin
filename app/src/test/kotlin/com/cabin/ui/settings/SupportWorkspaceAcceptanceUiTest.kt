@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.cabin.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import com.cabin.R
import com.cabin.logging.FileLogManager
import com.cabin.logging.SupportExportQueue
import com.cabin.ui.theme.CabinTheme
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w1024dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportWorkspaceAcceptanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: FileLogManager
    private lateinit var directory: File
    private val generation = mutableIntStateOf(0)
    private val context get() = compose.activity

    @Before fun setup() {
        directory = File(context.filesDir, "logs").apply { mkdirs(); listFiles().orEmpty().forEach { it.delete() } }
        context.getSharedPreferences("cabin_log_bookmarks", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "support-exports").deleteRecursively()
        manager = FileLogManager(context)
    }
    @After fun release() { manager.release() }
    private fun label(id: Int) = context.getString(id)
    private fun entry(message: String) = "2026-09-30T12:00:00.000 I [UI] $message\n"
    private fun showWorkspace(logging: Boolean = false) {
        compose.setContent { CabinTheme { key(generation.intValue) { LogWorkspaceDialog(LogFilesStore(manager), logging, {}) } } }
    }
    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun closeDetail() { compose.onAllNodesWithText(label(R.string.logs_close_viewer)).onLast().performClick() }
    private fun reopenWorkspace() { compose.runOnIdle { generation.intValue++ }; compose.waitForIdle() }

    @Test fun `context marks original line and persistent bookmarks reopen remove and explain a missing source`() {
        val file = File(directory, "session_context_001.log").apply {
            writeText((1..9).joinToString("") { entry(if (it == 5) "target-match" else "neighbor-$it") })
        }
        showWorkspace()
        waitForText(file.name)
        compose.onNodeWithText(label(R.string.lxg_scan_limits)).performScrollTo().assertIsDisplayed()
        assertTrue(label(R.string.lxg_scan_limits).contains("250 matching records"))
        assertTrue(label(R.string.lxg_scan_limits).contains("2 MiB"))
        assertTrue(label(R.string.lxg_scan_limits).contains("last record is read in full"))
        compose.onNodeWithText(label(R.string.logx_search)).performTextInput("target-match")
        compose.onNodeWithText(label(R.string.lxg_select_all)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.lxg_search)).performScrollTo().performClick()
        waitForText("${file.name}:5")
        compose.onNodeWithText(label(R.string.lxg_bookmark)).performScrollTo().performClick()
        assertEquals(5, LogBookmarks(context).list().single().line)
        compose.onNodeWithText(label(R.string.lxg_context)).performScrollTo().performClick()
        waitForText(label(R.string.lxg_context_not_exported))
        compose.onNodeWithText("▶ 5: " + entry("target-match").trim()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2: " + entry("neighbor-2").trim()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("8: " + entry("neighbor-8").trim()).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1: " + entry("neighbor-1").trim()).assertDoesNotExist()
        closeDetail()

        reopenWorkspace()
        compose.onNodeWithText(label(R.string.lxg_bookmarks)).performScrollTo().performClick()
        val bookmark = hasText("${file.name}:5") and hasClickAction()
        compose.onNode(bookmark).performScrollTo().performClick()
        waitForText("▶ 5: " + entry("target-match").trim())
        closeDetail()
        compose.runOnIdle { assertTrue(file.delete()) }
        reopenWorkspace()
        compose.onNodeWithText(label(R.string.lxg_bookmarks)).performScrollTo().performClick()
        compose.onNode(bookmark).performScrollTo().performClick()
        waitForText(label(R.string.lxg_source_changed))
        compose.onNodeWithText(label(R.string.lxg_source_changed)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.lxg_remove)).performScrollTo().performClick()
        compose.onNode(bookmark).assertDoesNotExist()
        assertTrue(LogBookmarks(context).list().isEmpty())
    }

    @Test fun `report picker imports both documents filters differences and rejects invalid replacements`() {
        val picker = CapturingDocumentRegistry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent { CabinTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HealthReportComparisonPanel() }
            }
        } }
        fun choose(button: Int, name: String, contents: String) {
            val uri = Uri.parse("content://cabin-test-reports/$name")
            shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(contents.toByteArray()))
            compose.onNodeWithText(label(button)).performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.intent!!.action)
                assertTrue(picker.dispatchResult(picker.requestCode!!, Activity.RESULT_OK, Intent().setData(uri)))
            }
        }
        choose(R.string.lxg_first_report, "before.json", """{"schemaVersion":2,"projectionState":"ready","vehicleHealth":"live","removed":"old"}""")
        waitForText(label(R.string.lxg_replace_first))
        choose(R.string.lxg_second_report, "after.json", """{"schemaVersion":2,"projectionState":"streaming","vehicleHealth":"live","added":"new"}""")
        waitForText("${label(R.string.lxg_changed)} (1)")
        compose.onNodeWithText("/projectionState").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lxg_before, "ready")).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.lxg_after, "streaming")).assertIsDisplayed()
        compose.onNodeWithText("${label(R.string.lxg_missing)} (2)").performClick()
        compose.onNodeWithText("/added").assertIsDisplayed()
        compose.onNodeWithText("/removed").assertIsDisplayed()
        compose.onNodeWithText("/projectionState").assertDoesNotExist()
        compose.onNodeWithText("${label(R.string.lxg_unchanged)} (2)").performClick()
        compose.onNodeWithText("/vehicleHealth").assertIsDisplayed()
        compose.onNodeWithText("/schemaVersion").assertIsDisplayed()
        compose.onNodeWithText("/added").assertDoesNotExist()
        closeDetail()
        compose.onNodeWithText(label(R.string.ux_support_show_comparison)).performScrollTo().performClick()
        compose.onNodeWithText("${label(R.string.lxg_unchanged)} (2)").assertIsSelected()
        compose.onNodeWithText("/vehicleHealth").assertIsDisplayed()
        assertEquals(2, picker.launchCount)
        closeDetail()
        choose(R.string.lxg_replace_second, "new-after.json", """{"schemaVersion":2,"projectionState":"new state","vehicleHealth":"live","added":"new"}""")
        waitForText("${label(R.string.lxg_changed)} (1)")
        compose.onNodeWithText("${label(R.string.lxg_changed)} (1)").assertIsSelected()
        compose.onNodeWithText("/projectionState").assertIsDisplayed()
        closeDetail()
        choose(R.string.lxg_replace_second, "invalid.json", """{"schemaVersion":99,"unrelated":true}""")
        waitForText(label(R.string.lxg_report_invalid))
        compose.onNodeWithText(label(R.string.lxg_report_invalid)).assertIsDisplayed()
        compose.onNodeWithText("${label(R.string.lxg_unchanged)} (2)").assertDoesNotExist()
        compose.onNodeWithText(label(R.string.lxg_replace_first)).assertIsEnabled()
    }

    @Test fun `interrupted preparation explains the missing snapshot and only offers discard`() {
        val directory = File(context.noBackupFilesDir, "support-exports").apply { mkdirs() }
        val id = "00000000-0000-0000-0000-000000000001"
        val journal = File(directory, "$id.json").apply {
            writeText("""{"name":"interrupted-log.txt","mime":"text/plain"}""")
        }
        val partial = File(directory, "$id.tmp").apply { writeText("unfinished private log contents") }
        val picker = CapturingDocumentRegistry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent { CabinTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SupportExportRecovery() }
            }
        } }
        waitForText(label(R.string.lxg_preparation_interrupted))
        compose.onNodeWithText(label(R.string.lxg_preparation_interrupted)).assertIsDisplayed()
        compose.onNodeWithText("interrupted-log.txt").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.lxg_resume)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.lxg_discard)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.ux_support_discard_message, "interrupted-log.txt")).assertIsDisplayed()
        assertTrue(journal.exists())
        assertTrue(partial.exists())
        compose.onNodeWithText(label(R.string.action_cancel)).performClick()
        assertTrue(journal.exists())
        compose.onNodeWithText(label(R.string.lxg_discard)).performScrollTo().performClick()
        compose.onNode(hasText(label(R.string.lxg_discard)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { !journal.exists() && !partial.exists() }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("interrupted-log.txt").fetchSemanticsNodes().isEmpty() }
        assertNull(picker.intent)
        assertFalse(File(directory, "$id.payload").exists())
    }

    @Test fun `unavailable and denied save pickers preserve resumable payload and allow retry`() {
        val queue = SupportExportQueue(context)
        val saved = runBlocking { queue.enqueueText("saved-report.json", "frozen report") }
        val original = File(directory, "original.log").apply { writeText("original log") }
        val picker = CapturingDocumentRegistry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent { CabinTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { SupportExportRecovery() }
            }
        } }
        waitForText(label(R.string.lxg_resume))
        listOf(ActivityNotFoundException("No DocumentsUI"), SecurityException("Document access denied")).forEach { failure ->
            picker.failure = failure
            compose.onNodeWithText(label(R.string.lxg_resume)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.ux_support_picker_unavailable)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.lxg_resume)).assertIsEnabled()
            assertEquals("frozen report", saved.file.readText())
            assertEquals(saved.id, runBlocking { queue.pending() }.single().id)
        }
        picker.failure = null
        compose.onNodeWithText(label(R.string.lxg_resume)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(picker.dispatchResult(picker.requestCode!!, Activity.RESULT_CANCELED, null)) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(label(R.string.lxg_resume)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(saved.file.exists())
        compose.onNodeWithText(label(R.string.lxg_discard)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.ux_support_discard_message, saved.name)).assertIsDisplayed()
        compose.onNode(hasText(label(R.string.lxg_discard)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { !saved.file.exists() }
        assertEquals("original log", original.readText())
    }

    @Test fun `report picker launch failures preserve the first report and clear reports resets its selection`() {
        val picker = CapturingDocumentRegistry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent { CabinTheme {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HealthReportComparisonPanel() }
            }
        } }
        chooseReport(picker, R.string.lxg_first_report, "retained.json", """{"schemaVersion":2,"projectionState":"ready","vehicleHealth":"live"}""")
        waitForText(label(R.string.lxg_replace_first))
        listOf(ActivityNotFoundException("No DocumentsUI"), SecurityException("Document access denied")).forEach { failure ->
            picker.failure = failure
            compose.onNodeWithText(label(R.string.lxg_second_report)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.ux_support_picker_unavailable)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.lxg_replace_first)).assertIsEnabled()
        }
        compose.onNodeWithText(label(R.string.ux_support_clear_reports)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.lxg_first_report)).assertIsEnabled()
        compose.onNodeWithText(label(R.string.ux_support_clear_reports)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.ux_support_show_comparison)).assertDoesNotExist()
        compose.onNodeWithText(label(R.string.ux_support_picker_unavailable)).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-w640dp-h360dp-land-mdpi")
    fun `comparison categories and records share a scroll area at double text size`() {
        val picker = CapturingDocumentRegistry(context)
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent { CabinTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner,
                LocalDensity provides Density(density.density, 2f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { HealthReportComparisonPanel() }
            }
        } }
        val report = """{"schemaVersion":2,"projectionState":"ready","vehicleHealth":"live"}"""
        chooseReport(picker, R.string.lxg_first_report, "large-first.json", report)
        waitForText(label(R.string.lxg_replace_first))
        chooseReport(picker, R.string.lxg_second_report, "large-second.json", report)
        waitForText("${label(R.string.lxg_changed)} (0)")
        val scroll = compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
        scroll.performScrollToNode(hasText(label(R.string.ux_support_no_category_rows)))
        compose.onNodeWithText(label(R.string.ux_support_no_category_rows)).assertIsDisplayed()
        scroll.performScrollToNode(hasText("${label(R.string.lxg_unchanged)} (3)"))
        compose.onNodeWithText("${label(R.string.lxg_unchanged)} (3)").performClick()
        scroll.performScrollToNode(hasText("/vehicleHealth"))
        compose.onNodeWithText("/vehicleHealth").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.logs_close_viewer)).assertIsDisplayed().performClick()
    }

    @Test fun `follow reads rotated session files once and stopping freezes the visible records`() {
        val first = File(directory, "cabin_follow_001.log").apply { writeText(entry("first-record")) }
        showWorkspace(logging = true)
        waitForText(first.name)
        compose.onNodeWithText(label(R.string.lxg_follow_rotation)).performScrollTo().performClick()
        waitForText("first-record", substring = true)
        compose.onNodeWithText(label(R.string.logx_search)).assertIsNotEnabled()
        val rotated = File(directory, "cabin_follow_002.log").apply { writeText(entry("rotated-record")) }
        // Advance the coroutine scheduler, not real wall time, through one follow interval.
        compose.mainClock.advanceTimeBy(3_100)
        waitForText("rotated-record", substring = true)
        compose.onAllNodesWithText(entry("first-record").trim()).assertCountEquals(1)
        compose.onAllNodesWithText(entry("rotated-record").trim()).assertCountEquals(1)
        compose.onNodeWithText(label(R.string.lxg_follow_rotation)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.logx_search)).assertIsEnabled()
        compose.runOnIdle { rotated.appendText(entry("after-stop")) }
        compose.mainClock.advanceTimeBy(6_100)
        compose.waitForIdle()
        compose.onNodeWithText("after-stop", substring = true).assertDoesNotExist()
    }

    private fun chooseReport(picker: CapturingDocumentRegistry, button: Int, name: String, contents: String) {
        val uri = Uri.parse("content://cabin-test-reports/$name")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(contents.toByteArray()))
        compose.onNodeWithText(label(button)).performScrollTo().performClick()
        compose.runOnIdle { assertTrue(picker.dispatchResult(picker.requestCode!!, Activity.RESULT_OK, Intent().setData(uri))) }
    }

    private class CapturingDocumentRegistry(private val context: Context) : ActivityResultRegistry() {
        @Volatile var intent: Intent? = null
        var requestCode: Int? = null
        var launchCount: Int = 0
        var failure: RuntimeException? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            failure?.let { throw it }
            launchCount++
            this.requestCode = requestCode
            intent = contract.createIntent(context, input)
        }
    }
}
