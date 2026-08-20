package eternalscript.scripting.compilation

import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.repl.k2.CompiledComponentGeneration

internal data class ScriptCompilationRequest(
    val id: Long,
    val epoch: Long,
    val activeRevision: Long,
    val activeSources: List<SharedReplSource>,
    val candidateSources: List<SharedReplSource>,
    val selection: ScriptCandidateSelection,
    val environmentSnapshot: ScriptEnvironmentSnapshot,
    val allowStartupCache: Boolean,
    val forceAll: Boolean
)

internal sealed interface ScriptCandidateSelection {
    data object Exact : ScriptCandidateSelection
    data class Load(val targetPaths: Set<String>, val activePaths: Set<String>) : ScriptCandidateSelection
}

internal data class ScriptCompilationMetrics(
    val environmentMillis: Long,
    val compileMillis: Long,
    val analyzedCount: Int,
    val compiledCount: Int,
    val reusedCount: Int,
    val componentCount: Int,
    val cache: String
)

internal sealed interface ScriptCompilationOutcome {
    val request: ScriptCompilationRequest

    data class Success(
        override val request: ScriptCompilationRequest,
        val environment: ScriptCompilationEnvironment,
        val generation: CompiledComponentGeneration,
        val candidateSources: List<SharedReplSource>,
        val affectedPaths: List<String>,
        val cacheHit: Boolean,
        val metrics: ScriptCompilationMetrics
    ) : ScriptCompilationOutcome

    data class Failure(
        override val request: ScriptCompilationRequest,
        val diagnostic: SharedReplDiagnostic,
        val metrics: ScriptCompilationMetrics,
        val environment: ScriptCompilationEnvironment? = null
    ) : ScriptCompilationOutcome

    data class Cancelled(override val request: ScriptCompilationRequest) : ScriptCompilationOutcome
}
