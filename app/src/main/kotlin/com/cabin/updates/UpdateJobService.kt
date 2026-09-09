package com.cabin.updates

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.*

class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = scope.launch {
            val updater = GitHubUpdater.get(this@UpdateJobService)
            if (updater.automatic) updater.check()
            jobFinished(params, updater.state.value.phase == UpdatePhase.FAILED)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        internal const val PERIODIC_ID = 0x434155
        internal const val STARTUP_ID = 0x434156
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val updater = GitHubUpdater.get(context)
            if (!updater.automatic) { scheduler.cancel(PERIODIC_ID); scheduler.cancel(STARTUP_ID); return }
            val component = ComponentName(context, UpdateJobService::class.java)
            if (scheduler.getPendingJob(PERIODIC_ID) == null) scheduler.schedule(JobInfo.Builder(PERIODIC_ID, component)
                .setPeriodic(24 * 60 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
            val age = System.currentTimeMillis() - updater.lastCheck
            if (age < 0 || age >= 24 * 60 * 60 * 1000L) scheduler.schedule(JobInfo.Builder(STARTUP_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(1000).build())
        }
    }
}
