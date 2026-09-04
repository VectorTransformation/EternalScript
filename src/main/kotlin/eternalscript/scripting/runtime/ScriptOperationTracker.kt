package eternalscript.scripting.runtime

import eternalscript.api.ScriptOperation
import eternalscript.api.ScriptOperationResult
import eternalscript.scripting.source.ScriptPathTransition
import java.util.List.copyOf
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

internal enum class ScriptOperationPhase {
    PREPARING,
    COMPILING,
    APPLYING
}

internal class PendingScriptOperation internal constructor(
    val token: Long,
    val operation: ScriptOperation,
    affectedPaths: List<String>,
    val future: CompletableFuture<ScriptOperationResult>,
    initialPhase: ScriptOperationPhase = ScriptOperationPhase.PREPARING
) {
    val affectedPaths: List<String> = copyOf(affectedPaths)

    @Volatile
    var phase: ScriptOperationPhase = initialPhase
        internal set

    @Volatile
    var requestId: Long? = null
        internal set

    @Volatile
    var sourceTransition: ScriptPathTransition? = null
        internal set

    @Volatile
    internal var workerCompletionClaimed: Boolean = false
}

internal sealed interface CancelOperationResult {
    data object Idle : CancelOperationResult
    data object Busy : CancelOperationResult
    data class Cancelled(val operation: PendingScriptOperation) : CancelOperationResult
}

/**
 * Owns the thread-safe lifecycle of the single accepted script mutation.
 *
 * The Paper main thread owns generation and snapshot mutations. When the Paper scheduler can no
 * longer return a callback, the source worker first finalizes any path transition and then detaches
 * the operation atomically with publication of the idle snapshot.
 */
internal class ScriptOperationTracker {
    private val sequence = AtomicLong()
    private val lock = Any()

    @Volatile
    private var pending: PendingScriptOperation? = null

    fun current(): PendingScriptOperation? = pending

    fun tryBegin(
        operation: ScriptOperation,
        future: CompletableFuture<ScriptOperationResult>,
        affectedPaths: List<String> = emptyList()
    ): PendingScriptOperation? = synchronized(lock) {
        if (pending != null) return@synchronized null
        PendingScriptOperation(sequence.incrementAndGet(), operation, affectedPaths, future).also { started ->
            pending = started
        }
    }

    fun cancelCancellable(): CancelOperationResult = synchronized(lock) {
        val current = pending ?: return@synchronized CancelOperationResult.Idle
        if (current.phase == ScriptOperationPhase.APPLYING || current.workerCompletionClaimed) {
            return@synchronized CancelOperationResult.Busy
        }
        pending = null
        CancelOperationResult.Cancelled(current)
    }

    fun attachTransition(
        operation: PendingScriptOperation,
        transition: ScriptPathTransition
    ): Boolean = synchronized(lock) {
        if (pending?.token != operation.token || operation.phase != ScriptOperationPhase.PREPARING) {
            return@synchronized false
        }
        operation.sourceTransition = transition
        true
    }

    fun markCompiling(operation: PendingScriptOperation, requestId: Long): Boolean = synchronized(lock) {
        if (pending?.token != operation.token || operation.phase != ScriptOperationPhase.PREPARING) {
            return@synchronized false
        }
        operation.requestId = requestId
        operation.phase = ScriptOperationPhase.COMPILING
        true
    }

    fun markApplying(operation: PendingScriptOperation, requestId: Long): Boolean = synchronized(lock) {
        if (
            pending?.token != operation.token ||
            operation.requestId != requestId ||
            operation.phase != ScriptOperationPhase.COMPILING
        ) {
            return@synchronized false
        }
        operation.phase = ScriptOperationPhase.APPLYING
        true
    }

    fun isCurrent(operation: PendingScriptOperation): Boolean = pending?.token == operation.token

    fun findByFuture(future: CompletableFuture<ScriptOperationResult>): PendingScriptOperation? =
        pending?.takeIf { operation -> operation.future === future }

    fun detach(operation: PendingScriptOperation): Boolean = synchronized(lock) {
        if (pending?.token != operation.token) return@synchronized false
        pending = null
        true
    }

    fun cancelCurrent(): PendingScriptOperation? = synchronized(lock) {
        val current = pending ?: return@synchronized null
        pending = null
        current
    }

    fun finishFromWorker(
        operation: PendingScriptOperation,
        result: ScriptOperationResult,
        finalize: (ScriptOperationResult) -> ScriptOperationResult,
        publishDetachedState: () -> Unit
    ): ScriptOperationResult? {
        val claimed = synchronized(lock) {
            if (
                pending?.token != operation.token ||
                operation.workerCompletionClaimed
            ) {
                return@synchronized false
            }
            // Prevent clear from preempting while a source path is being committed or rolled back.
            operation.workerCompletionClaimed = true
            operation.phase = ScriptOperationPhase.APPLYING
            true
        }
        if (!claimed) return null

        val finalized = finalize(result)
        return synchronized(lock) {
            if (pending?.token != operation.token) return@synchronized null
            pending = null
            // Keep the public snapshot transition in the same critical section as detachment so
            // another operation cannot publish its BUSY state and then have it cleared here.
            publishDetachedState()
            finalized
        }
    }
}
