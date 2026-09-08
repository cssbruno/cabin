@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.cabin.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.cabin.util.LogCallback
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class MediaSessionArtworkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = object : LogCallback {
        override fun log(message: String) = Unit
        override fun log(tag: String, message: String) = Unit
    }

    @Test
    fun `late artwork attaches to latest text instead of replaying old track`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val uri = Uri.parse("content://test/cover")
        val manager = MediaSessionManager(context, logger) {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            uri
        }
        manager.initialize()
        manager.setProjectionActive()
        try {
            manager.updateMetadata("Old", "Artist", "Album", "Player", byteArrayOf(1), 100)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val job = pendingJob(manager)
            manager.updateMetadata("Latest", "Artist", "Album", "Player", null, 200)
            finish.countDown()
            await(job)
            shadowOf(Looper.getMainLooper()).idle()
            val metadata = player(manager).mediaMetadata
            assertEquals("Latest", metadata.title)
            assertEquals(200L, metadata.durationMs)
            assertEquals(uri, metadata.artworkUri)
        } finally {
            finish.countDown()
            manager.release()
        }
    }

    @Test
    fun `artwork from disconnected session cannot overwrite reconnected placeholder`() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val manager = MediaSessionManager(context, logger) {
            started.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            Uri.parse("content://test/old-cover")
        }
        manager.initialize()
        manager.setProjectionActive()
        try {
            manager.updateMetadata("Old", null, null, null, byteArrayOf(1))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val job = pendingJob(manager)
            manager.setInactive()
            manager.setProjectionActive()
            manager.updateMetadata("New session", null, null, null, null)
            finish.countDown()
            await(job)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("New session", player(manager).mediaMetadata.title)
            assertNull(player(manager).mediaMetadata.artworkUri)
        } finally {
            finish.countDown()
            manager.release()
        }
    }

    @Test
    fun `older blocked write cannot replace newer artwork`() {
        val firstStarted = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val manager = MediaSessionManager(context, logger) { bytes ->
            if (bytes[0] == 1.toByte()) {
                firstStarted.countDown()
                check(finishFirst.await(5, TimeUnit.SECONDS))
            }
            Uri.parse("content://test/cover-${bytes[0]}")
        }
        manager.initialize()
        manager.setProjectionActive()
        try {
            manager.updateMetadata("First", null, null, null, byteArrayOf(1))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            val first = pendingJob(manager)
            manager.updateMetadata("Second", null, null, null, byteArrayOf(2))
            await(pendingJob(manager))
            shadowOf(Looper.getMainLooper()).idle()
            finishFirst.countDown()
            await(first)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Second", player(manager).mediaMetadata.title)
            assertEquals(Uri.parse("content://test/cover-2"), player(manager).mediaMetadata.artworkUri)
        } finally {
            finishFirst.countDown()
            manager.release()
        }
    }

    private fun pendingJob(manager: MediaSessionManager): Job =
        MediaSessionManager::class.java.getDeclaredField("lastArtJob").apply { isAccessible = true }.get(manager) as Job

    private fun player(manager: MediaSessionManager): UsbAdapterPlayer =
        MediaSessionManager::class.java.getDeclaredField("player").apply { isAccessible = true }.get(manager) as UsbAdapterPlayer

    private fun await(job: Job) = runBlocking { withTimeout(5_000) { job.join() } }
}
