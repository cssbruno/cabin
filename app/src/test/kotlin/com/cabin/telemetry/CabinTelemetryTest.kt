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

    @Test fun `log sanitizer rejects arbitrary bodies and strips SDK attributes`() {
        val log = io.sentry.SentryLogEvent(SentryId(), 1.0, "Cabin log com.cabin.joying.Session.start:42", io.sentry.SentryLogLevel.DEBUG)
        log.setAttribute("user.email", io.sentry.SentryLogEventAttributeValue("string", "private@example.com"))
        val clean = CabinTelemetry.cleanLog(log)!!
        assertEquals(log.body, clean.body)
        assertEquals(io.sentry.SentryLogLevel.DEBUG, clean.level)
        assertEquals(setOf("sentry.release", "sentry.environment"), clean.attributes!!.keys)
        log.body = "Phone connected private@example.com"
        assertNull(CabinTelemetry.cleanLog(log))
    }

    @Test fun `CarPlay journal keeps meaningful stages and bounded measurements`() {
        val body = CabinTelemetry.journalBody("CarPlay", "first_frame_received", "bytes=4096")!!
        assertEquals("Cabin diagnostic CarPlay/first_frame_received bytes=4096", body)
        val log = io.sentry.SentryLogEvent(SentryId(), 1.0, body, io.sentry.SentryLogLevel.DEBUG)
        assertEquals(body, CabinTelemetry.cleanLog(log)!!.body)
        val crumb = Breadcrumb().apply { category = "cabin.diagnostic"; message = body; setData("phone", "private") }
        val clean = CabinTelemetry.sanitize(SentryEvent().apply { breadcrumbs = listOf(crumb) }).breadcrumbs!!.single()
        assertEquals(body, clean.message)
        assertTrue(clean.data.isEmpty())
        assertEquals("Cabin diagnostic CarPlay/connection_failed",
            CabinTelemetry.journalBody("CarPlay", "connection_failed", "private phone and SSID"))
        assertEquals("Cabin diagnostic CarPlay/link_state state=2",
            CabinTelemetry.journalBody("CarPlay", "link_state", "state=2"))
    }

    @Test fun `journal rejects unknown events and does not leak arbitrary diagnostic details`() {
        assertNull(CabinTelemetry.journalBody("CAN", "callback", "field=1; value=42"))
        assertNull(CabinTelemetry.journalBody("phone", "connected", "private"))
        assertEquals("Cabin diagnostic CarPlay/link_state",
            CabinTelemetry.journalBody("CarPlay", "link_state", "state=2 private"))
        val log = io.sentry.SentryLogEvent(SentryId(), 1.0,
            "Cabin diagnostic CarPlay/link_state state=2 private", io.sentry.SentryLogLevel.DEBUG)
        assertNull(CabinTelemetry.cleanLog(log))
        assertNull(CabinTelemetry.cleanBreadcrumb(Breadcrumb().apply {
            category = "cabin.diagnostic"; message = "Cabin diagnostic CarPlay/private"
        }))
    }

    @Test fun `native addresses build IDs and crashed threads survive without private fields`() {
        val nativeFrame = SentryStackFrame().apply { instructionAddr = "0x1234"; imageAddr = "0x1000"; vars = mapOf("secret" to "value") }
        val event = SentryEvent().apply {
            platform = "native"
            debugMeta = DebugMeta().apply { images = listOf(DebugImage().apply {
                type = "elf"; debugId = "abcd"; codeFile = "/private/path/libcabin_io.so"; imageAddr = "0x1000"
            }) }
            threads = listOf(SentryThread().apply { id = 2; isCrashed = true; name = "private"; stacktrace = SentryStackTrace(listOf(nativeFrame)) })
            exceptions = listOf(SentryException().apply { mechanism = Mechanism().apply { type = "ANR"; isHandled = false } })
        }
        val clean = CabinTelemetry.sanitize(event)
        assertEquals("native", clean.platform)
        assertEquals("abcd", clean.debugMeta!!.images!!.first().debugId)
        assertEquals("libcabin_io.so", clean.debugMeta!!.images!!.first().codeFile)
        val thread = clean.threads!!.single()
        assertTrue(thread.isCrashed!!)
        assertNull(thread.name)
        assertEquals("0x1234", thread.stacktrace!!.frames!!.single().instructionAddr)
        assertNull(thread.stacktrace!!.frames!!.single().vars)
        assertEquals("ANR", clean.exceptions!!.single().mechanism!!.type)
    }

    @Test fun `cached crashes retain their original release and mapping`() {
        val event = SentryEvent().apply {
            release = "zeno.carlink@0.1+2000131"
            environment = "production"
            debugMeta = DebugMeta().apply { images = listOf(DebugImage().apply {
                type = "proguard"; uuid = "decc9a24-92cd-4274-8acf-7da0de64b6bc"
            }) }
        }
        val clean = CabinTelemetry.sanitize(event)
        assertEquals(event.release, clean.release)
        assertEquals("production", clean.environment)
        assertEquals("decc9a24-92cd-4274-8acf-7da0de64b6bc", clean.debugMeta!!.images!!.single().uuid)
    }

    @Test fun `warning messages omit reporting stacks while real exceptions retain theirs`() {
        val options = io.sentry.SentryOptions().apply { CabinTelemetry.configureStackCapture(this) }
        io.sentry.MainEventProcessor(options).use { processor ->
            val warning = SentryEvent().apply { message = Message().apply { formatted = "JOYING_EXHAUSTED" } }
            val processed = processor.process(warning, io.sentry.Hint())
            assertTrue(processed.threads.isNullOrEmpty())
            assertTrue(processed.exceptions.isNullOrEmpty())
            val error = processor.process(SentryEvent(IllegalStateException("private error")), io.sentry.Hint())
            assertTrue(error.exceptions.orEmpty().any { !it.stacktrace?.frames.isNullOrEmpty() })
        }
    }

    @Test fun `safe socket reason and breadcrumb severity survive privacy filtering`() {
        val event = SentryEvent().apply {
            setTag("joying_video_failure", "BUSY")
            breadcrumbs = listOf(Breadcrumb().apply {
                category = "cabin.log"; message = "Cabin log com.cabin.joying.Session.start:42"; level = io.sentry.SentryLevel.ERROR
            })
        }
        val clean = CabinTelemetry.sanitize(event)
        assertEquals("BUSY", clean.getTag("joying_video_failure"))
        assertEquals(io.sentry.SentryLevel.ERROR, clean.breadcrumbs!!.single().level)
        event.setTag("joying_video_failure", "private vendor text")
        assertNull(CabinTelemetry.sanitize(event).getTag("joying_video_failure"))
    }

    @Test fun `log budget bounds bursts and resets on monotonic window boundary`() {
        val budget = TelemetryBudget(2)
        assertTrue(budget.take(10)); assertTrue(budget.take(11)); assertFalse(budget.take(12))
        assertFalse(budget.take(60_009)); assertTrue(budget.take(60_010))
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
