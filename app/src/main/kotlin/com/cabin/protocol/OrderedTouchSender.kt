package com.cabin.protocol

import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One USB touch consumer preserves gesture order without blocking the UI producer. */
internal class OrderedTouchSender(
    scope: CoroutineScope,
    private val onError: (Exception) -> Unit,
) {
    private data class Pending(
        val session: Any,
        val touches: List<MessageSerializer.TouchPoint>,
        val send: (List<MessageSerializer.TouchPoint>) -> Unit,
    ) {
        fun replaces(previous: Pending): Boolean =
            session === previous.session && touches.isNotEmpty() &&
                touches.size == previous.touches.size &&
                touches.indices.all { index ->
                    val point = touches[index]
                    val old = previous.touches[index]
                    point.action == MultiTouchAction.MOVE && old.action == MultiTouchAction.MOVE && point.id == old.id
                }
    }

    private val lock = Any()
    private val pending = ArrayDeque<Pending>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var closed = false
    private val worker = scope.launch(Dispatchers.IO) {
        try {
            for (signal in wakeup) {
                while (isActive) {
                    val next = synchronized(lock) { pending.pollFirst() } ?: break
                    try {
                        next.send(next.touches)
                    } catch (error: Exception) {
                        onError(error)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                closed = true
                pending.clear()
                wakeup.close()
            }
        }
    }

    fun submit(
        session: Any,
        touches: List<MessageSerializer.TouchPoint>,
        send: (List<MessageSerializer.TouchPoint>) -> Unit,
    ) {
        val next = Pending(session, touches.toList(), send)
        synchronized(lock) {
            if (closed || !worker.isActive) return
            // Replace only adjacent MOVE batches for the same fingers/session. DOWN/UP
            // boundaries are always retained, even while USB is temporarily busy.
            pending.peekLast()?.let { if (next.replaces(it)) pending.removeLast() }
            pending.addLast(next)
            wakeup.trySend(Unit)
        }
    }

    fun clear() = synchronized(lock) { pending.clear() }
}
