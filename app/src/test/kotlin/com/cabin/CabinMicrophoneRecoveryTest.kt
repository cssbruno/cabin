package com.cabin

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cabin.audio.MicrophoneCaptureManager
import com.cabin.protocol.AudioCommand
import com.cabin.util.LogCallback
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CabinMicrophoneRecoveryTest {
    @Test
    fun `failed narrowband call retains requested format for automatic recovery`() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val manager = CabinManager(application)
        val microphone = MicrophoneCaptureManager(application, object : LogCallback {
            override fun log(message: String) = Unit
            override fun log(tag: String, message: String) = Unit
        })
        try {
            ReflectionHelpers.setField(manager, "microphoneManager", microphone)
            ReflectionHelpers.setField(manager, "lastIncomingDecodeType", 3)
            ReflectionHelpers.callInstanceMethod<Unit>(
                manager,
                "handleAudioCommand",
                ReflectionHelpers.ClassParameter.from(AudioCommand::class.java, AudioCommand.AUDIO_PHONECALL_START),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 3),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, AudioCommand.AUDIO_PHONECALL_START.id),
            )

            // Permission failure leaves capture stopped, but the scheduled retry must
            // retain this 8 kHz call instead of falling back to 16 kHz Siri defaults.
            assertFalse(microphone.isCapturing())
            assertEquals(3, ReflectionHelpers.getField<Int>(manager, "currentMicDecodeType"))
            assertEquals(3, ReflectionHelpers.getField<Int>(manager, "currentMicAudioType"))
            assertNotNull(ReflectionHelpers.getField<Any?>(manager, "micRecoveryJob"))
        } finally {
            manager.releaseAndWait()
        }
    }
}
