package eternalScript.core.script.generation

import eternalScript.core.script.data.ScriptExecutionGate

/** Immutable user-facing projection of the generation currently owned by the runtime. */
internal data class ScriptProjectGenerationSnapshot(
    val state: ScriptExecutionGate.State?,
    val sourceNames: Set<String>,
    val entryNames: List<String>
) {
    val exists: Boolean
        get() = state != null && state != ScriptExecutionGate.State.RETIRED

    val acceptsCallbacks: Boolean
        get() = state == ScriptExecutionGate.State.ACTIVE

    companion object {
        val EMPTY = ScriptProjectGenerationSnapshot(
            state = null,
            sourceNames = emptySet(),
            entryNames = emptyList()
        )
    }
}

internal enum class ScriptProjectLoadOutcome {
    ACTIVATED,
    REJECTED_PREVIOUS_ACTIVE,
    REJECTED_ACTIVE_CHANGED,
    REJECTED_NO_ACTIVE
}

internal enum class GenerationDiagnosticPhase {
    COMPILATION,
    EVALUATION
}

internal enum class ScriptLifecycleFailurePhase {
    ENABLE,
    DISABLE,
    PUBLISH,
    RESTORE,
    CLEANUP
}

/** Stable, classloader-free summary of one compiler or evaluator diagnostic. */
internal data class ScriptProjectDiagnosticSummary(
    val phase: GenerationDiagnosticPhase,
    val sourceName: String,
    val line: Int?,
    val column: Int?,
    val message: String
)

/** Stable, classloader-free summary of one lifecycle failure. */
internal data class ScriptLifecycleFailureSummary(
    val phase: ScriptLifecycleFailurePhase,
    val sourceName: String,
    val line: Int?,
    val reason: String
)

internal data class ScriptProjectReport(
    val diagnostics: List<ScriptProjectDiagnosticSummary> = emptyList(),
    val lifecycleFailures: List<ScriptLifecycleFailureSummary> = emptyList()
) {
    val diagnosticCount: Int
        get() = diagnostics.size

    val failureCount: Int
        get() = diagnostics.size + lifecycleFailures.size
}

internal data class ScriptProjectLoadResult(
    val outcome: ScriptProjectLoadOutcome,
    val previousGeneration: ScriptProjectGenerationSnapshot,
    val generation: ScriptProjectGenerationSnapshot,
    val report: ScriptProjectReport
) {
    val activated: Boolean
        get() = outcome == ScriptProjectLoadOutcome.ACTIVATED
}

internal enum class ScriptProjectCheckOutcome {
    NO_SOURCES,
    PASSED,
    FAILED
}

internal data class ScriptProjectCheckResult(
    val outcome: ScriptProjectCheckOutcome,
    val report: ScriptProjectReport
) {
    val passed: Boolean
        get() = outcome == ScriptProjectCheckOutcome.PASSED

    val diagnosticCount: Int
        get() = report.diagnosticCount
}

internal enum class ScriptProjectUnloadOutcome {
    UNLOADED,
    ALREADY_EMPTY,
    REJECTED
}

internal data class ScriptProjectUnloadResult(
    val outcome: ScriptProjectUnloadOutcome,
    val sourceCount: Int,
    val entryCount: Int,
    val generation: ScriptProjectGenerationSnapshot,
    val report: ScriptProjectReport
)
