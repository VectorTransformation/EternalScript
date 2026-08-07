package eternalScript.core.manager

import eternalScript.core.runtime.GlobalTaskOwner
import eternalScript.core.runtime.GlobalTaskQueue
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationShutdownTest {
    @Test
    fun `global shutdown pumps handoff that completes operation`() {
        val operation = Job()
        var pumps = 0

        val stopped = awaitOperationShutdown(
            operation = operation,
            timeoutMillis = 1_000,
            isGlobalThread = true
        ) {
            pumps += 1
            operation.complete()
        }

        assertTrue(stopped)
        assertTrue(operation.isCompleted)
        assertTrue(pumps > 0)
    }

    @Test
    fun `global shutdown drains handoff queued during noncancellable wait`() = runBlocking {
        val queue = GlobalTaskQueue()
        val owner = GlobalTaskOwner(1)
        assertTrue(owner.beginShutdownDrain(1))
        val transactionReady = CountDownLatch(1)
        val allowHandoff = CountDownLatch(1)
        val cleanupRan = AtomicBoolean()
        val operation = launch(Dispatchers.Default) {
            withContext(NonCancellable) {
                transactionReady.countDown()
                allowHandoff.await()
                suspendCancellableCoroutine { continuation ->
                    val task = queue.enqueue(
                        epoch = 1,
                        owner = owner,
                        action = {
                            cleanupRan.set(true)
                            continuation.resume(Unit)
                        }
                    )
                    continuation.invokeOnCancellation {
                        queue.cancel(task)
                    }
                }
            }
        }
        assertTrue(transactionReady.await(1, TimeUnit.SECONDS))
        operation.cancel()

        val stopped = awaitOperationShutdown(
            operation = operation,
            timeoutMillis = 1_000,
            isGlobalThread = true
        ) {
            allowHandoff.countDown()
            queue.drain(owner, GlobalTaskQueue.Task::run)
        }

        assertTrue(stopped)
        assertTrue(operation.isCompleted)
        assertTrue(cleanupRan.get())
    }

    @Test
    fun `global shutdown remains bounded when operation cannot complete`() {
        val operation: CompletableJob = Job()

        val stopped = awaitOperationShutdown(
            operation = operation,
            timeoutMillis = 1,
            isGlobalThread = true,
            pumpGlobalTasks = {}
        )

        assertFalse(stopped)
        operation.cancel()
    }
}
