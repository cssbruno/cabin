package com.cabin.updates

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.cabin.BuildConfig
import kotlinx.coroutines.*

class UpdateJobService : JobService() {
    private var job: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        job = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            val updater = GitHubUpdater.get(this@UpdateJobService)
            if (updater.automatic) updater.checkAndDownload()
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { job?.cancel(); return true }
    override fun onDestroy() { job?.cancel(); super.onDestroy() }
    companion object {
        private const val JOB_ID = 0x434155
        fun schedule(context: Context) {
            if (!BuildConfig.TEYES_CLUSTER_MEDIA_BRIDGE) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val updater = GitHubUpdater.get(context)
            if (!updater.automatic || !validUpdateRepository(updater.repository)) { scheduler.cancel(JOB_ID); return }
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setPeriodic(24 * 60 * 60 * 1000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true).build())
        }
    }
}
