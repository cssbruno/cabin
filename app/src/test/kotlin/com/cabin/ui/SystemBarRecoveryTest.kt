package com.cabin.ui

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.cabin.ui.settings.DisplayMode
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
@LooperMode(LooperMode.Mode.PAUSED)
class SystemBarRecoveryTest {
    private fun advance() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

    @Test fun `late bottom bar reveal recovers even when status bar stays hidden`() {
        val view = View(ApplicationProvider.getApplicationContext<Context>())
        val hidden = mutableListOf<Int>()
        val recovery = SystemBarRecovery(view, { true }, hidden::add)
        try {
            recovery.update(DisplayMode.FULLSCREEN_IMMERSIVE)
            advance()
            hidden.clear()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, 48))
                .setVisible(WindowInsetsCompat.Type.statusBars(), false)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
            assertTrue("Swiped controls remain briefly available", hidden.isEmpty())
            advance()
            assertEquals(listOf(WindowInsetsCompat.Type.systemBars()), hidden)
        } finally { recovery.close() }
    }

    @Suppress("DEPRECATION")
    @Test fun `legacy dock reveal schedules another recovery after initial retry`() {
        val view = View(ApplicationProvider.getApplicationContext<Context>())
        val hidden = mutableListOf<Int>()
        val recovery = SystemBarRecovery(view, { true }, hidden::add)
        try {
            recovery.update(DisplayMode.NAV_BAR_HIDDEN)
            advance()
            hidden.clear()
            view.dispatchSystemUiVisibilityChanged(View.SYSTEM_UI_FLAG_FULLSCREEN)
            advance()
            assertEquals(listOf(WindowInsetsCompat.Type.navigationBars()), hidden)
        } finally { recovery.close() }
    }

    @Test fun `focus loss visible mode and disposal prevent queued hides`() {
        val view = View(ApplicationProvider.getApplicationContext<Context>())
        val hidden = mutableListOf<Int>()
        var focused = true
        val recovery = SystemBarRecovery(view, { focused }, hidden::add)
        try {
            recovery.update(DisplayMode.FULLSCREEN_IMMERSIVE)
            focused = false
            advance()
            assertTrue(hidden.isEmpty())
            focused = true
            recovery.update(DisplayMode.FULLSCREEN_IMMERSIVE)
            recovery.update(DisplayMode.SYSTEM_UI_VISIBLE)
            advance()
            assertTrue(hidden.isEmpty())
            recovery.update(DisplayMode.FULLSCREEN_IMMERSIVE)
            recovery.close()
            advance()
            assertTrue(hidden.isEmpty())
        } finally { recovery.close() }
    }
}
