package eternalScript.core.manager

import eternalScript.core.feedback.UserFeedback
import eternalScript.core.feedback.UserFeedbackEvent
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.operation.ScriptOperationState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScriptOperationControllerTest {
    @Test
    fun `operations serialize and completion releases the next operation`() = runBlocking {
        val lifecycle = ProjectLifecycleFence().apply { open() }
        val runtime = FakeOperationRuntime()
        val events = mutableListOf<UserFeedbackEvent>()
        val idleCalls = AtomicInteger()
        val controller = ScriptOperationController(
            lifecycle = lifecycle,
            runtime = runtime,
            logger = Logger.getAnonymousLogger(),
            emit = { feedback, event -> feedback.emit(event) },
            onIdle = { idleCalls.incrementAndGet() }
        )
        val feedback = UserFeedback(events::add)
        val release = CompletableDeferred<Unit>()

        assertTrue(
            controller.start(feedback, ScriptOperationKind.RELOAD) {
                release.await()
                true
            }
        )
        assertFalse(
            controller.start(feedback, ScriptOperationKind.CHECK) { true }
        )
        assertIs<UserFeedbackEvent.OperationBusy>(events.single())

        release.complete(Unit)
        runtime.handles.single().job.join()
        assertEquals(1, idleCalls.get())
        assertEquals(
            ScriptOperationState.COMPLETED,
            controller.snapshot().lastUser?.state
        )

        assertTrue(controller.start(feedback, ScriptOperationKind.CHECK) { true })
        runtime.handles.last().job.join()
        assertEquals(2, idleCalls.get())
        assertEquals(ScriptOperationKind.CHECK, controller.snapshot().lastUser?.operation?.kind)
    }

    private class FakeOperationRuntime : ScriptOperationRuntime {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handles = mutableListOf<FakeHandle>()

        override val isGlobalThread: Boolean = false

        override fun create(
            block: suspend CoroutineScope.() -> Unit
        ): ScriptOperationHandle {
            val handle = FakeHandle(scope.launch(start = CoroutineStart.LAZY, block = block))
            handles += handle
            return handle
        }

        override fun closeAdmission(draining: ScriptOperationHandle?) = Unit

        class FakeHandle(override val job: Job) : ScriptOperationHandle {
            override fun beginShutdown() = Unit
            override fun pumpGlobalTasks() = Unit
            override fun close() = Unit
        }
    }
}
