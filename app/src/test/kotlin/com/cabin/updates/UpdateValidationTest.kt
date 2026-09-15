package com.cabin.updates

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import com.cabin.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27])
@Suppress("DEPRECATION")
class UpdateValidationTest {
    private val release = UpdateRelease(1007, "0.1.0-alpha.6", "", 100, "a".repeat(64))
    private fun apk(code: Int, signer: String = "abcd") = PackageInfo().apply {
        packageName = "zeno.carlink"
        versionCode = code
        versionName = release.versionName
        signatures = arrayOf(Signature(signer))
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 27 }
    }
    private fun validate(candidate: PackageInfo) =
        validateUpdatePackage(apk(1006), candidate, release, "zeno.carlink", 27)

    @Test fun `compatible update passes validation`() {
        validate(apk(1007))
    }

    @Test fun `different signing key reports why retry cannot help`() {
        val error = assertThrows(UpdateException::class.java) { validate(apk(1007, "1234")) }
        assertEquals(R.string.update_error_signature, updateErrorResource(error))
    }

    @Test fun `unsigned update remains rejected`() {
        val error = assertThrows(UpdateException::class.java) {
            validate(apk(1007).apply { signatures = emptyArray() })
        }
        assertEquals(R.string.update_error_signature, error.errorRes)
    }

    @Test fun `wrong package and stale version remain rejected`() {
        for (candidate in listOf(apk(1007).apply { packageName = "other.app" }, apk(1006))) {
            assertEquals(R.string.update_error_apk,
                assertThrows(UpdateException::class.java) { validate(candidate) }.errorRes)
        }
    }

    @Test fun `unsupported Android is reported separately`() {
        val error = assertThrows(UpdateException::class.java) {
            validate(apk(1007).apply { applicationInfo!!.minSdkVersion = 28 })
        }
        assertEquals(R.string.update_error_android, error.errorRes)
    }

    @Test fun `connection and certificate errors have actionable messages`() {
        assertEquals(R.string.update_error_network, updateErrorResource(java.net.UnknownHostException()))
        assertEquals(R.string.update_error_network, updateErrorResource(java.net.SocketTimeoutException()))
        assertEquals(R.string.update_error_tls, updateErrorResource(javax.net.ssl.SSLException("certificate")))
        assertEquals(R.string.update_failed, updateErrorResource(IllegalStateException()))
    }
}
