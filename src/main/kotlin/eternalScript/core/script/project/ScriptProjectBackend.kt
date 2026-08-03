package eternalScript.core.script.project

import eternalScript.core.data.Resource
import eternalScript.core.script.definition.ScriptCompilationCache
import eternalScript.core.script.definition.ScriptRuntimeClasspath
import eternalScript.core.script.definition.scriptRuntimeClasspath
import java.nio.file.Path
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * Runtime project boundary used by ScriptManager. The compiler and evaluator
 * own separate responsibilities underneath it.
 */
internal interface ScriptProjectBackend {
    fun compile(project: ScriptProjectSource): ResultWithDiagnostics<CompiledScript>

    fun evaluate(compiledScript: CompiledScript): ResultWithDiagnostics<EvaluationResult>
}

internal class KotlinProjectBackend(
    cacheRoot: () -> Path = {
        Resource.CACHE.toPath().resolve(ScriptCompilationCache.generation())
    },
    runtimeClasspath: () -> ScriptRuntimeClasspath = ::scriptRuntimeClasspath
) : ScriptProjectBackend {
    private val compiler = ScriptProjectCompiler(cacheRoot, runtimeClasspath)
    private val evaluator = ScriptGenerationEvaluator()

    override fun compile(project: ScriptProjectSource): ResultWithDiagnostics<CompiledScript> =
        compiler.compile(project)

    override fun evaluate(
        compiledScript: CompiledScript
    ): ResultWithDiagnostics<EvaluationResult> =
        evaluator.evaluate(compiledScript)
}
