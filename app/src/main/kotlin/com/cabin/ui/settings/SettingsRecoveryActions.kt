package com.cabin.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Capture the explicit restart intent in this click, then finish the stop/start gap even if
 * Settings is removed. CabinManager.restart still honors a later Stop or manager release.
 * Do not use this for background retries: only an explicit user recovery action owns this work.
 */
internal fun launchSettingsRestart(
    scope: CoroutineScope,
    restart: suspend () -> Unit,
    onFinished: () -> Unit,
): Job =
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) {
            try {
                restart()
            } finally {
                onFinished()
            }
        }
    }

internal fun settingsControlColumns(
    widthDp: Float,
    fontScale: Float,
): Int = if (widthDp >= 800f * fontScale.coerceAtLeast(1f)) 2 else 1

internal fun canResetAndroidClusterHost(
    automotive: Boolean,
    templatesHost: Boolean,
): Boolean = automotive && templatesHost
