package com.cabin.platform

import android.os.Binder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SyuFrameworkProbeTest {
    @Test fun `absent framework is distinct from restricted access`() {
        assertEquals(SyuFrameworkAccess.ABSENT, SyuFrameworkProbe.inspectEndpoint { null })
        assertEquals(SyuFrameworkAccess.RESTRICTED, SyuFrameworkProbe.inspectEndpoint { throw SecurityException() })
        assertEquals(SyuFrameworkAccess.RESTRICTED, SyuFrameworkProbe.inspectEndpoint { throw NoSuchMethodException() })
    }
    @Test fun `only exact toolkit descriptor is recognized without module calls`() {
        val toolkit = Binder().apply { attachInterface(null, "com.syu.ipc.IRemoteToolkit") }
        assertEquals(SyuFrameworkAccess.TOOLKIT_PRESENT, SyuFrameworkProbe.inspectEndpoint { toolkit })
        val unrelated = Binder().apply { attachInterface(null, "com.syu.ipc.IRemoteModule") }
        assertEquals(SyuFrameworkAccess.WRONG_INTERFACE, SyuFrameworkProbe.inspectEndpoint { unrelated })
    }
    @Test fun `framework faults are reported without leaking exception details`() {
        assertEquals(SyuFrameworkAccess.FAILED, SyuFrameworkProbe.inspectEndpoint { throw IllegalStateException("private payload") })
        assertEquals(SyuFrameworkAccess.RESTRICTED, SyuFrameworkProbe.inspectEndpoint {
            throw java.lang.reflect.InvocationTargetException(SecurityException("restricted"))
        })
    }
}
