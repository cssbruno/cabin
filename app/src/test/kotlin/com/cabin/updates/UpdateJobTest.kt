package com.cabin.updates

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateJobTest {
    @Test fun `daily checks persist and disabling cancels both jobs`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val updater = GitHubUpdater.get(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        updater.setAutomatic(true)
        val daily = scheduler.getPendingJob(UpdateJobService.PERIODIC_ID)!!
        assertTrue(daily.isPersisted)
        assertEquals(24 * 60 * 60 * 1000L, daily.intervalMillis)
        assertNotNull(scheduler.getPendingJob(UpdateJobService.STARTUP_ID))
        updater.setAutomatic(false)
        assertNull(scheduler.getPendingJob(UpdateJobService.PERIODIC_ID))
        assertNull(scheduler.getPendingJob(UpdateJobService.STARTUP_ID))
    }
}
