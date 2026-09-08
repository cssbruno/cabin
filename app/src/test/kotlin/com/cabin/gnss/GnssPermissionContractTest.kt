package com.cabin.gnss

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GnssPermissionContractTest {
    private val contract = GnssPermissionContract()
    private val fine = Manifest.permission.ACCESS_FINE_LOCATION
    private val coarse = Manifest.permission.ACCESS_COARSE_LOCATION

    @Test
    fun `runtime request includes precise and approximate permissions together`() {
        val intent = contract.createIntent(ApplicationProvider.getApplicationContext<Application>(), Unit)
        assertEquals(RequestMultiplePermissions.ACTION_REQUEST_PERMISSIONS, intent.action)
        assertArrayEquals(arrayOf(fine, coarse), intent.getStringArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS))
    }

    @Test
    fun `approximate only and denied results cannot enable GNSS`() {
        assertFalse(contract.parseResult(Activity.RESULT_OK, result(fineGranted = false, coarseGranted = true)))
        assertFalse(contract.parseResult(Activity.RESULT_OK, result(fineGranted = false, coarseGranted = false)))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, result(fineGranted = true, coarseGranted = true)))
        assertFalse(contract.parseResult(Activity.RESULT_OK, null))
        assertFalse(contract.parseResult(Activity.RESULT_OK, Intent()))
    }

    @Test
    fun `precise grant enables the existing capability refresh callback`() {
        assertTrue(contract.parseResult(Activity.RESULT_OK, result(fineGranted = true, coarseGranted = true)))
    }

    @Test
    fun `cached approximate grant still requests precise permission`() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(fine)
        shadowOf(application).grantPermissions(coarse)
        assertNull(contract.getSynchronousResult(application, Unit))

        shadowOf(application).grantPermissions(fine)
        assertTrue(contract.getSynchronousResult(application, Unit)!!.value)
    }

    private fun result(fineGranted: Boolean, coarseGranted: Boolean) = Intent()
        .putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(fine, coarse))
        .putExtra(
            RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
            intArrayOf(
                if (fineGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
                if (coarseGranted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
            ),
        )
}
