package eternalScript.core.manager

import eternalScript.core.operation.ScriptOperationSnapshot
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.generation.ScriptProjectGenerationSnapshot

internal enum class ScriptProjectState {
    INACTIVE,
    ACTIVE,
    UPDATING,
    STOPPING
}

internal enum class AutomaticProjectLoadState {
    NOT_ATTEMPTED,
    EMPTY,
    ACTIVATED,
    FAILED_PRESERVED,
    FAILED_INACTIVE
}

/** One immutable status view assembled for commands and other user interfaces. */
internal data class ScriptProjectStatus(
    val generation: ScriptProjectGenerationSnapshot,
    val availableSources: Set<String>,
    val currentUserOperation: ScriptOperationSnapshot?,
    val lastUserOperation: ScriptOperationSnapshot?,
    val backgroundMaintenance: Boolean,
    val automaticLoadState: AutomaticProjectLoadState
) {
    val state: ScriptProjectState
        get() = when (generation.state) {
            null -> ScriptProjectState.INACTIVE
            ScriptExecutionGate.State.ACTIVE -> ScriptProjectState.ACTIVE
            ScriptExecutionGate.State.STAGED,
            ScriptExecutionGate.State.SWAPPING -> ScriptProjectState.UPDATING
            ScriptExecutionGate.State.RETIRED -> ScriptProjectState.STOPPING
        }
}
