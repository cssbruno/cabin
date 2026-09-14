package com.cabin

import android.app.Activity
import java.lang.ref.WeakReference

/** Main-thread ownership across launcher aliases and OEM task/multi-window launches. */
internal class ActivityInstanceOwner<T : Activity> {
    private var owner = WeakReference<T>(null)

    fun claim(activity: T): T? {
        val existing = owner.get()
        if (existing != null && existing !== activity && !existing.isFinishing && !existing.isDestroyed) {
            return existing
        }
        owner = WeakReference(activity)
        return null
    }

    fun release(activity: T) {
        if (owner.get() === activity) owner.clear()
    }
}
