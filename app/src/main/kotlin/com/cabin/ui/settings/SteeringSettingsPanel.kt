package com.cabin.ui.settings

import com.cabin.R
import androidx.compose.ui.res.stringResource
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cabin.platform.labelRes
import com.cabin.platform.LocalTeyesKeyRouter
import com.cabin.platform.TeyesFeaturePreferences
import com.cabin.platform.TeyesKeyAction
import kotlinx.coroutines.delay

@Composable
internal fun SteeringSettingsPanel() {
    val context = LocalContext.current
    val preferences = remember(context) { TeyesFeaturePreferences.get(context) }
    val router = LocalTeyesKeyRouter.current
    val keyStatus = router?.status?.collectAsStateWithLifecycle()?.value.orEmpty()
    var learnLongPress by remember { mutableStateOf(false) }
    var mappings by remember { mutableStateOf(preferences.mappedKeys()) }
    var learningAction by remember { mutableStateOf<TeyesKeyAction?>(null) }
    var learningToken by remember { mutableStateOf(0) }
    val shortcutRevision by preferences.revision.collectAsStateWithLifecycle()
    LaunchedEffect(keyStatus, shortcutRevision, learnLongPress) {
        mappings = preferences.mappedKeys(learnLongPress)
        if (router?.isLearning != true) learningAction = null
    }
    LaunchedEffect(learningAction, learningToken) {
        if (learningAction != null) {
            delay(15_000)
            router?.cancelLearning()
            learningAction = null
        }
    }
    DisposableEffect(router) { onDispose { router?.cancelLearning() } }
    SettingsDisclosure(stringResource(R.string.teyes_steering_shortcuts), androidx.compose.ui.res.pluralStringResource(R.plurals.teyes_learned_buttons, mappings.size, mappings.size), onCollapse = {
        learningAction = null
        router?.cancelLearning()
    }) {
        Text(stringResource(R.string.teyes_steering_detail))
        if (keyStatus.isNotEmpty()) SettingsNotice(keyStatus)
        if (router == null) SettingsNotice(stringResource(R.string.teyes_steering_unavailable))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !learnLongPress, onClick = { router?.cancelLearning(); learnLongPress = false },
                label = { Text(stringResource(R.string.layout_short_press)) })
            FilterChip(selected = learnLongPress, onClick = { router?.cancelLearning(); learnLongPress = true },
                label = { Text(stringResource(R.string.layout_long_press)) })
        }
        TeyesKeyAction.entries.forEach { action ->
            TextButton(onClick = {
                learningAction = action
                learningToken++
                router?.learn(action, learnLongPress)
            }, enabled = router != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(R.string.teyes_learn_action, stringResource(action.labelRes))) }
        }
        if (learningAction != null) {
            TextButton(onClick = {
                learningAction = null
                router?.cancelLearning()
            }, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.teyes_cancel_learning)) }
        }
        mappings.forEach { (code, action) ->
            TextButton(onClick = {
                preferences.mapKey(code, null, learnLongPress)
                mappings = preferences.mappedKeys(learnLongPress)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.teyes_remove_mapping, KeyEvent.keyCodeToString(code), stringResource(action.labelRes)))
            }
        }
    }
}
