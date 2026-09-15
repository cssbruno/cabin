package com.cabin.platform

import com.cabin.CabinManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProjectionTransition(val elapsedMs: Long, val state: CabinManager.State)

/** Deliberately no free-form text, phone identifiers, location, artwork or audio. */
enum class ProjectionEventKind {
    USER_CONNECT,
    USER_DISCONNECT,
    USER_RESTART,
    USB_DETACHED,
    PICTURE_RECOVERY,
}

data class ProjectionEvent(val elapsedMs: Long, val kind: ProjectionEventKind)

data class ProjectionIncident(
    val elapsedMs: Long,
    val nextState: CabinManager.State,
    val transitions: List<ProjectionTransition>,
    val events: List<ProjectionEvent>,
)

data class ProjectionHealthSnapshot(
    val connection: CabinManager.State = CabinManager.State.DISCONNECTED,
    val status: String = "Connect adapter",
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val transitions: List<ProjectionTransition> = emptyList(),
    val disconnectTransitions: Int = 0,
    val events: List<ProjectionEvent> = emptyList(),
    val incidents: List<ProjectionIncident> = emptyList(),
    val lastRecoveryDurationMs: Long? = null,
)

/** Multi-observer dashboard state. No Activity callbacks, artwork copies or unbounded logs. */
class ProjectionHealthStore(private val nowMillis: () -> Long) {
    private val startedAt = nowMillis()
    private val mutableState = MutableStateFlow(ProjectionHealthSnapshot())
    val state = mutableState.asStateFlow()
    private var lastElapsedMs = 0L
    private var streamEndedAt: Long? = null

    private fun elapsedNow(): Long {
        lastElapsedMs = (nowMillis() - startedAt).coerceAtLeast(lastElapsedMs)
        return lastElapsedMs
    }

    @Synchronized
    fun event(kind: ProjectionEventKind) {
        val previous = mutableState.value
        mutableState.value = previous.copy(events = (previous.events + ProjectionEvent(elapsedNow(), kind)).takeLast(MAX_EVENTS))
    }

    @Synchronized
    fun connection(value: CabinManager.State) {
        val previous = mutableState.value
        if (previous.connection == value) return
        val event = when (value) {
            CabinManager.State.DISCONNECTED -> com.cabin.telemetry.DiagnosticEvent.PROJECTION_DISCONNECTED
            CabinManager.State.CONNECTING -> com.cabin.telemetry.DiagnosticEvent.PROJECTION_CONNECTING
            CabinManager.State.STREAMING -> com.cabin.telemetry.DiagnosticEvent.PROJECTION_STREAMING
            else -> null
        }
        event?.let { com.cabin.telemetry.CabinTelemetry.record(it) }
        val disconnected = value == CabinManager.State.DISCONNECTED
        val clearMedia = disconnected || value == CabinManager.State.CONNECTING
        val elapsed = elapsedNow()
        val transitions = (previous.transitions + ProjectionTransition(elapsed, value)).takeLast(MAX_EVENTS)
        val streamEnded = previous.connection == CabinManager.State.STREAMING && value != CabinManager.State.STREAMING
        if (streamEnded) streamEndedAt = elapsed
        val recoveryDuration = if (value == CabinManager.State.STREAMING) streamEndedAt?.let { elapsed - it } else null
        if (value == CabinManager.State.STREAMING) streamEndedAt = null
        mutableState.value =
            previous.copy(
                connection = value,
                title = if (clearMedia) "" else previous.title,
                artist = if (clearMedia) "" else previous.artist,
                playing = if (clearMedia) false else previous.playing,
                transitions = transitions,
                incidents =
                    if (streamEnded) {
                        (
                            previous.incidents +
                                ProjectionIncident(
                                    elapsed, value, transitions,
                                    previous.events.filter { elapsed - it.elapsedMs <= INCIDENT_CONTEXT_MS },
                                )
                        ).takeLast(MAX_INCIDENTS)
                    } else {
                        previous.incidents
                    },
                lastRecoveryDurationMs = recoveryDuration ?: previous.lastRecoveryDurationMs,
                disconnectTransitions =
                    if (disconnected) {
                        (previous.disconnectTransitions + 1).coerceAtMost(Int.MAX_VALUE - 1)
                    } else {
                        previous.disconnectTransitions
                    },
            )
    }

    @Synchronized
    fun status(value: String) {
        mutableState.value = mutableState.value.copy(status = displayText(value))
    }

    @Synchronized
    fun media(
        title: String?,
        artist: String?,
        playing: Boolean,
    ) {
        val current = mutableState.value
        if (current.connection != CabinManager.State.STREAMING && current.connection != CabinManager.State.DEVICE_CONNECTED) return
        mutableState.value = current.copy(title = displayText(title), artist = displayText(artist), playing = playing)
    }

    @Synchronized
    fun clearMedia() {
        mutableState.value = mutableState.value.copy(title = "", artist = "", playing = false)
    }

    companion object {
        const val MAX_EVENTS = 40
        const val MAX_INCIDENTS = 5
        const val INCIDENT_CONTEXT_MS = 60_000L

        fun displayText(value: String?): String =
            value.orEmpty().filterNot {
                it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069'
            }.trim().take(160)
    }
}

@get:androidx.annotation.StringRes
val ProjectionEventKind.labelRes: Int
    get() = when (this) {
        ProjectionEventKind.USER_CONNECT -> com.cabin.R.string.hub_event_user_connect
        ProjectionEventKind.USER_DISCONNECT -> com.cabin.R.string.hub_event_user_disconnect
        ProjectionEventKind.USER_RESTART -> com.cabin.R.string.hub_event_user_restart
        ProjectionEventKind.USB_DETACHED -> com.cabin.R.string.hub_event_usb_detached
        ProjectionEventKind.PICTURE_RECOVERY -> com.cabin.R.string.hub_event_picture_recovery
    }
