package com.cabin.platform

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class AndroidPlayer(val id: String, val title: String, val artist: String, val playing: Boolean)
internal object CabinMediaSessions {
    internal val mutable = MutableStateFlow<List<AndroidPlayer>>(emptyList())
    val players = mutable.asStateFlow()
    internal var controllers = emptyList<MediaController>()
    fun control(id: String, action: TeyesKeyAction) {
        val controller = controllers.firstOrNull { it.packageName == id } ?: return
        try {
            when (action) {
                TeyesKeyAction.PLAY_PAUSE -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controller.transportControls.pause() else controller.transportControls.play()
                TeyesKeyAction.NEXT -> controller.transportControls.skipToNext()
                TeyesKeyAction.PREVIOUS -> controller.transportControls.skipToPrevious()
                else -> Unit
            }
        } catch (_: RuntimeException) { }
    }
}

/** Uses Android's explicit notification-access grant solely to discover media sessions. */
class CabinMediaListener : NotificationListenerService() {
    private var sessions: MediaSessionManager? = null
    private val callbacks = mutableMapOf<MediaController, MediaController.Callback>()
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { replace(it.orEmpty()) }
    override fun onListenerConnected() {
        super.onListenerConnected()
        sessions = getSystemService(MediaSessionManager::class.java)
        try {
            val component = ComponentName(this, CabinMediaListener::class.java)
            sessions?.addOnActiveSessionsChangedListener(listener, component)
            replace(sessions?.getActiveSessions(component).orEmpty())
        } catch (_: SecurityException) { replace(emptyList()) }
    }
    private fun replace(controllers: List<MediaController>) {
        callbacks.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        callbacks.clear()
        CabinMediaSessions.controllers = controllers.filter { it.packageName != packageName }.distinctBy { it.packageName }.take(16)
        CabinMediaSessions.controllers.forEach { controller ->
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onSessionDestroyed() { replace(CabinMediaSessions.controllers.filterNot { it == controller }) }
            }
            callbacks[controller] = callback
            controller.registerCallback(callback)
        }
        publish()
    }
    private fun publish() {
        CabinMediaSessions.mutable.value = CabinMediaSessions.controllers.map { controller ->
            AndroidPlayer(controller.packageName,
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().take(200),
                controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().take(200),
                controller.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
    }
    override fun onListenerDisconnected() { release(); super.onListenerDisconnected() }
    override fun onDestroy() { release(); super.onDestroy() }
    private fun release() { sessions?.removeOnActiveSessionsChangedListener(listener); sessions = null; replace(emptyList()) }
}
