package com.cabin.updates

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.cabin.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Suppress("DEPRECATION")
class UpdateSigningTest {
    private val old = Signature("abcd")
    private val rotated = Signature("1234")
    private val unrelated = Signature("5678")
    private val release = UpdateRelease(1007, "0.1", "", 100, "a".repeat(64))

    private fun apk(
        code: Int,
        signers: Array<Signature>,
        history: Array<Signature>? = null,
        legacy: Array<Signature>? = null,
    ) = PackageInfo().apply {
        packageName = "zeno.carlink"
        versionCode = code
        versionName = release.versionName
        applicationInfo = ApplicationInfo().apply { minSdkVersion = 27 }
        signatures = legacy
        signingInfo = SigningInfo().also {
            shadowOf(it).setSignatures(signers)
            if (history != null) shadowOf(it).setPastSigningCertificates(history)
        }
    }

    private fun validate(current: PackageInfo, candidate: PackageInfo) =
        validateUpdatePackage(current, candidate, release, "zeno.carlink", 28)

    private fun rejected(current: PackageInfo, candidate: PackageInfo) {
        assertEquals(R.string.update_error_signature,
            assertThrows(UpdateException::class.java) { validate(current, candidate) }.errorRes)
    }

    @Test fun `same signer and proven forward rotation pass`() {
        val current = apk(1006, arrayOf(old))
        validate(current, apk(1007, arrayOf(old)))
        validate(current, apk(1007, arrayOf(rotated), arrayOf(old, rotated)))
    }

    @Test fun `unrelated key and reverse rotation fail`() {
        rejected(apk(1006, arrayOf(old)), apk(1007, arrayOf(unrelated)))
        rejected(apk(1006, arrayOf(rotated), arrayOf(old, rotated)), apk(1007, arrayOf(old)))
        rejected(apk(1006, arrayOf(rotated), arrayOf(old, rotated)),
            apk(1007, arrayOf(unrelated), arrayOf(old, unrelated)))
    }

    @Test fun `multiple signers require complete set equality`() {
        val current = apk(1006, arrayOf(old, rotated))
        validate(current, apk(1007, arrayOf(rotated, old)))
        rejected(current, apk(1007, arrayOf(old)))
        rejected(current, apk(1007, arrayOf(old, unrelated)))
        rejected(apk(1006, arrayOf(old)), apk(1007, arrayOf(old, rotated)))
    }

    @Test fun `missing modern metadata uses requested legacy certificates`() {
        val current = apk(1006, arrayOf(old), legacy = arrayOf(old)).apply { signingInfo = null }
        validate(current, apk(1007, arrayOf(old)))
        validate(current, apk(1007, arrayOf(old), legacy = arrayOf(old)).apply { signingInfo = null })
        rejected(current, apk(1007, arrayOf(unrelated), legacy = arrayOf(unrelated)).apply { signingInfo = null })
    }

    @Test fun `empty or missing certificates fail closed`() {
        val current = apk(1006, arrayOf(old))
        rejected(current, apk(1007, emptyArray(), legacy = arrayOf(old)))
        rejected(current, apk(1007, emptyArray()).apply { signingInfo = null })
        rejected(apk(1006, emptyArray()), apk(1007, arrayOf(old)))
    }
}
