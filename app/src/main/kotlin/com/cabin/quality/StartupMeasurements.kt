package com.cabin.quality

import android.app.Activity
import android.os.SystemClock
import android.view.ViewTreeObserver
import com.cabin.BuildConfig
import org.json.JSONObject
import java.io.File

/** Fixed-label timing only, compiled away from ordinary debug and production release builds. */
object StartupMeasurements {
    private var previous = 0L
    private val stages = linkedMapOf<String, Long>()
    @Synchronized fun mark(label: String) {
        if (BuildConfig.BUILD_TYPE != "releaseCheck") return
        val now = SystemClock.elapsedRealtimeNanos()
        if (previous > 0) stages[label] = (now - previous) / 1_000_000
        previous = now
    }
    fun activityCreated(activity: Activity) {
        if (BuildConfig.BUILD_TYPE != "releaseCheck") return
        mark("activity dispatch")
        val view = activity.window.decorView
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
                mark("activity creation to first draw")
                activity.reportFullyDrawn()
                val snapshot = synchronized(this@StartupMeasurements) { JSONObject(stages.toMap()).toString(2) }
                Thread({
                    runCatching { File(activity.applicationContext.getExternalFilesDir(null), "quality/startup-phases.json").apply { parentFile!!.mkdirs(); writeText(snapshot) } }
                }, "CabinStartupEvidence").start()
                return true
            }
        })
    }
}
