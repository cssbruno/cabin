package com.cabin.launcher

import com.cabin.ui.settings.DisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherDisplayPolicyTest {
    @Test fun `dashboard overrides saved modes that would expose Android bars`() {
        DisplayMode.entries.forEach {
            assertEquals(DisplayMode.FULLSCREEN_IMMERSIVE, launcherDisplayMode(it, dashboardSession = true, compact = false))
        }
    }
    @Test fun `explicit projection and compact windows retain their requested display policy`() {
        DisplayMode.entries.forEach {
            assertEquals(it, launcherDisplayMode(it, dashboardSession = false, compact = false))
            assertEquals(it, launcherDisplayMode(it, dashboardSession = true, compact = true))
        }
    }
}
