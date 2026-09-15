package com.cabin.joying

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import java.io.Closeable

/** The native daemon plays PCM; Cabin coordinates Android focus, not a second AudioTrack. */
internal class JoyingAudioFocus(context: Context, private val mediaControl: (Boolean) -> Unit) : Closeable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var media = false
    private var voice = false
    private var call = false
    @Volatile private var closed = false
    @Volatile private var generation = 0

    fun updateAudio(flags: Int) {
        when (flags and 0xfff) {
            0, 1 -> { val next = flags == 1; if (media == next) return; media = next }
            0x800, 0x801 -> { val next = flags == 0x801; if (voice == next) return; voice = next }
            else -> return
        }
        update()
    }
    fun reset() { media = false; voice = false; call = false; update() }
    fun updateCall(value: Int) { val next = value == 1 || value == 2; if (call != next) { call = next; update() } }
    private fun update() {
        if (closed) return
        val current = ++generation
        request?.let { audio.abandonAudioFocusRequest(it) }
        request = null
        if (!media && !voice && !call) return
        val attributes = AudioAttributes.Builder()
            .setUsage(if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else if (voice) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
            .setContentType(if (call || voice) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val next = AudioFocusRequest.Builder(if (call || voice) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (!closed && generation == current && media) when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> mediaControl(true)
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaControl(false)
                }
            }.build()
        request = next
        if (audio.requestAudioFocus(next) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED && media) mediaControl(false)
    }
    override fun close() { closed = true; generation++; request?.let { audio.abandonAudioFocusRequest(it) }; request = null }
}
