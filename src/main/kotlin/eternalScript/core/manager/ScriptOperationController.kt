package eternalScript.core.manager

import eternalScript.core.feedback.UserFeedback
import eternalScript.core.feedback.UserFeedbackEvent
import eternalScript.core.operation.ScriptOperation
import eternalScript.core.operation.ScriptOperationKind
import eternalScript.core.operation.ScriptOperationSnapshot
import eternalScript.core.operation.ScriptOperationTracker
import eternalScript.core.runtime.GlobalExecution
import eternalScript.core.runtime.GlobalTaskOwner
import eternalScript.core.runtime.ServerAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

internal interface ScriptOperationHandle {
    val job: Job
    fun beginShutdown()
    fun pumpGlobalTasks()
    fun close()
}

internal interface ScriptOperationRuntime {
    val isGlobalThread: Boolean

    fun create(block: suspend CoroutineScope.() -> Unit): ScriptOperationHandle
    fun closeAdmission(draining: ScriptOperationHandle?)
}

internal class GlobalScriptOperationRuntime(
    private val globalExecution: GlobalExecution,
    private val server: ServerAccess
) : ScriptOperationRuntime {
    override val isGlobalThread: Boolean
        get() = server.isGlobalTickThread

    override fun create(
        block: suspend CoroutineScope.() -> Unit
    ): ScriptOperationHandle {
        val owner = globalExecution.newTaskOwner()
        val job = globalExecution.launch(
            context = owner,
            start = CoroutineStart.LAZY,
            block = block
        )
        return GlobalHandle(owner, job)
    }

    override fun closeAdmission(draining: ScriptOperationHandle?) {
        globalExecution.closeAdmission((draining as? GlobalHandle)?.owner)
    }

    private inner class GlobalHandle(
        val owner: GlobalTaskOwner,
        override val job: Job
    ) : ScriptOperationHandle {
        override fun beginShutdown() {
            globalExecution.beginTaskOwnerShutdown(owner)
        }

        override fun pumpGlobalTasks() {
            globalExecution.drainPendingGlobalTasks(owner)
        }

        override fun close() {
            globalExecution.closeTaskOwner(owner)
        }
    }
}

internal class ScriptOperationController(
    private val lifecycle: DataManagerLifecycle,
    private val runtime: ScriptOperationRuntime,
    private val logger: Logger,
    private val emit: suspend (UserFeedback, UserFeedbackEvent) -> Unit,
    private val onIdle: () -> Unit
) {
    private val lock = Any()
    private var operation: ScriptProjectOperation? = null
    private var lastUserOperation: ScriptOperationSnapshot? = null

    fun reset() {
        synchronized(lock) {
            check(operation == null) {
                "The script operation controller cannot reset while an operation is active."
            }
            lastUserOperation = null
        }
    }

    fun start(
        feedback: UserFeedback,
        kind: ScriptOperationKind,
        announceBusy: Boolean = true,
        block: suspend (session: Long) -> Boolean
    ): Boolean {
        val current = synchronized(lock) {
            val session = lifecycle.openSession() ?: return false
            if (operation != null) return@synchronized null

            val tracker = ScriptOperationTracker(ScriptOperation(kind))
            val handle = runtime.create {
                tracker.start()
                try {
                    val result = if (lifecycle.accepts(session)) {
                        block(session)
                    } else {
                        null
                    }
                    if (result == null) {
                        tracker.cancel()
                    } else if (lifecycle.accepts(session)) {
                        tracker.complete(result)
                    } else {
                        tracker.cancel()
                    }
                } catch (_: CancellationException) {
                    tracker.cancel()
                } catch (exception: Throwable) {
                    tracker.fail()
                    val incidentId = UUID.randomUUID().toString().substring(0, 8)
                    logger.log(
                        Level.SEVERE,
                        "EternalScript project operation failed " +
                            "(incident=$incidentId, operation=${kind.name}).",
                        exception
                    )
                    if (kind.userVisible && lifecycle.accepts(session)) {
                        emit(feedback, UserFeedbackEvent.OperationFailed(kind, incidentId))
                    }
                }
            }
            ScriptProjectOperation(tracker, handle, session).also { created ->
                operation = created
                handle.job.invokeOnCompletion {
                    complete(created)
                }
            }
        }

        if (current == null) {
            if (announceBusy) {
                feedback.emit(UserFeedbackEvent.OperationBusy)
            }
            return false
        }

        current.handle.job.start()
        return true
    }

    fun isActive(): Boolean = synchronized(lock) { operation != null }

    fun snapshot(): ScriptOperationControllerSnapshot = synchronized(lock) {
        ScriptOperationControllerSnapshot(
            current = operation?.tracker?.snapshot(),
            lastUser = lastUserOperation
        )
    }

    fun shutdown(timeoutMillis: Long = OPERATION_SHUTDOWN_TIMEOUT_MILLIS): Boolean {
        val current = synchronized(lock) {
            operation.also { operation = null }
        }
        current?.handle?.beginShutdown()
        runtime.closeAdmission(current?.handle)
        current?.handle?.job?.cancel()
        return try {
            awaitOperationShutdown(
                operation = current?.handle?.job,
                timeoutMillis = timeoutMillis,
                isGlobalThread = runtime.isGlobalThread,
                pumpGlobalTasks = { current?.handle?.pumpGlobalTasks() }
            )
        } finally {
            current?.handle?.close()
        }
    }

    private fun complete(completed: ScriptProjectOperation) {
        val snapshot = completed.tracker.snapshot()
        synchronized(lock) {
            if (operation === completed) {
                operation = null
            }
            if (snapshot.operation.kind.userVisible) {
                lastUserOperation = snapshot
            }
        }

        completed.handle.close()
        onIdle()
    }
}

internal data class ScriptOperationControllerSnapshot(
    val current: ScriptOperationSnapshot?,
    val lastUser: ScriptOperationSnapshot?
)

private data class ScriptProjectOperation(
    val tracker: ScriptOperationTracker,
    val handle: ScriptOperationHandle,
    val session: Long
)

private const val OPERATION_SHUTDOWN_TIMEOUT_MILLIS = 10_000L
