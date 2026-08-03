package eternalScript.core.operation

import java.util.concurrent.atomic.AtomicReference

/** The user-visible kind of an asynchronous EternalScript operation. */
internal enum class ScriptOperationKind(val userVisible: Boolean) {
    RELOAD(true),
    CHECK(true),
    UNLOAD(true),
    CONFIG_RELOAD(true),
    WORKSPACE_UPDATE(true),
    CACHE_CLEAR(true),
    ENVIRONMENT_REFRESH(false)
}

internal enum class ScriptOperationState {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

internal data class ScriptOperation(
    val kind: ScriptOperationKind
)

internal data class ScriptOperationSnapshot(
    val operation: ScriptOperation,
    val state: ScriptOperationState
)

/** Thread-safe state holder for one accepted operation. */
internal class ScriptOperationTracker(
    val operation: ScriptOperation
) {
    private val state = AtomicReference(ScriptOperationState.ACCEPTED)

    fun start() {
        state.compareAndSet(
            ScriptOperationState.ACCEPTED,
            ScriptOperationState.RUNNING
        )
    }

    fun complete(success: Boolean) {
        state.updateAndGet { current ->
            when (current) {
                ScriptOperationState.ACCEPTED,
                ScriptOperationState.RUNNING -> if (success) {
                    ScriptOperationState.COMPLETED
                } else {
                    ScriptOperationState.FAILED
                }

                ScriptOperationState.COMPLETED,
                ScriptOperationState.FAILED,
                ScriptOperationState.CANCELLED -> current
            }
        }
    }

    fun fail() {
        state.updateAndGet { current ->
            when (current) {
                ScriptOperationState.ACCEPTED,
                ScriptOperationState.RUNNING -> ScriptOperationState.FAILED

                ScriptOperationState.COMPLETED,
                ScriptOperationState.FAILED,
                ScriptOperationState.CANCELLED -> current
            }
        }
    }

    fun cancel() {
        state.updateAndGet { current ->
            when (current) {
                ScriptOperationState.ACCEPTED,
                ScriptOperationState.RUNNING -> ScriptOperationState.CANCELLED

                ScriptOperationState.COMPLETED,
                ScriptOperationState.FAILED,
                ScriptOperationState.CANCELLED -> current
            }
        }
    }

    fun snapshot(): ScriptOperationSnapshot =
        ScriptOperationSnapshot(operation, state.get())
}
