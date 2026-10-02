package com.cabin.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.Activity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import com.cabin.R
import com.cabin.logging.SupportExportQueue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.cabin.logging.FileLogManager
import com.cabin.ui.theme.CabinTheme
import java.io.File
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogExplorerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: FileLogManager
    private lateinit var file: File
    @Before fun setup() {
        manager = FileLogManager(compose.activity)
        file = File(compose.activity.cacheDir, "explorer-ui.log").apply {
            writeText("2026-09-30T12:00:00.000 E [USB] USB cable disconnected\n2026-09-30T12:00:01.000 I [UI] screen opened\n")
        }
    }
    @After fun cleanup() { manager.release(); file.delete() }

    @Test fun `missing export picker retains the copy and permits a new export`() {
        val context = compose.activity
        File(context.noBackupFilesDir, "support-exports").deleteRecursively()
        var fail = true
        var request: Int? = null
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (fail) throw ActivityNotFoundException("No document picker")
                request = requestCode
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        compose.setContent { CabinTheme { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            LogFileViewer(file, LogFilesStore(manager), {})
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("USB cable disconnected", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.logx_filters)).performClick()
        val export = context.getString(R.string.logx_export)
        compose.onNodeWithText(export).performScrollTo().performClick()
        val message = context.getString(R.string.uxf_export_saved_locally)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        val queue = SupportExportQueue(context)
        val saved = runBlocking { queue.pending().single() }
        assertTrue(saved.file.readText().contains("USB cable disconnected"))
        compose.onNodeWithText(export).performScrollTo().assertIsEnabled()
        fail = false
        compose.onNodeWithText(export).performClick()
        compose.waitUntil(10_000) { request != null }
        compose.runOnIdle { assertTrue(registry.dispatchResult(request!!, Activity.RESULT_CANCELED, null)) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(export) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(saved.file.exists())
    }

    @Test fun `search narrows actual visible records and original page reader remains reachable`() {
        val store = LogFilesStore(manager)
        compose.setContent { CabinTheme { LogFileViewer(file, store, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("USB cable disconnected", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Search log text").performTextInput("cable")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("screen opened", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("USB cable disconnected", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Filters and display").performClick()
        compose.onNodeWithText("Subsystem tag").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Filters and display").performScrollTo().performClick()
        compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val destination = File("build/reports/ui/debug/log-explorer.png")
            destination.parentFile?.mkdirs()
            destination.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithText("Browse original pages").performClick()
        try {
            compose.waitUntil(10_000) { compose.onAllNodesWithText("screen opened", substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (failure: Throwable) {
            println(compose.onAllNodes(isRoot(), useUnmergedTree = true).onLast().printToString())
            throw failure
        }
        compose.onNodeWithText("screen opened", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("cable").assertExists()
    }
}
