package com.cabin.updates

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.pm.PackageInfoCompat
import com.cabin.BuildConfig
import com.cabin.R
import com.cabin.logging.SupportExportQueue
import com.cabin.platform.PortableConfigurationBackup
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.ui.theme.CabinTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exercises the real settings UI without selecting actions that open the network or installer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w800dp-h600dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateSettingsGoalsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = compose.activity
    private val prefs get() = context.getSharedPreferences("github_updates_v2", Context.MODE_PRIVATE)
    private val generation = mutableIntStateOf(0)
    private lateinit var picker: CapturingDocumentRegistry

    @Before fun reset() {
        prefs.edit().clear().putBoolean("automatic", false).commit()
        File(context.filesDir, "updates/update.apk").delete()
        PortableConfigurationBackup.stores.values.forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        resetSingleton(GitHubUpdater::class.java)
        resetSingleton(TeyesFeaturePreferences::class.java)
        val queue = SupportExportQueue(context)
        runBlocking { queue.pending().forEach { queue.discard(it.id) } }
        picker = CapturingDocumentRegistry(context)
    }

    @Test fun `cached release notes open and close without a network check`() {
        val release = seedRelease(notes = "Improved reconnect stability and clearer update status.")
        showSettings()
        compose.onNodeWithText(release.versionName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ux_release_notes)).performScrollTo().performClick()
        compose.onNodeWithText(release.notes).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.logs_close_viewer)).performClick()
        compose.onNodeWithText(release.notes).assertDoesNotExist()
        assertEquals(0L, GitHubUpdater.get(context).lastCheck)
        assertEquals(UpdatePhase.AVAILABLE, GitHubUpdater.get(context).state.value.phase)
    }

    @Test fun `cached release without notes explains their absence`() {
        seedRelease(notes = "  ")
        showSettings()
        compose.onNodeWithText(label(R.string.ux_release_notes)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.ux_no_notes)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.logs_close_viewer)).performClick()
        assertEquals(0L, GitHubUpdater.get(context).lastCheck)
    }

    @Test fun `saved switches and preview channel are shown and network preference survives reopening`() {
        prefs.edit().putBoolean("previews", true).putBoolean("unmetered_only", true).commit()
        showSettings()
        compose.onAllNodes(isToggleable()).assertCountEquals(2)
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOn().performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.ux_preview)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(label(R.string.ux_stable)).assertIsNotSelected()
        assertFalse(prefs.getBoolean("unmetered_only", true))
        reopenSettings()
        compose.onAllNodes(isToggleable())[0].assertIsOff()
        compose.onAllNodes(isToggleable())[1].assertIsOff()
        compose.onNodeWithText(label(R.string.ux_preview)).performScrollTo().assertIsSelected()
        assertEquals(0L, GitHubUpdater.get(context).lastCheck)
    }

    @Test fun `skip suppresses cached release across reopening and clearing the choice restores it`() {
        val release = seedRelease()
        showSettings()
        compose.onNodeWithText(label(R.string.ux_skip)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.ux_skipped, release.versionName)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.update_download)).assertDoesNotExist()
        assertEquals(release.versionCode, prefs.getLong("skipped_code", -1))
        reopenSettings()
        compose.onNodeWithText(context.getString(R.string.ux_skipped, release.versionName)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ux_show_skipped)).assertIsDisplayed()
        assertNull(GitHubUpdater.get(context).state.value.release)

        // The actual Show skipped button intentionally performs a network check. Exercise its
        // persisted clear operation directly, then reopen from cache without making that request.
        compose.runOnIdle { GitHubUpdater.get(context).clearSkipped() }
        reopenSettings()
        compose.onNodeWithText(release.versionName).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(label(R.string.update_download)).performScrollTo().assertIsDisplayed()
        assertFalse(prefs.contains("skipped_code"))
        assertEquals(0L, GitHubUpdater.get(context).lastCheck)
    }

    @Test fun `canceling the preinstall backup prompt preserves the ready update without installing`() {
        seedRelease(ready = true)
        showSettings()
        compose.onNodeWithText(label(R.string.update_install)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.ux_backup_title)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.ux_backup_detail)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.action_cancel)).performClick()
        compose.onNodeWithText(label(R.string.ux_backup_title)).assertDoesNotExist()
        assertTrue(runBlocking { SupportExportQueue(context).pending().isEmpty() })
        assertNull(picker.intent)
        assertReadyWithoutInstall()
    }

    @Test fun `canceling document export keeps a validated local backup and never starts installation`() {
        seedRelease(ready = true)
        context.getSharedPreferences("cabin_vehicle_tools", 0).edit().putBoolean("readOnly", true).commit()
        showSettings()
        compose.onNodeWithText(label(R.string.update_install)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.ux_save_backup)).performClick()
        compose.waitUntil(timeoutMillis = 10_000) { picker.intent != null }
        compose.runOnIdle {
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent!!.action)
            assertEquals("application/json", picker.intent!!.type)
            assertEquals("cabin-before-update.json", picker.intent!!.getStringExtra(Intent.EXTRA_TITLE))
            assertTrue(picker.dispatchResult(picker.requestCode!!, Activity.RESULT_CANCELED, null))
        }
        compose.waitForIdle()
        compose.onNodeWithText(label(R.string.ux_backup_local)).assertIsDisplayed()
        val dialogCancel = hasText(label(R.string.action_cancel)) and hasAnyAncestor(isDialog())
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(dialogCancel and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNode(dialogCancel).performClick()
        val queued = runBlocking { SupportExportQueue(context).pending() }.single()
        assertEquals("cabin-before-update.json", queued.name)
        assertEquals("application/json", queued.mime)
        val snapshot = queued.file.readText()
        assertEquals(PortableConfigurationBackup.stores.keys, PortableConfigurationBackup(context).availableSections(snapshot))
        assertTrue(snapshot.contains("readOnly"))
        assertReadyWithoutInstall()
    }

    @Test fun `update preferences expose their labels on full row switch targets`() {
        prefs.edit().putBoolean("unmetered_only", true).commit()
        showSettings()
        compose.onNode(hasText(label(R.string.update_automatic)) and isToggleable()).assertHeightIsAtLeast(56.dp).assertIsOff()
        compose.onNode(hasText(label(R.string.ux_unmetered)) and isToggleable()).performScrollTo().performClick().assertIsOff()
        assertFalse(prefs.getBoolean("unmetered_only", true))
    }

    @Test fun `same named release explains the build change`() {
        val release = seedRelease(versionName = BuildConfig.VERSION_NAME)
        showSettings()
        compose.onNodeWithText(context.getString(R.string.ux_review_build_change, BuildConfig.VERSION_CODE, release.versionCode)).performScrollTo().assertIsDisplayed()
    }

    @Test fun `missing file picker retains the backup and leaves installation to the user`() {
        seedRelease(ready = true)
        picker.launchFailure = ActivityNotFoundException("No document provider")
        showSettings()
        compose.onNodeWithText(label(R.string.update_install)).performScrollTo().performClick()
        compose.onNodeWithText(label(R.string.ux_save_backup)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.ux_review_backup_picker_missing)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.ux_review_backup_picker_missing)).assertIsDisplayed()
        val saved = runBlocking { SupportExportQueue(context).pending() }.single()
        assertEquals(PortableConfigurationBackup.stores.keys, PortableConfigurationBackup(context).availableSections(saved.file.readText()))
        compose.onNode(hasText(label(R.string.action_cancel)) and hasAnyAncestor(isDialog())).performClick()
        assertReadyWithoutInstall()
    }

    private fun seedRelease(notes: String = "Cached update information", ready: Boolean = false, versionName: String = "999.0"): UpdateRelease {
        val installed = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        val release = UpdateRelease(installed + 100, versionName, "https://github.com/$UPDATE_REPOSITORY/releases/download/v$versionName/cabin.apk", 10, "a".repeat(64), notes)
        prefs.edit().putString("release", release.json()).putString("ready_asset", release.assetIdentity()).commit()
        // Only enough state to reach the confirmation UI. No test ever requests APK installation.
        if (ready) File(context.filesDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
        return release
    }

    private fun showSettings() {
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
        compose.setContent {
            CabinTheme(darkTheme = true) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    key(generation.intValue) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { UpdateSettingsSection() }
                    }
                }
            }
        }
    }

    private fun reopenSettings() {
        compose.runOnIdle { resetSingleton(GitHubUpdater::class.java); generation.intValue++ }
        compose.waitForIdle()
    }

    private fun assertReadyWithoutInstall() {
        assertEquals(UpdatePhase.READY, GitHubUpdater.get(context).state.value.phase)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        assertFalse(prefs.contains("install_session"))
        assertNull(shadowOf(context).nextStartedActivity)
        assertTrue(File(context.filesDir, "updates/update.apk").isFile)
        assertEquals(0L, GitHubUpdater.get(context).lastCheck)
    }

    private fun label(resource: Int) = context.getString(resource)
    private fun resetSingleton(type: Class<*>) = type.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)

    private class CapturingDocumentRegistry(private val context: Context) : ActivityResultRegistry() {
        @Volatile var intent: Intent? = null
        var requestCode: Int? = null
        var launchFailure: RuntimeException? = null
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launchFailure?.let { throw it }
            this.requestCode = requestCode
            intent = contract.createIntent(context, input)
        }
    }
}
