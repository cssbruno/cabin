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
        assertTrue(error.message!!.contains("firmware denied"))
    }

    @Test fun `persistent conflict stops retrying after bounded wait`() {
        var binds = 0
        var releases = 0
        val error = assertThrows(IllegalStateException::class.java) {
            JoyingVideoConnection.open({ binds++; throw IOException("Address already in use") }, { releases++ }, {})
        }
        assertEquals(11, binds)
        assertEquals(1, releases)
        assertTrue(error.message!!.contains("still in use"))
    }

    @Test fun `unknown IO failure is not treated as ownership conflict`() {
        val error = assertThrows(IllegalStateException::class.java) {
            JoyingVideoConnection.open({ throw IOException("Too many open files") }, { fail("Must not stop service") }, {})
        }
        assertTrue(error.message!!.contains("Too many open files"))
    }

    @Test fun `cancelled retry does not bind again`() {
        var binds = 0
        assertThrows(InterruptedException::class.java) {
            JoyingVideoConnection.open({ binds++; throw IOException("EADDRINUSE") }, {}, { throw InterruptedException() })
        }
        assertEquals(1, binds)
    }
}
