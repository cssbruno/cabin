@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.cabin.media

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.session.MediaSession
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CabinMediaNotificationTest {
    @Test
    fun `reconciliation and metadata refresh preserve MediaStyle controls`() {
        val controller = Robolectric.buildService(CabinMediaBrowserService::class.java).create()
        val service = controller.get()
        val mediaSession = MediaSession(service, "notification-test")
        try {
            service.onStartCommand(Intent().setAction("ACTION_START_FOREGROUND"), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val manager = service.getSystemService(NotificationManager::class.java)
            val mediaNotification = Notification.Builder(service, "carlink_connection")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Playing track")
                .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken))
                .addAction(Notification.Action.Builder(null, "Pause", null).build())
                .build()
            manager.notify(CabinMediaBrowserService.NOTIFICATION_ID, mediaNotification)

            CabinMediaBrowserService.updateNowPlaying("Updated track", "Artist")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

            val posted = manager.activeNotifications.single { it.id == CabinMediaBrowserService.NOTIFICATION_ID }.notification
            assertTrue(posted.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
            assertEquals("Pause", posted.actions.single().title)
            assertEquals("Playing track", posted.extras.getCharSequence(Notification.EXTRA_TITLE))
        } finally {
            CabinMediaBrowserService.stopConnectionForeground(service)
            mediaSession.release()
            controller.destroy()
        }
    }
}
