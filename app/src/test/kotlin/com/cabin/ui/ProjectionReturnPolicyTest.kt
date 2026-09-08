package com.cabin.ui

import com.cabin.CabinManager.State
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectionReturnPolicyTest {
    @Test fun `automatic reconnect never navigates without an explicit request`() {
        State.entries.forEach { assertEquals(ProjectionReturnDecision.WAIT, projectionReturnDecision(null, 10_000, it, true)) }
    }

    @Test fun `only video ready consumes a recent explicit request`() {
        State.entries.filterNot { it == State.STREAMING }.forEach {
            assertEquals(ProjectionReturnDecision.WAIT, projectionReturnDecision(1_000, 10_000, it, true))
        }
        assertEquals(ProjectionReturnDecision.RETURN, projectionReturnDecision(1_000, 10_000, State.STREAMING, true))
    }

    @Test fun `leaving hub changing settings losing focus or backgrounding cancels even if streaming`() {
        State.entries.forEach { assertEquals(ProjectionReturnDecision.CANCEL, projectionReturnDecision(1_000, 10_000, it, false)) }
    }

    @Test fun `expired and reversed monotonic requests never steal focus later`() {
        assertEquals(ProjectionReturnDecision.CANCEL, projectionReturnDecision(1_000, 121_001, State.STREAMING, true))
        assertEquals(ProjectionReturnDecision.CANCEL, projectionReturnDecision(1_000, 999, State.STREAMING, true))
        assertEquals(ProjectionReturnDecision.RETURN, projectionReturnDecision(1_000, 121_000, State.STREAMING, true))
    }
}
