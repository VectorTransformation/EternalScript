package eternalScript.core.script.generation

import eternalScript.core.runtime.GlobalExecution
import eternalScript.core.script.project.ScriptProjectBackend
import eternalScript.core.script.project.ScriptProjectRuntime
import eternalScript.core.script.project.ScriptProjectSource
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.valueOrNull

/** Compiles and evaluates a candidate without publishing it as active. */
internal class GenerationStager(
    private val backend: ScriptProjectBackend,
    private val globalExecution: GlobalExecution,
    private val diagnostics: GenerationDiagnostics
) {
    fun compile(
        project: ScriptProjectSource,
        report: MutableScriptProjectReport
    ): CompiledScript? {
        val compilation = backend.compile(project)
        diagnostics.report(
            project,
            compilation,
            GenerationDiagnosticPhase.COMPILATION,
            report
        )
        return compilation.valueOrNull()
    }

    suspend fun stage(
        project: ScriptProjectSource,
        compiled: CompiledScript,
        report: MutableScriptProjectReport,
        canStage: () -> Boolean,
        takeOwnership: (ManagedProjectGeneration) -> Boolean
    ): ManagedProjectGeneration? = globalExecution.global stage@{
        if (!canStage()) return@stage null
        val evaluation = backend.evaluate(compiled)
        diagnostics.report(
            project,
            evaluation,
            GenerationDiagnosticPhase.EVALUATION,
            report
        )

        val stagedRuntime = evaluation.valueOrNull()?.returnValue?.scriptInstance
            as? ScriptProjectRuntime
            ?: return@stage null
        var generation: ScriptGeneration? = null
        generation = try {
            stagedRuntime.transfer().also { runtime ->
                runtime.mapRuntimeExceptions(project)
            }
        } catch (exception: Throwable) {
            runCatching {
                generation?.dispose() ?: stagedRuntime.close()
            }.exceptionOrNull()?.let(exception::addSuppressed)
            throw exception
        }

        try {
            ManagedProjectGeneration(project, generation).also { candidate ->
                check(takeOwnership(candidate)) {
                    "The staged script candidate already has an owner."
                }
            }
        } catch (exception: Throwable) {
            runCatching(generation::dispose)
                .exceptionOrNull()
                ?.let(exception::addSuppressed)
            throw exception
        }
    }
}
