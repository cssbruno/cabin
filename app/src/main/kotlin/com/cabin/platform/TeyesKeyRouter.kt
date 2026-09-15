package com.cabin.platform

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class TeyesKeyAction(val label: String) {
    BACK("Back"), NONE("Do nothing"),
    LAUNCHER("Launcher"),
    PLAY_PAUSE("Play / pause"),
    NEXT("Next track"),
    PREVIOUS("Previous track"),
    VOICE("Phone assistant"),
    CLIMATE("A/C panel"),
    PAGE_NEXT("Next dashboard page"), PAGE_PREVIOUS("Previous dashboard page"),
    VOLUME_UP("Volume up"), VOLUME_DOWN("Volume down"), MUTE("Mute"),
}

val LocalTeyesKeyRouter = staticCompositionLocalOf<TeyesKeyRouter?> { null }

/** Only handles events delivered to our foreground Activity. Never captures system safety keys. */
class TeyesKeyRouter(
    private val preferences: TeyesFeaturePreferences,
    private val nowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val launchApp: (String) -> Boolean = { false },
    private val perform: (TeyesKeyAction) -> Unit,
) {
    private val mutablePageChanges = kotlinx.coroutines.flow.MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val pageChanges = mutablePageChanges.asSharedFlow()
    private val resources = preferences.resources
    private val mutableStatus = MutableStateFlow(resources.getString(com.cabin.R.string.key_waiting))
    val status = mutableStatus.asStateFlow()
    private data class Target(val action: TeyesKeyAction? = null, val component: String? = null)
    private var learning: Target? = null
    val isLearning: Boolean get() = learning != null
    private var learningUntil = 0L
    private var learningLongPress = false
    private val downTimes = mutableMapOf<Int, Long>()
    private val longActions = mutableMapOf<Int, Target>()
    private val held = mutableSetOf<Int>()
    private val actionsOnRelease = mutableMapOf<Int, Target>()

    fun learn(action: TeyesKeyAction, longPress: Boolean = false) {
        learningLongPress = longPress
        learning = Target(action = action)
        learningUntil = nowMillis() + 15_000
        mutableStatus.value = resources.getString(com.cabin.R.string.key_press, resources.getString(action.labelRes))
    }

    fun learnApp(component: String, longPress: Boolean = false) {
        require(TeyesConfigurationBackup.validComponent(component))
        learningLongPress = longPress
        learning = Target(component = component)
        learningUntil = nowMillis() + 15_000
        mutableStatus.value = resources.getString(com.cabin.R.string.key_press, component.substringBefore('/'))
    }

    fun cancelLearning() {
        if (learning != null) mutableStatus.value = resources.getString(com.cabin.R.string.key_learning_ended)
        learning = null
    }

    fun cancelPressedKeys() {
        // Retain consumed key codes until their up event, but don't execute an action
        // after focus loss, a canceled gesture, or an interrupted learning session.
        actionsOnRelease.clear()
        downTimes.clear()
        longActions.clear()
        cancelLearning()
    }

    fun dispatch(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && held.remove(code)) {
            val shortAction = actionsOnRelease.remove(code)
            val longAction = longActions.remove(code)
            val downTime = downTimes.remove(code)
            val action = if (downTime != null && nowMillis() - downTime >= 650L && longAction != null) longAction else shortAction
            if (!event.isCanceled && action != null) {
                if (action.component != null) {
                    if (!launchApp(action.component)) mutableStatus.value = resources.getString(com.cabin.R.string.steering_app_unavailable)
                } else when (action.action) {
                    TeyesKeyAction.NONE, null -> Unit
                    TeyesKeyAction.PAGE_NEXT -> mutablePageChanges.tryEmit(1)
                    TeyesKeyAction.PAGE_PREVIOUS -> mutablePageChanges.tryEmit(-1)
                    else -> perform(action.action)
                }
            }
            return true
        }
        if (!isMappable(code) || event.action != KeyEvent.ACTION_DOWN) return false
        if (code in held && event.repeatCount > 0) return true
        // New down after an up was lost. It must not inherit an old action.
        held.remove(code)
        actionsOnRelease.remove(code)
        downTimes.remove(code)
        longActions.remove(code)
        if (event.repeatCount != 0) return false
        mutableStatus.value = resources.getString(com.cabin.R.string.key_received, KeyEvent.keyCodeToString(code), code)
        val actionToLearn = learning.takeIf { nowMillis() <= learningUntil }
        learning = null
        if (actionToLearn != null) {
            val label = if (actionToLearn.component != null) {
                preferences.mapKeyApp(code, actionToLearn.component, learningLongPress)
                actionToLearn.component.substringBefore('/')
            } else {
                preferences.mapKey(code, actionToLearn.action, learningLongPress)
                resources.getString(requireNotNull(actionToLearn.action).labelRes)
            }
            mutableStatus.value = resources.getString(com.cabin.R.string.key_saved, KeyEvent.keyCodeToString(code), label)
            held.add(code)
            return true
        }
        val action = target(code, false)
        val longAction = target(code, true)
        if (action == null && longAction == null) return false
        held.add(code)
        downTimes[code] = nowMillis()
        if (action != null) actionsOnRelease[code] = action
        if (longAction != null) longActions[code] = longAction
        return true
    }

    private fun target(code: Int, longPress: Boolean): Target? =
        preferences.keyApp(code, longPress)?.let { Target(component = it) }
            ?: preferences.keyAction(code, longPress)?.let { Target(action = it) }

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
        TeyesKeyAction.BACK -> com.cabin.R.string.steering_back
        TeyesKeyAction.NONE -> com.cabin.R.string.steering_none
        TeyesKeyAction.LAUNCHER -> com.cabin.R.string.vehicle_key_launcher
        TeyesKeyAction.PLAY_PAUSE -> com.cabin.R.string.teyes_action_play_pause
        TeyesKeyAction.NEXT -> com.cabin.R.string.teyes_action_next
        TeyesKeyAction.PREVIOUS -> com.cabin.R.string.teyes_action_previous
        TeyesKeyAction.VOICE -> com.cabin.R.string.teyes_action_voice
        TeyesKeyAction.PAGE_NEXT -> com.cabin.R.string.layout_next_page
        TeyesKeyAction.PAGE_PREVIOUS -> com.cabin.R.string.layout_previous_page
        TeyesKeyAction.CLIMATE -> com.cabin.R.string.teyes_action_climate
        TeyesKeyAction.VOLUME_UP -> com.cabin.R.string.vehicle_volume_up
        TeyesKeyAction.VOLUME_DOWN -> com.cabin.R.string.vehicle_volume_down
        TeyesKeyAction.MUTE -> com.cabin.R.string.vehicle_mute
    }
