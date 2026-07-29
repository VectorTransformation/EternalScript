package eternalScript.core.the

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GlobalTaskQueueTest {
    @Test
    fun `scheduled execution and shutdown drain race exactly once`() {
        val queue = GlobalTaskQueue()
        val owner = GlobalTaskOwner(1)
        var calls = 0
        val task = queue.enqueue(1, owner, { calls += 1 })

        assertEquals(1, queue.drain(owner, GlobalTaskQueue.Task::run))
        assertEquals(null, queue.claim(task))
        assertEquals(1, calls)
    }

    @Test
    fun `scheduled execution removes task before shutdown drain`() {
        val queue = GlobalTaskQueue()
        val owner = GlobalTaskOwner(1)
        var calls = 0
        val task = queue.enqueue(1, owner, { calls += 1 })

        assertTrue(queue.claim(task)?.let { claimed ->
            claimed.run()
            true
        } == true)
        assertEquals(0, queue.drain(owner, GlobalTaskQueue.Task::run))
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled handoff is never executed`() {
        val queue = GlobalTaskQueue()
        val owner = GlobalTaskOwner(1)
        var calls = 0
        val task = queue.enqueue(1, owner, { calls += 1 })

        assertTrue(queue.cancel(task))
        assertEquals(null, queue.claim(task))
        assertEquals(0, queue.drain(owner, GlobalTaskQueue.Task::run))
        assertEquals(0, calls)
    }

    @Test
    fun `shutdown drain executes only the selected operation owner`() {
        val queue = GlobalTaskQueue()
        val selected = GlobalTaskOwner(1)
        val unrelated = GlobalTaskOwner(1)
        var selectedCalls = 0
        var unrelatedCalls = 0
        queue.enqueue(1, selected, { selectedCalls += 1 })
        val unrelatedTask = queue.enqueue(1, unrelated, { unrelatedCalls += 1 })

        assertEquals(1, queue.drain(selected, GlobalTaskQueue.Task::run))
        assertEquals(1, selectedCalls)
        assertEquals(0, unrelatedCalls)

        queue.claim(unrelatedTask)?.run()
        assertEquals(1, unrelatedCalls)
    }

    @Test
    fun `closing an owner rejects and purges its pending handoffs`() {
        val queue = GlobalTaskQueue()
        val owner = GlobalTaskOwner(1)
        var rejected = 0
        val task = queue.enqueue(
            epoch = 1,
            owner = owner,
            action = {},
            rejection = { rejected += 1 }
        )

        owner.close()
        assertEquals(1, queue.rejectOwner(owner) { IllegalStateException("closed") })
        assertEquals(1, rejected)
        assertEquals(null, queue.claim(task))
    }

    @Test
    fun `owner rejects a restarted lifecycle epoch`() {
        val owner = GlobalTaskOwner(1)

        assertTrue(owner.allows(GlobalLifecycleSnapshot(epoch = 1, open = true)))
        assertFalse(owner.allows(GlobalLifecycleSnapshot(epoch = 2, open = true)))
        assertTrue(owner.beginShutdownDrain(currentEpoch = 1))
        assertTrue(owner.allows(GlobalLifecycleSnapshot(epoch = 1, open = false)))
        owner.close()
        assertFalse(owner.allows(GlobalLifecycleSnapshot(epoch = 1, open = false)))
    }
}
