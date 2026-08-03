package eternalScript.core.script.runtime

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptTaskScopeTest {
    @Test
    fun `generation coroutine is registered before it starts`() = runBlocking {
        val manager = ScriptTaskScope()
        val entered = CompletableDeferred<Unit>()
        manager.open()
        val job = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            entered.complete(Unit)
        }

        manager.trackAndStart(job)

        entered.await()
        job.join()
        assertTrue(job.isCompleted)
    }

    @Test
    fun `lazy generation coroutine cannot start after task manager closes`() = runBlocking {
        val manager = ScriptTaskScope()
        val entered = AtomicBoolean(false)
        val job = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
            entered.set(true)
        }

        manager.trackAndStart(job)
        job.join()

        assertFalse(entered.get())
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cooperative tracked job is cancelled and drained`() = runBlocking {
        val manager = ScriptTaskScope()
        val entered = CompletableDeferred<Unit>()
        manager.open()
        val job = launch(Dispatchers.Default) {
            entered.complete(Unit)
            awaitCancellation()
        }
        manager.track(job)
        entered.await()

        assertTrue(manager.cancelTrackedWorkAndJoin(1_000))
        assertTrue(job.isCancelled)
    }

    @Test
    fun `non cooperative tracked job reports drain timeout`() = runBlocking {
        val manager = ScriptTaskScope()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        manager.open()
        val job = launch(Dispatchers.Default) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    release.await()
                }
            }
        }
        manager.track(job)
        entered.await()

        assertFalse(manager.cancelTrackedWorkAndJoin(10))

        release.complete(Unit)
        job.join()
    }

    @Test
    fun `cancel waits for an already running bukkit task`() = runBlocking {
        val running = AtomicBoolean(true)
        val manager = ScriptTaskScope(taskIsRunning = { running.get() })
        val task = FakeBukkitTask()
        manager.open()
        manager.track(task)
        launch {
            delay(50)
            running.set(false)
        }

        assertTrue(manager.cancelTrackedWorkAndJoin(1_000))
        assertTrue(task.isCancelled)
    }

    @Test
    fun `running bukkit task reports drain timeout`() = runBlocking {
        val manager = ScriptTaskScope(taskIsRunning = { true })
        val task = FakeBukkitTask()
        manager.open()
        manager.track(task)

        assertFalse(manager.cancelTrackedWorkAndJoin(10))
        assertTrue(task.isCancelled)
    }

    @Test
    fun `task submitted after close remains tracked until execution stops`() = runBlocking {
        val running = AtomicBoolean(true)
        val manager = ScriptTaskScope(
            taskIsRunning = { running.get() },
            taskIsLive = { running.get() }
        )
        val task = FakeBukkitTask()

        manager.track(task)

        assertFalse(manager.cancelTrackedWorkAndJoin(10))
        assertTrue(task.isCancelled)

        running.set(false)
        assertTrue(manager.cancelTrackedWorkAndJoin(1_000))
    }

    @Test
    fun `completed bukkit tasks are pruned while generation remains active`() = runBlocking {
        val manager = ScriptTaskScope(
            taskIsRunning = { task -> task.taskId == 1 },
            taskIsLive = { task -> task.taskId != 1 }
        )
        manager.open()
        manager.track(FakeBukkitTask(taskId = 1))
        manager.track(FakeBukkitTask(taskId = 2))

        assertTrue(manager.cancelTrackedWorkAndJoin(1_000))
    }

    @Test
    fun `cancel waits for an already running folia task`() = runBlocking {
        val task = FakeScheduledTask(ScheduledTask.ExecutionState.RUNNING)
        val manager = ScriptTaskScope()
        manager.open()
        manager.track(task)
        launch {
            delay(50)
            task.finishCancellation()
        }

        assertTrue(manager.cancelTrackedWorkAndJoin(1_000))
        assertTrue(task.cancelCalled.get())
    }

    private class FakeBukkitTask(
        private val taskId: Int = 1
    ) : BukkitTask {
        private val cancelled = AtomicBoolean(false)

        override fun getTaskId() = taskId

        override fun getOwner(): Plugin =
            error("The task owner is not used by this test.")

        override fun isSync() = false

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isCancelled() = cancelled.get()
    }

    private class FakeScheduledTask(
        initialState: ScheduledTask.ExecutionState
    ) : ScheduledTask {
        private val state = AtomicReference(initialState)
        val cancelCalled = AtomicBoolean(false)

        override fun getOwningPlugin(): Plugin =
            error("The task owner is not used by this test.")

        override fun isRepeatingTask() = false

        override fun cancel(): ScheduledTask.CancelledState {
            cancelCalled.set(true)
            return when (state.get()) {
                ScheduledTask.ExecutionState.RUNNING,
                ScheduledTask.ExecutionState.CANCELLED_RUNNING -> {
                    state.set(ScheduledTask.ExecutionState.CANCELLED_RUNNING)
                    ScheduledTask.CancelledState.RUNNING
                }

                ScheduledTask.ExecutionState.IDLE -> {
                    state.set(ScheduledTask.ExecutionState.CANCELLED)
                    ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                }

                ScheduledTask.ExecutionState.CANCELLED ->
                    ScheduledTask.CancelledState.CANCELLED_ALREADY

                ScheduledTask.ExecutionState.FINISHED ->
                    ScheduledTask.CancelledState.ALREADY_EXECUTED
            }
        }

        override fun getExecutionState() = state.get()

        fun finishCancellation() {
            state.set(ScheduledTask.ExecutionState.CANCELLED)
        }
    }
}
