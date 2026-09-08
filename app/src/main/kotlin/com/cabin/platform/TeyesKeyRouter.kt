package com.cabin.platform

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TeyesKeyAction(val label: String) {
    PLAY_PAUSE("Play / pause"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    VOICE("Phone assistant"),
    CLIMATE("A/C panel"),
}

val LocalTeyesKeyRouter = staticCompositionLocalOf<TeyesKeyRouter?> { null }

/** Only handles events delivered to our foreground Activity. Never captures system safety keys. */
class TeyesKeyRouter(
    private val preferences: TeyesFeaturePreferences,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val perform: (TeyesKeyAction) -> Unit,
) {
    private val resources = preferences.resources
    private val mutableStatus = MutableStateFlow(resources.getString(com.cabin.R.string.key_waiting))
    val status = mutableStatus.asStateFlow()
    private var learning: TeyesKeyAction? = null
    val isLearning: Boolean get() = learning != null
    private var learningUntil = 0L
    private val held = mutableSetOf<Int>()
    private val actionsOnRelease = mutableMapOf<Int, TeyesKeyAction>()

    fun learn(action: TeyesKeyAction) {
        learning = action
        learningUntil = nowMillis() + 15_000
        mutableStatus.value = resources.getString(com.cabin.R.string.key_press, resources.getString(action.labelRes))
    }

    fun cancelLearning() {
        if (learning != null) mutableStatus.value = resources.getString(com.cabin.R.string.key_learning_ended)
        learning = null
    }

    fun cancelPressedKeys() {
        // Retain consumed key codes until their up event, but don't execute an action
        // after focus loss, a canceled gesture, or an interrupted learning session.
        actionsOnRelease.clear()
        cancelLearning()
    }

    fun dispatch(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && held.remove(code)) {
            val action = actionsOnRelease.remove(code)
            if (!event.isCanceled && action != null) perform(action)
            return true
        }
        if (!isMappable(code) || event.action != KeyEvent.ACTION_DOWN) return false
        if (code in held && event.repeatCount > 0) return true
        // New down after an up was lost. It must not inherit an old action.
        held.remove(code)
        actionsOnRelease.remove(code)
        if (event.repeatCount != 0) return false
        mutableStatus.value = resources.getString(com.cabin.R.string.key_received, KeyEvent.keyCodeToString(code), code)
        val actionToLearn = learning.takeIf { nowMillis() <= learningUntil }
        learning = null
        if (actionToLearn != null) {
            preferences.mapKey(code, actionToLearn)
            mutableStatus.value = resources.getString(com.cabin.R.string.key_saved, KeyEvent.keyCodeToString(code), resources.getString(actionToLearn.labelRes))
            held.add(code)
            return true
        }
        val action = preferences.keyAction(code) ?: return false
        held.add(code)
        actionsOnRelease[code] = action
        return true
    }

    companion object {
        fun isMappable(code: Int): Boolean =
            code in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ||
                code in KeyEvent.KEYCODE_BUTTON_1..KeyEvent.KEYCODE_BUTTON_16 ||
                code in KeyEvent.KEYCODE_PROG_RED..KeyEvent.KEYCODE_PROG_BLUE ||
                code in
                setOf(
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                    KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_SEARCH,
                )
    }
}

/** Localized presentation; enum names remain stable for saved configuration. */
@get:androidx.annotation.StringRes
val TeyesKeyAction.labelRes: Int
    get() = when (this) {
        TeyesKeyAction.PLAY_PAUSE -> com.cabin.R.string.teyes_action_play_pause
        TeyesKeyAction.NEXT -> com.cabin.R.string.teyes_action_next
        TeyesKeyAction.PREVIOUS -> com.cabin.R.string.teyes_action_previous
        TeyesKeyAction.VOICE -> com.cabin.R.string.teyes_action_voice
        TeyesKeyAction.CLIMATE -> com.cabin.R.string.teyes_action_climate
    }
