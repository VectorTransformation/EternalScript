package eternalscript.scripting.runtime

import eternalscript.api.ScriptDiagnostic
import eternalscript.scripting.compilation.ScriptCompilationEnvironment
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.repl.k2.CompiledComponentGeneration
import eternalscript.scripting.repl.k2.EvaluatedComponentGeneration

internal data class LoadedScript(
    val name: String,
    val text: String,
    val active: ActiveScript? = null
) {
    fun source(): SharedReplSource = SharedReplSource(name, text)
    fun sameSource(other: LoadedScript): Boolean = name == other.name && text == other.text
    fun withoutRuntime(): LoadedScript = LoadedScript(name, text)
}

internal class ScriptGenerationState {
    val loaded: MutableList<LoadedScript> = mutableListOf()
    var compiled: CompiledComponentGeneration? = null
    var evaluated: EvaluatedComponentGeneration? = null
    var environment: ScriptCompilationEnvironment? = null
    var revision: Long = 0
}

internal data class GenerationApplyResult(
    val success: Boolean,
    val diagnostics: List<ScriptDiagnostic>,
    val cacheRetryable: Boolean = false
)
