package ai.respondo.sdk

import ai.respondo.sdk.core.CommandQueue
import ai.respondo.sdk.core.QueuedCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила command-queue (api-surface §7). */
class CommandQueueTest {

    @Test
    fun identify_coalescesToLast() {
        val q = CommandQueue()
        q.enqueue(QueuedCommand.Identify(RespondoIdentity(userId = "a")))
        q.enqueue(QueuedCommand.Track("event", emptyMap()))
        q.enqueue(QueuedCommand.Identify(RespondoIdentity(userId = "b")))
        val drained = q.drain()
        val identifies = drained.filterIsInstance<QueuedCommand.Identify>()
        assertEquals(1, identifies.size)
        assertEquals("b", identifies.first().identity.userId)
    }

    @Test
    fun track_isNotCoalesced_andFifoPreserved() {
        val q = CommandQueue()
        q.enqueue(QueuedCommand.Track("a", emptyMap()))
        q.enqueue(QueuedCommand.Open)
        q.enqueue(QueuedCommand.Track("b", emptyMap()))
        val drained = q.drain()
        assertEquals(3, drained.size)
        assertTrue(drained[0] is QueuedCommand.Track)
        assertTrue(drained[1] is QueuedCommand.Open)
        assertEquals("b", (drained[2] as QueuedCommand.Track).name)
    }

    @Test
    fun overflow_dropsOldestNonHandlePush_keepsHandlePush() {
        val q = CommandQueue()
        val push = RespondoPushPayload(conversationId = "c", messageId = "m", title = null, body = null, raw = emptyMap())
        q.enqueue(QueuedCommand.HandlePush(push))
        repeat(CommandQueue.MAX_SIZE) { q.enqueue(QueuedCommand.Track("t$it", emptyMap())) }
        assertEquals(CommandQueue.MAX_SIZE, q.size)
        val drained = q.drain()
        // handlePush пережил переполнение, самый старый track отброшен.
        assertTrue(drained.any { it is QueuedCommand.HandlePush })
    }

    @Test
    fun drain_clearsQueue() {
        val q = CommandQueue()
        q.enqueue(QueuedCommand.Open)
        q.drain()
        assertEquals(0, q.size)
    }
}
