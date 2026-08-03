package eternalScript.core.manager

import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

internal class DataManagerLifecycle {
    private data class State(
        val session: Long,
        val open: Boolean
    )

    private val state = AtomicReference(State(session = 0, open = false))

    fun open(): Long =
        state.updateAndGet { current ->
            if (current.open) current else State(current.session + 1, true)
        }.session

    fun close(): Long =
        state.updateAndGet { current ->
            if (!current.open) current else State(current.session + 1, false)
        }.session

    fun openSession(): Long? =
        state.get().let { current ->
            current.session.takeIf { current.open }
        }

    fun accepts(session: Long): Boolean =
        state.get().let { current ->
            current.open && current.session == session
        }
}

internal fun awaitOperationShutdown(
    operation: Job?,
    timeoutMillis: Long,
    isGlobalThread: Boolean,
    pumpGlobalTasks: () -> Unit
): Boolean {
    require(timeoutMillis >= 0L) {
        "Operation shutdown timeout must not be negative."
    }
    if (operation == null || operation.isCompleted) return true
    if (!isGlobalThread) {
        return runBlocking {
            withTimeoutOrNull(timeoutMillis) {
                operation.join()
                true
            } ?: false
        }
    }

    val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val started = System.nanoTime()
    while (!operation.isCompleted) {
        pumpGlobalTasks()
        if (operation.isCompleted) return true

        val elapsed = System.nanoTime() - started
        if (elapsed >= timeoutNanos) return false
        LockSupport.parkNanos(
            minOf(SHUTDOWN_OPERATION_POLL_NANOS, timeoutNanos - elapsed)
        )
    }
    return true
}

private const val SHUTDOWN_OPERATION_POLL_NANOS = 100_000L
