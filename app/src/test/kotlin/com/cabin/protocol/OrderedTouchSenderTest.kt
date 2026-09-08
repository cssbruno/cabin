package com.cabin.protocol

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedTouchSenderTest {
    @Test
    fun `busy USB keeps down move up ordered and replaces stale moves`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val downStarted = CountDownLatch(1)
        val unblockUsb = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val sent = Collections.synchronizedList(mutableListOf<MessageSerializer.TouchPoint>())
        val errors = Collections.synchronizedList(mutableListOf<Exception>())
        val sender = OrderedTouchSender(scope, errors::add)
        val session = Any()
        val send: (List<MessageSerializer.TouchPoint>) -> Unit = { touches ->
            val point = touches.single()
            if (point.action == MultiTouchAction.DOWN) {
                downStarted.countDown()
                check(unblockUsb.await(5, TimeUnit.SECONDS))
            }
            sent.add(point)
            if (point.action == MultiTouchAction.UP) complete.countDown()
        }
        try {
            sender.submit(session, listOf(point(MultiTouchAction.DOWN)), send)
            assertTrue(downStarted.await(5, TimeUnit.SECONDS))
            repeat(100) { index ->
                sender.submit(session, listOf(point(MultiTouchAction.MOVE, index / 100f)), send)
            }
            // The caller may reuse its list after enqueueing a MotionEvent.
            val up = mutableListOf(point(MultiTouchAction.UP, 1f))
            sender.submit(session, up, send)
            up.clear()
            unblockUsb.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(
                listOf(point(MultiTouchAction.DOWN), point(MultiTouchAction.MOVE, .99f), point(MultiTouchAction.UP, 1f)),
                sent.toList(),
            )
            assertTrue(errors.isEmpty())
        } finally {
            unblockUsb.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `coalescing never crosses gestures or sessions`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val complete = CountDownLatch(6)
        val sent = Collections.synchronizedList(mutableListOf<MultiTouchAction>())
        val sender = OrderedTouchSender(scope) { throw AssertionError(it) }
        val firstSession = Any()
        val secondSession = Any()
        try {
            sender.submit(firstSession, listOf(point(MultiTouchAction.DOWN))) {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val actions = listOf(
                MultiTouchAction.MOVE, MultiTouchAction.UP, MultiTouchAction.DOWN,
                MultiTouchAction.MOVE, MultiTouchAction.MOVE, MultiTouchAction.UP,
            )
            actions.forEachIndexed { index, action ->
                sender.submit(if (index < 4) firstSession else secondSession, listOf(point(action))) {
                    sent.add(it.single().action)
                    complete.countDown()
                }
            }
            unblock.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(actions, sent.toList())
        } finally {
            unblock.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `disconnect discards queued input before a new session`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val complete = CountDownLatch(1)
        val sent = Collections.synchronizedList(mutableListOf<String>())
        val sender = OrderedTouchSender(scope) { throw AssertionError(it) }
        try {
            val session = Any()
            sender.submit(session, listOf(point(MultiTouchAction.DOWN))) {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            sender.submit(session, listOf(point(MultiTouchAction.UP))) { sent.add("old") }
            sender.clear()
            sender.submit(Any(), listOf(point(MultiTouchAction.DOWN))) {
                sent.add("new")
                complete.countDown()
            }
            unblock.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("new"), sent.toList())
        } finally {
            unblock.countDown()
            scope.cancel()
        }
    }

    private fun point(action: MultiTouchAction, x: Float = 0f) = MessageSerializer.TouchPoint(x, 0f, action, 0)
}
