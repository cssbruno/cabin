package com.cabin.platform

import com.cabin.CabinManager.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionHealthStoreTest {
    @Test fun `stream end freezes bounded context without retaining media`() {
        var now = 0L
        val store = ProjectionHealthStore { now }
        repeat(100) {
            store.connection(State.STREAMING)
            store.media("private title", "private artist", true)
            now += 100
            store.event(ProjectionEventKind.USB_DETACHED)
            store.connection(State.DISCONNECTED)
        }
        assertEquals(ProjectionHealthStore.MAX_EVENTS, store.state.value.events.size)
        assertEquals(ProjectionHealthStore.MAX_INCIDENTS, store.state.value.incidents.size)
        assertTrue(store.state.value.incidents.all { it.events.size <= ProjectionHealthStore.MAX_EVENTS })
        assertFalse(store.state.value.incidents.toString().contains("private"))
        val frozen = store.state.value.incidents.last()
        store.event(ProjectionEventKind.USER_CONNECT)
        assertEquals(frozen, store.state.value.incidents.last())
    }

    @Test fun `return duration measures observed time and includes intentional waiting`() {
        var now = 0L
        val store = ProjectionHealthStore { now }
        store.connection(State.STREAMING)
        now = 1000
        store.event(ProjectionEventKind.USER_DISCONNECT)
        store.connection(State.DISCONNECTED)
        now = 5000
        store.connection(State.CONNECTING)
        now = 9000
        store.connection(State.STREAMING)
        assertEquals(8000L, store.state.value.lastRecoveryDurationMs)
        assertEquals(ProjectionEventKind.USER_DISCONNECT, store.state.value.incidents.single().events.single().kind)
    }

    @Test fun `old events fall out of incident context and clocks never reverse`() {
        var now = 0L
        val store = ProjectionHealthStore { now }
        store.event(ProjectionEventKind.USER_CONNECT)
        store.connection(State.STREAMING)
        now = 61000
        store.event(ProjectionEventKind.USB_DETACHED)
        now = 100
        store.connection(State.DISCONNECTED)
        assertEquals(listOf(ProjectionEventKind.USB_DETACHED), store.state.value.incidents.single().events.map { it.kind })
        assertEquals(61000L, store.state.value.incidents.single().elapsedMs)
    }

    @Test fun `history is bounded and repeated state adds nothing`() {
        var now = 0L
        val store = ProjectionHealthStore { now }
        repeat(100) {
            now++
            store.connection(State.CONNECTING)
            store.connection(State.CONNECTING)
            store.connection(State.DISCONNECTED)
        }
        assertEquals(40, store.state.value.transitions.size)
        assertEquals(100, store.state.value.disconnectTransitions)
        assertEquals(100L, store.state.value.transitions.last().elapsedMs)
    }

    @Test fun `reconnect clears metadata and ignores disconnected updates`() {
        val store = ProjectionHealthStore { 0 }
        store.connection(State.STREAMING)
        store.media("Song", "Artist", true)
        assertEquals("Song", store.state.value.title)
        store.connection(State.CONNECTING)
        assertEquals("", store.state.value.title)
        assertFalse(store.state.value.playing)
        store.media("stale", "stale", true)
        assertEquals("", store.state.value.title)
    }

    @Test fun `display text is bounded and strips control direction overrides`() {
        assertEquals("hello", ProjectionHealthStore.displayText("\u202ehello\u0000"))
        assertEquals(160, ProjectionHealthStore.displayText("x".repeat(1000)).length)
    }

    @Test fun `timeline cannot run backwards`() {
        var now = 100L
        val store = ProjectionHealthStore { now }
        now = 200
        store.connection(State.CONNECTING)
        now = 50
        store.connection(State.STREAMING)
        assertTrue(store.state.value.transitions.zipWithNext().all { it.first.elapsedMs <= it.second.elapsedMs })
    }
}
