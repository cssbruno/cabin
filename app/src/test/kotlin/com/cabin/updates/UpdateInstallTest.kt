package com.cabin.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class UpdateInstallTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("github_updates_v2", Context.MODE_PRIVATE)
    @Before fun reset() {
        prefs.edit().clear().commit()
        GitHubUpdater::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun `unprivileged caller cannot create silent session`() = runBlocking {
        val updater = GitHubUpdater.get(context)
        assertFalse(updater.canInstallSilently)
        try { updater.installSilently(); fail("Expected permission gate") } catch (_: IllegalStateException) { }
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }
    @Test fun `unrelated session results do not change persisted install`() {
        val updater = GitHubUpdater.get(context)
        prefs.edit().putInt("install_session", 12).commit()
        updater.installResult(13, PackageInstaller.STATUS_SUCCESS)
        assertEquals(12, prefs.getInt("install_session", -1))
    }
    @Test fun `firmware confirmation request enables normal installer fallback`() {
        val updater = GitHubUpdater.get(context)
        prefs.edit().putInt("install_session", 12).commit()
        updater.installResult(12, PackageInstaller.STATUS_PENDING_USER_ACTION)
        assertEquals(-1, prefs.getInt("install_session", -1))
        assertTrue(prefs.getBoolean("install_confirmation_required", false))
        assertFalse(updater.canInstallSilently)
        assertEquals(UpdatePhase.READY, updater.state.value.phase)
    }
    @Test fun `failed installation allows retry and success clears active session`() {
        val updater = GitHubUpdater.get(context)
        prefs.edit().putInt("install_session", 12).commit()
        updater.installResult(12, PackageInstaller.STATUS_FAILURE_STORAGE)
        assertEquals(UpdatePhase.READY, updater.state.value.phase)
        assertNotNull(updater.state.value.errorRes)
        prefs.edit().putInt("install_session", 14).commit()
        updater.installResult(14, PackageInstaller.STATUS_SUCCESS)
        assertEquals(UpdatePhase.CURRENT, updater.state.value.phase)
        assertEquals(-1, prefs.getInt("install_session", -1))
    }
    @Test fun `receiver rejects callback with mismatched session identity`() {
        prefs.edit().putInt("install_session", 12).commit()
        UpdateInstallReceiver().onReceive(context, Intent(UpdateInstallReceiver.ACTION)
            .setData(Uri.parse("cabin-update://session/12"))
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, 13)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS))
        assertEquals(12, prefs.getInt("install_session", -1))
    }
}
