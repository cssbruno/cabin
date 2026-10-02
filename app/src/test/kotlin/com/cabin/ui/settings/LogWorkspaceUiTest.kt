package com.cabin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.logging.FileLogManager
import com.cabin.logging.SupportExportQueue
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1024dp-h700dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: FileLogManager
    @Before fun setup() {
        val directory = File(compose.activity.filesDir, "logs").apply { mkdirs(); listFiles().orEmpty().forEach { it.delete() } }
        File(compose.activity.noBackupFilesDir, "support-exports").deleteRecursively()
        manager = FileLogManager(compose.activity)
        File(directory, "multi-one.log").writeText((1..300).joinToString("") { "2026-09-30T12:00:00.000 I [UI] matching entry-$it\n" })
        File(directory, "multi-two.log").writeText((1..3).joinToString("") { "2026-09-30T12:00:00.000 W [USB] matching tail-$it\n" })
    }
    @After fun release() { manager.release() }
    @Test @Config(qualifiers = "en-w600dp-h360dp-land-mdpi")
    fun `short workspace keeps results and close visible at double text size`() {
        RuntimeEnvironment.setFontScale(2f)
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), false, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(compose.activity.getString(com.cabin.R.string.logs_close_viewer)).assertHeightIsAtLeast(56.dp).assertIsDisplayed()
        compose.onNodeWithText("multi-one.log").performScrollTo().performClick()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log:1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("multi-one.log:1").assertIsDisplayed()
        val result = compose.onNodeWithText("2026-09-30T12:00:00.000 I [UI] matching entry-1")
        result.assertIsDisplayed()
        compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                view.draw(android.graphics.Canvas(image))
                File("build/reports/ui/debug/ux-log-workspace-200.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        result.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        val visibleHeight = result.fetchSemanticsNode().boundsInRoot.height
        assertTrue("The first result must be fully readable: visible=$visibleHeight, laid out=${layouts.single().size.height}", visibleHeight >= layouts.single().size.height - 1f)
        compose.onNodeWithContentDescription(compose.activity.getString(com.cabin.R.string.logs_close_viewer)).assertIsDisplayed()
    }

    @Test fun `empty workspace guides file selection and distinguishes an empty search page`() {
        // Empty pages are bounded by scanned bytes, not by the number of nonmatching lines.
        File(compose.activity.filesDir, "logs/multi-one.log").writeText("2026-09-30T12:00:00.000 I " + "x".repeat(2 * 1024 * 1024) + "\nno match here\n")
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), false, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(compose.activity.getString(com.cabin.R.string.ux_review_logs_start)).assertIsDisplayed()
        compose.onNodeWithText("Select / clear all").performScrollTo().performClick()
        val query = compose.onNode(hasSetTextAction() and hasText(compose.activity.getString(com.cabin.R.string.logx_search)))
        query.performScrollTo().performTextInput("absent text")
        query.assertIsFocused()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        val message = compose.activity.getString(com.cabin.R.string.ux_review_logs_page_empty)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        query.assertIsNotFocused()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithText("Continue search — next page").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(com.cabin.R.string.ux_review_logs_empty)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continue search — next page").assertDoesNotExist()
    }

    @Test fun `changed filters disable stale continuation and export until an IME search refreshes results`() {
        val context = compose.activity
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), false, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Select / clear all").performScrollTo().performClick()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 250 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val search = compose.onNode(hasSetTextAction() and hasText(context.getString(com.cabin.R.string.logx_search)))
        search.performScrollTo().performTextReplacement("tail-")
        compose.onNodeWithText(context.getString(com.cabin.R.string.uxf_search_changed)).assertIsDisplayed()
        compose.onNodeWithText("Continue search — next page").assertIsNotEnabled()
        compose.onNodeWithText("Export all matching records").assertIsNotEnabled()
        search.performScrollTo().assertIsFocused().performImeAction()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 303 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        // The dialog owns a separate focus root. Completing a search must release
        // its field so the software keyboard stops covering the result list.
        search.assertIsNotFocused()
        compose.onNodeWithText(context.getString(com.cabin.R.string.uxf_search_changed)).assertDoesNotExist()
        compose.onNodeWithText("Export all matching records").performScrollTo().assertIsEnabled().performClick()
        val queue = SupportExportQueue(context)
        compose.waitUntil(10_000) { runBlocking { queue.pending().isNotEmpty() } }
        val payload = runBlocking { queue.pending().single().file.readText() }
        assertEquals(3, payload.lineSequence().count { it.startsWith("2026-") })
        assertTrue(payload.contains("tail-3")); assertFalse(payload.contains("entry-"))
    }

    @Test fun `invalid date entry explains validation and cannot export a previous search`() {
        val context = compose.activity
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), false, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("multi-one.log").performScrollTo().performClick()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 250 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText(context.getString(com.cabin.R.string.lxg_from))).performScrollTo().performTextInput("2026-13-40T25:00")
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(com.cabin.R.string.lxg_invalid_range)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(com.cabin.R.string.logs_read_failed)).assertDoesNotExist()
        compose.onNodeWithText("Export all matching records").assertIsNotEnabled()
        assertTrue(runBlocking { SupportExportQueue(context).pending().isEmpty() })
    }

    @Test fun `a missing selected file prevents silently exporting a partial selection`() {
        val context = compose.activity
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), false, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Select / clear all").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(File(context.filesDir, "logs/multi-two.log").delete()) }
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(context.getString(com.cabin.R.string.lxg_source_changed)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export all matching records").assertIsNotEnabled()
        compose.onNodeWithText("multi-two.log").assertDoesNotExist()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 250 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export all matching records").assertIsEnabled()
    }

    @Test fun `pausing live results does not export the older static search`() {
        val context = compose.activity
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), true, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("multi-one.log").performScrollTo().performClick()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 250 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Export all matching records").assertIsEnabled()
        val followLabel = context.getString(com.cabin.R.string.lxg_follow_rotation)
        compose.onNodeWithText(followLabel).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Continue search — next page").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText(followLabel).performClick()
        compose.onNodeWithText(context.getString(com.cabin.R.string.uxf_follow_search_required)).assertIsDisplayed()
        compose.onNodeWithText("Export all matching records").assertIsNotEnabled()
        assertTrue(runBlocking { SupportExportQueue(context).pending().isEmpty() })
    }

    @Test fun `follow waits for a session that appears after opening the workspace`() {
        File(compose.activity.filesDir, "logs").listFiles().orEmpty().forEach { it.delete() }
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), true, {}) } }
        compose.onNodeWithText(compose.activity.getString(com.cabin.R.string.lxg_follow_rotation)).performScrollTo().performClick()
        compose.waitForIdle()
        File(compose.activity.filesDir, "logs/new_session_001.log").writeText("2026-10-01T12:00:00.000 I [UI] first live record\n")
        compose.mainClock.advanceTimeBy(3_100)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("first live record", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("2026-10-01T12:00:00.000 I [UI] first live record").assertIsDisplayed()
    }

    @Test fun `selected file search continues beyond first page and export contains every matching record`() {
        compose.setContent { CabinTheme { LogWorkspaceDialog(LogFilesStore(manager), false, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("multi-one.log").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Select / clear all").performScrollTo().performClick()
        compose.onNodeWithText("Search selected files").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 250 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continue search — next page").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Scanned: 303 lines.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Continue search — next page").assertDoesNotExist()
        compose.onNodeWithText("Export all matching records").performScrollTo().performClick()
        val queue = SupportExportQueue(compose.activity)
        compose.waitUntil(10_000) { runBlocking { queue.pending().isNotEmpty() } }
        val payload = runBlocking { queue.pending().single().file.readText() }
        assertEquals(303, payload.lineSequence().count { it.startsWith("2026-") })
        assertTrue(payload.contains("entry-1\n")); assertTrue(payload.contains("entry-300\n")); assertTrue(payload.contains("tail-3\n"))
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Resume export").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Resume export").performScrollTo().assertIsDisplayed()
    }
}
