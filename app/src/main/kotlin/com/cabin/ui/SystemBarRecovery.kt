package com.cabin.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cabin.ui.settings.DisplayMode

internal fun hiddenSystemBars(mode: DisplayMode): Int = when (mode) {
    DisplayMode.FULLSCREEN_IMMERSIVE -> WindowInsetsCompat.Type.systemBars()
    DisplayMode.NAV_BAR_HIDDEN -> WindowInsetsCompat.Type.navigationBars()
    DisplayMode.STATUS_BAR_HIDDEN -> WindowInsetsCompat.Type.statusBars()
    DisplayMode.SYSTEM_UI_VISIBLE -> 0
}

/** Recover late OEM bar reveals without polling or changing the window's dimensions. */
@Suppress("DEPRECATION")
internal class SystemBarRecovery(
    private val decor: View,
    private val canRecover: () -> Boolean,
    private val hide: (Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var hiddenTypes = 0
    private var pending = false
    private var closed = false
    private val recover = Runnable {
        pending = false
        if (!closed && hiddenTypes != 0 && canRecover()) hide(hiddenTypes)
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            val visibleTypes =
                (if (insets.isVisible(WindowInsetsCompat.Type.statusBars())) WindowInsetsCompat.Type.statusBars() else 0) or
                (if (insets.isVisible(WindowInsetsCompat.Type.navigationBars())) WindowInsetsCompat.Type.navigationBars() else 0)
            if (visibleTypes and hiddenTypes != 0) schedule()
            insets
        }
        // Android 8/9 head units may restore legacy flags without dispatching new insets.
        decor.setOnSystemUiVisibilityChangeListener { flags ->
            val visibleTypes =
                (if (flags and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) WindowInsetsCompat.Type.statusBars() else 0) or
                (if (flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) WindowInsetsCompat.Type.navigationBars() else 0)
            if (visibleTypes and hiddenTypes != 0) schedule()
        }
    }

    fun update(mode: DisplayMode) {
        cancel()
        hiddenTypes = hiddenSystemBars(mode)
        schedule()
    }

    private fun schedule() {
        if (closed || pending || hiddenTypes == 0 || !canRecover()) return
        pending = true
        // Leave intentionally swiped-in controls usable before restoring immersive mode.
        handler.postDelayed(recover, 1_500)
    }

    fun cancel() {
        handler.removeCallbacks(recover)
        pending = false
    }

    fun close() {
        closed = true
        cancel()
        ViewCompat.setOnApplyWindowInsetsListener(decor, null)
        decor.setOnSystemUiVisibilityChangeListener(null)
    }
}
