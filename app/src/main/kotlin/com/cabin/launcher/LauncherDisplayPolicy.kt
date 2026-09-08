package com.cabin.launcher

import com.cabin.ui.settings.DisplayMode

/** The launcher owns the whole display; explicit projection/compact windows retain their policy. */
internal fun launcherDisplayMode(requested: DisplayMode, dashboardSession: Boolean, compact: Boolean): DisplayMode =
    if (dashboardSession && !compact) DisplayMode.FULLSCREEN_IMMERSIVE else requested
