package com.cabin.joying

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class JoyingVideoConnectionTest {
    @Test fun `free socket does not stop stock service`() {
        assertEquals("socket", JoyingVideoConnection.open({ "socket" }, { fail("Unnecessary handoff") }, { fail("Unnecessary delay") }))
    }

    @Test fun `busy socket waits for asynchronous stock cleanup and recovers`() {
        var binds = 0
        var releases = 0
        var pauses = 0
        val result = JoyingVideoConnection.open(
            { if (++binds < 4) throw IOException("bind failed: EADDRINUSE") else "socket" },
            { releases++ }, { pauses++ },
        )
        assertEquals("socket", result)
        assertEquals(1, releases)
        assertEquals(3, pauses)
    }

    @Test fun `permission denial preserves cause without handoff`() {
        val denied = IOException("bind failed", IOException("EACCES (Permission denied)"))
        val error = assertThrows(IllegalStateException::class.java) {
            JoyingVideoConnection.open({ throw denied }, { fail("Must not stop service") }, { fail("Must not retry") })
        }
        assertSame(denied, error.cause)
        assertEquals(JoyingVideoConnection.Failure.DENIED, (error as JoyingVideoConnection.ConnectionException).reason)
        assertFalse(error.retryable)
        assertTrue(error.message!!.contains("firmware denied"))
    }

    @Test fun `persistent conflict stops retrying after bounded wait`() {
        var binds = 0
        var releases = 0
        val error = assertThrows(IllegalStateException::class.java) {
            JoyingVideoConnection.open({ binds++; throw IOException("Address already in use") }, { releases++ }, {})
        }
        assertEquals(JoyingVideoConnection.Failure.BUSY, (error as JoyingVideoConnection.ConnectionException).reason)
        assertFalse(error.retryable)
        assertEquals(11, binds)
        assertEquals(1, releases)
        assertTrue(error.message!!.contains("still in use"))
    }

    @Test fun `unknown IO failure is not treated as ownership conflict`() {
        val error = assertThrows(IllegalStateException::class.java) {
            JoyingVideoConnection.open({ throw IOException("Too many open files") }, { fail("Must not stop service") }, {})
        }
        assertTrue(error.message!!.contains("Too many open files"))
        assertTrue((error as JoyingVideoConnection.ConnectionException).retryable)
    }

    @Test fun `blocked handoff is permanent and preserves its cause`() {
        val denied = SecurityException("private vendor text")
        val error = assertThrows(JoyingVideoConnection.ConnectionException::class.java) {
            JoyingVideoConnection.open({ throw IOException("EADDRINUSE") }, { throw denied }, {})
        }
        assertEquals(JoyingVideoConnection.Failure.HANDOFF_BLOCKED, error.reason)
        assertFalse(error.retryable)
        assertSame(denied, error.cause)
    }

    @Test fun `manual force stop permits a new attempt without privileged handoff`() {
        var occupied = true
        var releaseAttempts = 0
        val bind = { if (occupied) throw IOException("EADDRINUSE") else "socket" }
        val release: () -> Unit = { releaseAttempts++; throw SecurityException("Not privileged") }
        assertThrows(JoyingVideoConnection.ConnectionException::class.java) {
            JoyingVideoConnection.open(bind, release, {})
        }
        occupied = false // User stops Car Link through Android app settings.
        assertEquals("socket", JoyingVideoConnection.open(bind, release, {}))
        assertEquals(1, releaseAttempts)
    }

    @Test fun `interrupted handoff remains cancellation`() {
        assertThrows(InterruptedException::class.java) {
            JoyingVideoConnection.open({ throw IOException("EADDRINUSE") }, { throw InterruptedException() }, {})
        }
    }

    @Test fun `cancelled retry does not bind again`() {
        var binds = 0
        assertThrows(InterruptedException::class.java) {
            JoyingVideoConnection.open({ binds++; throw IOException("EADDRINUSE") }, {}, { throw InterruptedException() })
        }
        assertEquals(1, binds)
    }
}
