package eternalscript.scripting.runtime

import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptOperationStatus
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ScriptOperationDispatcherTest {
    @Test
    fun `runs immediately when already on the main thread`() {
        val scheduled = AtomicBoolean()
        val dispatcher = ScriptOperationDispatcher(
            isMainThread = { true },
            schedule = { scheduled.set(true) }
        )

        val result = dispatcher.dispatch(ScriptOperation.RELOAD, ::disabled) { future ->
            future.complete(result(ScriptOperation.RELOAD, ScriptOperationStatus.SUCCESS))
        }.toCompletableFuture().get()

        assertEquals(ScriptOperationStatus.SUCCESS, result.status)
        assertFalse(scheduled.get())
    }

    @Test
    fun `close disables a queued operation and a late task cannot execute it`() {
        var queued: (() -> Unit)? = null
        val executed = AtomicBoolean()
        val dispatcher = ScriptOperationDispatcher(
            isMainThread = { false },
            schedule = { block -> queued = block }
        )
        val pending = dispatcher.dispatch(ScriptOperation.ENABLE, ::disabled) { future ->
            executed.set(true)
            future.complete(result(ScriptOperation.ENABLE, ScriptOperationStatus.SUCCESS))
        }

        dispatcher.close(::disabled)
        assertEquals(ScriptOperationStatus.DISABLED, pending.toCompletableFuture().get().status)

        requireNotNull(queued).invoke()
        assertFalse(executed.get())
    }

    @Test
    fun `queued operation runs once when the scheduler invokes it`() {
        var queued: (() -> Unit)? = null
        var executions = 0
        val dispatcher = ScriptOperationDispatcher(
            isMainThread = { false },
            schedule = { block -> queued = block }
        )
        val pending = dispatcher.dispatch(ScriptOperation.RECOMPILE, ::disabled) { future ->
            executions++
            future.complete(result(ScriptOperation.RECOMPILE, ScriptOperationStatus.SUCCESS))
        }

        requireNotNull(queued).invoke()
        dispatcher.close(::disabled)

        assertEquals(ScriptOperationStatus.SUCCESS, pending.toCompletableFuture().get().status)
        assertEquals(1, executions)
    }

    @Test
    fun `scheduler rejection disables the operation without invoking it`() {
        val executed = AtomicBoolean()
        val dispatcher = ScriptOperationDispatcher(
            isMainThread = { false },
            schedule = { throw IllegalStateException("scheduler stopped") }
        )

        val result = dispatcher.dispatch(ScriptOperation.DISABLE, ::disabled) { future ->
            executed.set(true)
            future.complete(result(ScriptOperation.DISABLE, ScriptOperationStatus.SUCCESS))
        }.toCompletableFuture().get()

        assertEquals(ScriptOperationStatus.DISABLED, result.status)
        assertFalse(executed.get())
    }

    @Test
    fun `close rejects operations submitted afterward`() {
        val executed = AtomicBoolean()
        val dispatcher = ScriptOperationDispatcher(
            isMainThread = { true },
            schedule = { error("must not schedule") }
        )
        dispatcher.close(::disabled)

        val result = dispatcher.dispatch(ScriptOperation.CANCEL, ::disabled) { future ->
            executed.set(true)
            future.complete(result(ScriptOperation.CANCEL, ScriptOperationStatus.SUCCESS))
        }.toCompletableFuture().get()

        assertEquals(ScriptOperationStatus.DISABLED, result.status)
        assertFalse(executed.get())
    }

    private fun disabled(operation: ScriptOperation): ScriptOperationResult =
        result(operation, ScriptOperationStatus.DISABLED)

    private fun result(
        operation: ScriptOperation,
        status: ScriptOperationStatus
    ): ScriptOperationResult = ScriptOperationResult(operation, status, 7)
}
