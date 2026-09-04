package eternalscript.scripting.runtime

import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptOperationStatus
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScriptOperationTrackerTest {
    @Test
    fun `accepts exactly one ordinary operation`() {
        val tracker = ScriptOperationTracker()
        val first = requireNotNull(tracker.tryBegin(ScriptOperation.ENABLE, CompletableFuture(), listOf("a")))

        assertNull(tracker.tryBegin(ScriptOperation.RELOAD, CompletableFuture()))
        assertSame(first, tracker.current())
        assertEquals(listOf("a"), first.affectedPaths)
    }

    @Test
    fun `cancel detaches preparation and compilation but not application`() {
        val preparingTracker = ScriptOperationTracker()
        val preparing = requireNotNull(
            preparingTracker.tryBegin(ScriptOperation.ENABLE, CompletableFuture(), listOf("a"))
        )
        val cancelDuringPreparation = assertIs<CancelOperationResult.Cancelled>(
            preparingTracker.cancelCancellable()
        )
        assertSame(preparing, cancelDuringPreparation.operation)
        assertNull(preparingTracker.current())

        val compilingTracker = ScriptOperationTracker()
        val compiling = requireNotNull(
            compilingTracker.tryBegin(ScriptOperation.RECOMPILE, CompletableFuture())
        )
        assertTrue(compilingTracker.markCompiling(compiling, 7))
        val cancelDuringCompilation = assertIs<CancelOperationResult.Cancelled>(
            compilingTracker.cancelCancellable()
        )
        assertSame(compiling, cancelDuringCompilation.operation)

        val applyingTracker = ScriptOperationTracker()
        val applying = requireNotNull(applyingTracker.tryBegin(ScriptOperation.RELOAD, CompletableFuture()))
        assertTrue(applyingTracker.markCompiling(applying, 9))
        assertTrue(applyingTracker.markApplying(applying, 9))
        assertIs<CancelOperationResult.Busy>(applyingTracker.cancelCancellable())
        assertSame(applying, applyingTracker.current())

        assertIs<CancelOperationResult.Idle>(ScriptOperationTracker().cancelCancellable())
    }

    @Test
    fun `only matching compilation can enter applying phase`() {
        val tracker = ScriptOperationTracker()
        val operation = requireNotNull(tracker.tryBegin(ScriptOperation.RELOAD, CompletableFuture()))

        assertFalse(tracker.markApplying(operation, 1))
        assertTrue(tracker.markCompiling(operation, 4))
        assertFalse(tracker.markApplying(operation, 3))
        assertTrue(tracker.markApplying(operation, 4))
        assertEquals(ScriptOperationPhase.APPLYING, operation.phase)
    }

    @Test
    fun `worker finalizes and publishes detached state before exposing terminal result`() {
        val tracker = ScriptOperationTracker()
        val future = CompletableFuture<ScriptOperationResult>()
        val operation = requireNotNull(tracker.tryBegin(ScriptOperation.DISABLE, future, listOf("a")))
        val terminal = result(ScriptOperation.DISABLE, ScriptOperationStatus.DISABLED)
        val finalized = terminal.copy(status = ScriptOperationStatus.FAILED)
        val pathFinalized = AtomicBoolean()
        val detachedStatePublished = AtomicBoolean()

        val completed = requireNotNull(
            tracker.finishFromWorker(
                operation,
                terminal,
                finalize = { unfinalized ->
                    assertSame(terminal, unfinalized)
                    assertFalse(future.isDone)
                    pathFinalized.set(true)
                    finalized
                },
                publishDetachedState = {
                    assertTrue(pathFinalized.get())
                    assertFalse(future.isDone)
                    detachedStatePublished.set(true)
                }
            )
        )
        future.complete(completed)
        assertTrue(pathFinalized.get())
        assertTrue(detachedStatePublished.get())
        assertSame(finalized, future.getNow(null))
        assertNull(tracker.current())
    }

    @Test
    fun `cancel cannot preempt worker path finalization`() {
        val tracker = ScriptOperationTracker()
        val operation = requireNotNull(
            tracker.tryBegin(ScriptOperation.ENABLE, CompletableFuture(), listOf("a"))
        )
        val finalizerStarted = CountDownLatch(1)
        val releaseFinalizer = CountDownLatch(1)
        val worker = Thread {
            val completed = tracker.finishFromWorker(
                operation,
                result(ScriptOperation.ENABLE, ScriptOperationStatus.DISABLED),
                finalize = { terminal ->
                    finalizerStarted.countDown()
                    assertTrue(releaseFinalizer.await(5, TimeUnit.SECONDS))
                    terminal
                },
                publishDetachedState = {}
            )
            if (completed != null) operation.future.complete(completed)
        }

        worker.start()
        assertTrue(finalizerStarted.await(5, TimeUnit.SECONDS))
        assertIs<CancelOperationResult.Busy>(tracker.cancelCancellable())
        releaseFinalizer.countDown()
        worker.join(5_000)

        assertFalse(worker.isAlive)
        assertTrue(operation.future.isDone)
        assertNull(tracker.current())
    }

    @Test
    fun `cancelled operation cannot detach or finish over a new operation`() {
        val tracker = ScriptOperationTracker()
        val stale = requireNotNull(tracker.tryBegin(ScriptOperation.ENABLE, CompletableFuture()))
        assertIs<CancelOperationResult.Cancelled>(tracker.cancelCancellable())
        val replacement = requireNotNull(tracker.tryBegin(ScriptOperation.RELOAD, CompletableFuture()))

        assertFalse(tracker.detach(stale))
        assertNull(
            tracker.finishFromWorker(
                stale,
                result(ScriptOperation.ENABLE, ScriptOperationStatus.FAILED),
                finalize = { it },
                publishDetachedState = {}
            )
        )
        assertSame(replacement, tracker.current())
    }

    private fun result(
        operation: ScriptOperation,
        status: ScriptOperationStatus
    ): ScriptOperationResult = ScriptOperationResult(operation, status, 0)
}
