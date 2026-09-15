package com.cabin.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CabinTelemetryTest {
    @Test fun `sanitization drops private messages context and breadcrumb payloads but preserves stack locations`() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "Phone AA:BB:CC:DD:EE:FF at -3.7,-38.5" }
            user = User().apply { email = "private@example.com" }
            request = Request().apply { url = "https://private.example/token" }
            setExtra("audio", "private")
            setTag("phone", "private")
            contexts["location"] = mapOf("lat" to -3.7)
            exceptions = listOf(SentryException().apply {
                type = "IllegalStateException"
                value = "SSID and phone address"
                module = "com.cabin.joying"
                stacktrace = SentryStackTrace(listOf(SentryStackFrame().apply {
                    module = "com.cabin.joying.JoyingEmbeddedSession"
                    function = "start"
                    filename = "/private/account/Session.kt"
                    absPath = "/private/account/Session.kt"
                    lineno = 42
                    vars = mapOf("phone" to "private")
                }))
            })
            breadcrumbs = listOf(
                Breadcrumb().apply { category = "http"; message = "private URL" },
                Breadcrumb().apply { category = "cabin.connection"; message = "JOYING_RETRY"; setData("phone", "private") },
                Breadcrumb().apply { category = "cabin.connection"; message = "raw vendor data" },
            )
        }
        val clean = CabinTelemetry.sanitize(event)
        assertNull(clean.user)
        assertNull(clean.request)
        assertNull(clean.extras)
        assertFalse(clean.tags.orEmpty().containsKey("phone"))
        assertFalse(clean.contexts.containsKey("location"))
        assertEquals("Cabin application error", clean.message!!.formatted)
        assertNull(clean.exceptions!!.single().value)
        val frame = clean.exceptions!!.single().stacktrace!!.frames!!.single()
        assertEquals("start", frame.function)
        assertEquals("Session.kt", frame.filename)
        assertEquals(42, frame.lineno)
        assertNull(frame.absPath)
        assertNull(frame.vars)
        assertEquals("JOYING_RETRY", clean.breadcrumbs!!.single().message)
        assertTrue(clean.breadcrumbs!!.single().data.isEmpty())
    }

    @Test fun `only fixed diagnostic messages survive`() {
        DiagnosticEvent.entries.forEach { kind ->
            val event = SentryEvent().apply { message = Message().apply { formatted = kind.name } }
            assertEquals(kind.name, CabinTelemetry.sanitize(event).message!!.formatted)
        }
    }

    @Test fun `reporting defaults off and disabling removes queued reports`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(CabinTelemetry.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        assertFalse(CabinTelemetry.enabled(context))
        val pending = File(context.noBackupFilesDir, "sentry/nested/pending")
        pending.parentFile!!.mkdirs()
        pending.writeText("queued")
        context.getSharedPreferences(CabinTelemetry.PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        CabinTelemetry.setEnabled(context, false)
        assertFalse(CabinTelemetry.enabled(context))
        assertFalse(File(context.noBackupFilesDir, "sentry").exists())
    }
}
