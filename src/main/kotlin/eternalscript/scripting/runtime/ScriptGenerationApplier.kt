package eternalscript.scripting.runtime

import eternalscript.api.ScriptDiagnostic
import eternalscript.api.ScriptDiagnosticPhase
import eternalscript.messaging.MessageKey
import eternalscript.messaging.MessageLevel
import eternalscript.messaging.SystemMessage
import eternalscript.messaging.systemMessage
import eternalscript.scripting.compilation.ScriptCompilationEnvironment
import eternalscript.scripting.compilation.ScriptCompilationOutcome
import eternalscript.scripting.repl.k2.CompiledComponentGeneration
import eternalscript.scripting.repl.k2.ComponentEvaluationResult
import eternalscript.scripting.repl.k2.ComponentGenerationEvaluator
import eternalscript.scripting.repl.k2.EvaluatedComponentGeneration
import org.bukkit.plugin.java.JavaPlugin

internal class ScriptGenerationApplier(
    private val plugin: JavaPlugin,
    private val state: ScriptGenerationState,
    private val commandRegistry: ScriptCommandRegistry,
    private val system: (SystemMessage) -> Unit
) {
    fun apply(
        candidate: List<LoadedScript>,
        outcome: ScriptCompilationOutcome.Success
    ): GenerationApplyResult = commandRegistry.batch { applyInBatch(candidate, outcome) }

    fun clear(): List<String> = commandRegistry.batch {
        val affected = state.loaded.map(LoadedScript::name)
        val previousEvaluated = state.evaluated
        val previousCompiled = state.compiled
        disposeEntries(orderedActive(state.loaded, state.compiled).asReversed())
        ReplStateBridge.publish(ReplStateBridge.StateTable())
        state.evaluated = null
        state.compiled = null
        state.loaded.clear()
        state.environment = null
        state.revision++
        retireEvaluatedSafely(previousEvaluated)
        closeCompiledSafely(previousCompiled)
        affected
    }

    fun shutdown() {
        commandRegistry.batch {
            val previousEvaluated = state.evaluated
            val previousCompiled = state.compiled
            disposeEntries(orderedActive(state.loaded, state.compiled).asReversed())
            ReplStateBridge.publish(ReplStateBridge.StateTable())
            state.evaluated = null
            state.compiled = null
            state.loaded.clear()
            state.environment = null
            retireEvaluatedSafely(previousEvaluated)
            closeCompiledSafely(previousCompiled)
        }
    }

    fun closeOutcomeArtifacts(outcome: ScriptCompilationOutcome) {
        if (outcome is ScriptCompilationOutcome.Success) closeCompiledSafely(outcome.generation)
    }

    private fun applyInBatch(
        candidate: List<LoadedScript>,
        outcome: ScriptCompilationOutcome.Success
    ): GenerationApplyResult {
        val affected = outcome.affectedPaths.toSet()
        val previousLoaded = state.loaded.toList()
        val previousCompiled = state.compiled
        val previousEvaluated = state.evaluated
        val previousEnvironment = state.environment
        val previousStateTable = previousEvaluated?.state?.copy() ?: ReplStateBridge.snapshot()
        val candidateEnvironment = outcome.environment.retained()
        val evaluatedResult = ComponentGenerationEvaluator.evaluate(
            compiled = outcome.generation,
            affectedPaths = affected,
            baseClassLoader = outcome.environment.runtimeClassLoader,
            previous = previousEvaluated,
            onDisposed = candidateEnvironment::close,
            onDisposalFailure = ::reportGenerationCleanupFailure
        )
        val candidateEvaluated = when (evaluatedResult) {
            is ComponentEvaluationResult.Failure -> {
                closeCompiledSafely(outcome.generation)
                val diagnostic = ScriptDiagnostic(
                    evaluatedResult.diagnostic.source,
                    ScriptDiagnosticPhase.EVALUATE,
                    evaluatedResult.diagnostic.message,
                    evaluatedResult.diagnostic.line,
                    evaluatedResult.diagnostic.column
                )
                logCause(evaluatedResult.diagnostic)
                return GenerationApplyResult(
                    false,
                    listOf(diagnostic),
                    cacheRetryable = outcome.cacheHit && evaluatedResult.cacheRetryable
                )
            }
            is ComponentEvaluationResult.Success -> evaluatedResult.generation
        }

        val previousByPath = previousLoaded.associateBy(LoadedScript::name)
        val stagedByPath = linkedMapOf<String, LoadedScript>()
        var declarationFailure: ScriptDiagnostic? = null
        candidate.forEach { source ->
            if (source.name !in affected) {
                previousByPath[source.name]?.let { stagedByPath[source.name] = it }
                return@forEach
            }
            val evaluated = candidateEvaluated.scripts[source.name]
            if (evaluated == null) {
                declarationFailure = ScriptDiagnostic(
                    source.name,
                    ScriptDiagnosticPhase.EVALUATE,
                    "Candidate evaluation did not produce a script DSL instance"
                )
                return@forEach
            }
            val active = try {
                ActiveScript(
                    evaluated.script,
                    source.name,
                    plugin,
                    commandRegistry,
                    candidateEvaluated.executionContext
                )
            } catch (error: Throwable) {
                declarationFailure = failure(
                    source.name,
                    ScriptDiagnosticPhase.EVALUATE,
                    "Script declaration staging failed: ${error.message}",
                    error
                )
                return@forEach
            }
            stagedByPath[source.name] = source.copy(active = active)
        }
        declarationFailure?.let { diagnostic ->
            val stagedAffected = outcome.generation.graph.initializationOrder
                .mapNotNull(stagedByPath::get)
                .filter { entry -> entry.name in affected }
            disposeEntries(stagedAffected.asReversed())
            disposeEvaluatedDeclarations(candidateEvaluated, affected)
            retireEvaluatedSafely(candidateEvaluated)
            closeCompiledSafely(outcome.generation)
            return GenerationApplyResult(false, listOf(diagnostic))
        }

        val previousAffected = orderedActive(previousLoaded, previousCompiled)
            .filter { entry -> entry.name in affected }
        val candidateAffected = outcome.generation.graph.initializationOrder
            .mapNotNull(stagedByPath::get)
            .filter { entry -> entry.name in affected }
        var transitionFailure: ScriptDiagnostic? = disposeForReplacement(previousAffected.asReversed())
        if (transitionFailure == null) {
            ReplStateBridge.publish(candidateEvaluated.state)
            transitionFailure = activateEntries(candidateAffected)
        }
        if (transitionFailure != null) {
            return rollback(
                previousLoaded,
                previousCompiled,
                previousEvaluated,
                previousEnvironment,
                previousStateTable,
                previousAffected.map(LoadedScript::name).toSet(),
                candidateAffected,
                candidateEvaluated,
                outcome,
                transitionFailure
            )
        }

        stagedByPath.values.forEach { entry ->
            entry.active?.updateExecutionContext(candidateEvaluated.executionContext)
        }
        val committedLoaded = candidate.map { source -> requireNotNull(stagedByPath[source.name]) }
        state.loaded.clear()
        state.loaded += committedLoaded
        state.compiled = outcome.generation
        state.evaluated = candidateEvaluated
        state.environment = outcome.environment
        state.revision++
        retireEvaluatedSafely(previousEvaluated)
        closeCompiledSafely(previousCompiled)
        return GenerationApplyResult(true, emptyList())
    }

    private fun rollback(
        previousLoaded: List<LoadedScript>,
        previousCompiled: CompiledComponentGeneration?,
        previousEvaluated: EvaluatedComponentGeneration?,
        previousEnvironment: ScriptCompilationEnvironment?,
        previousStateTable: ReplStateBridge.StateTable,
        affectedPaths: Set<String>,
        candidateAffected: List<LoadedScript>,
        candidateEvaluated: EvaluatedComponentGeneration,
        outcome: ScriptCompilationOutcome.Success,
        originalFailure: ScriptDiagnostic
    ): GenerationApplyResult {
        disposeEntries(candidateAffected.asReversed())
        disposeEvaluatedDeclarations(candidateEvaluated, candidateAffected.map(LoadedScript::name).toSet())
        ReplStateBridge.publish(previousStateTable)
        retireEvaluatedSafely(candidateEvaluated)
        closeCompiledSafely(outcome.generation)

        if (previousCompiled == null || previousEvaluated == null) {
            disposeEntries(previousLoaded.filterNot { entry -> entry.name in affectedPaths }.asReversed())
            ReplStateBridge.publish(ReplStateBridge.StateTable())
            state.loaded.clear()
            state.compiled = null
            state.evaluated = null
            state.environment = null
            state.revision++
            retireEvaluatedSafely(previousEvaluated)
            closeCompiledSafely(previousCompiled)
            return GenerationApplyResult(
                false,
                listOf(
                    originalFailure,
                    ScriptDiagnostic(
                        affectedPaths.firstOrNull() ?: "<rollback>",
                        ScriptDiagnosticPhase.ROLLBACK,
                        "Previous component generation is unavailable for rollback"
                    )
                )
            )
        }

        if (affectedPaths.isEmpty()) {
            return GenerationApplyResult(
                false,
                listOf(originalFailure)
            )
        }

        val rollbackEnvironment = previousEnvironment?.retained()
        val restoredResult = ComponentGenerationEvaluator.evaluate(
            compiled = previousCompiled,
            affectedPaths = affectedPaths,
            baseClassLoader = previousEnvironment?.runtimeClassLoader ?: plugin.javaClass.classLoader,
            previous = previousEvaluated,
            onDisposed = rollbackEnvironment?.let { retained -> retained::close } ?: {},
            onDisposalFailure = ::reportGenerationCleanupFailure
        )
        val restoredEvaluated = (restoredResult as? ComponentEvaluationResult.Success)?.generation
        var restoreFailure = (restoredResult as? ComponentEvaluationResult.Failure)?.diagnostic?.let { diagnostic ->
            logCause(diagnostic)
            ScriptDiagnostic(
                diagnostic.source,
                ScriptDiagnosticPhase.ROLLBACK,
                diagnostic.message,
                diagnostic.line,
                diagnostic.column
            )
        }
        val previousSources = previousLoaded.associateBy(LoadedScript::name)
        val restoredByPath = linkedMapOf<String, LoadedScript>()
        if (restoredEvaluated != null) {
            previousLoaded.forEach { old ->
                if (old.name !in affectedPaths) {
                    restoredByPath[old.name] = old
                } else {
                    val evaluated = restoredEvaluated.scripts[old.name]
                    if (evaluated == null) {
                        restoreFailure = ScriptDiagnostic(
                            old.name,
                            ScriptDiagnosticPhase.ROLLBACK,
                            "Rollback evaluation did not produce a script DSL instance"
                        )
                    } else if (restoreFailure == null) {
                        runCatching {
                            ActiveScript(
                                evaluated.script,
                                old.name,
                                plugin,
                                commandRegistry,
                                restoredEvaluated.executionContext
                            )
                        }.onSuccess { active ->
                            restoredByPath[old.name] = previousSources.getValue(old.name).copy(active = active)
                        }.onFailure { error ->
                            restoreFailure = failure(
                                old.name,
                                ScriptDiagnosticPhase.ROLLBACK,
                                "Rollback declaration staging failed: ${error.message}",
                                error
                            )
                        }
                    }
                }
            }
        }
        val restoredAffected = previousCompiled.graph.initializationOrder
            .mapNotNull(restoredByPath::get)
            .filter { entry -> entry.name in affectedPaths }
        if (restoreFailure == null && restoredEvaluated != null) {
            ReplStateBridge.publish(restoredEvaluated.state)
            restoreFailure = activateEntries(restoredAffected, ScriptDiagnosticPhase.ROLLBACK)
        }

        if (restoreFailure == null && restoredEvaluated != null) {
            restoredByPath.values.forEach { entry ->
                entry.active?.updateExecutionContext(restoredEvaluated.executionContext)
            }
            val restoredLoaded = previousLoaded.map { old -> requireNotNull(restoredByPath[old.name]) }
            state.loaded.clear()
            state.loaded += restoredLoaded
            state.compiled = previousCompiled
            state.evaluated = restoredEvaluated
            state.environment = previousEnvironment
            retireEvaluatedSafely(previousEvaluated)
        } else {
            disposeEntries(restoredAffected.asReversed())
            restoredEvaluated?.let { evaluated ->
                disposeEvaluatedDeclarations(evaluated, affectedPaths)
            }
            retireEvaluatedSafely(restoredEvaluated)
            val retainedPaths = previousLoaded.asSequence()
                .map(LoadedScript::name)
                .filterNot(affectedPaths::contains)
                .toSet()
            val degraded = runCatching {
                val degradedCompiled = previousCompiled.retainedSubset(retainedPaths)
                try {
                    val degradedEnvironment = previousEnvironment?.retained()
                    val degradedEvaluated = ComponentGenerationEvaluator.retainedSubset(
                        previous = previousEvaluated,
                        compiled = degradedCompiled,
                        paths = retainedPaths,
                        onDisposed = degradedEnvironment?.let { retained -> retained::close } ?: {},
                        onDisposalFailure = ::reportGenerationCleanupFailure
                    )
                    degradedCompiled to degradedEvaluated
                } catch (error: Throwable) {
                    runCatching(degradedCompiled::close).exceptionOrNull()?.let { cleanupFailure ->
                        if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
                    }
                    throw error
                }
            }
            degraded.onSuccess { (degradedCompiled, degradedEvaluated) ->
                val retainedLoaded = previousLoaded.filterNot { entry -> entry.name in affectedPaths }
                retainedLoaded.forEach { entry ->
                    entry.active?.updateExecutionContext(degradedEvaluated.executionContext)
                }
                ReplStateBridge.publish(degradedEvaluated.state)
                state.loaded.clear()
                state.loaded += retainedLoaded
                state.compiled = degradedCompiled
                state.evaluated = degradedEvaluated
                state.environment = previousEnvironment
                retireEvaluatedSafely(previousEvaluated)
                closeCompiledSafely(previousCompiled)
            }.onFailure { error ->
                disposeEntries(previousLoaded.filterNot { entry -> entry.name in affectedPaths }.asReversed())
                ReplStateBridge.publish(ReplStateBridge.StateTable())
                state.loaded.clear()
                state.compiled = null
                state.evaluated = null
                state.environment = null
                retireEvaluatedSafely(previousEvaluated)
                closeCompiledSafely(previousCompiled)
                val degradedFailure = failure(
                    affectedPaths.firstOrNull() ?: "<rollback>",
                    ScriptDiagnosticPhase.ROLLBACK,
                    "Could not preserve the unaffected generation: ${error.message}",
                    error
                )
                if (restoreFailure == null) restoreFailure = degradedFailure
            }
            state.revision++
        }
        return GenerationApplyResult(
            false,
            buildList {
                add(originalFailure)
                restoreFailure?.let(::add)
            }
        )
    }

    private fun activateEntries(
        entries: List<LoadedScript>,
        phase: ScriptDiagnosticPhase = ScriptDiagnosticPhase.ACTIVATE
    ): ScriptDiagnostic? {
        entries.forEach { entry ->
            val active = entry.active ?: return ScriptDiagnostic(entry.name, phase, "Script instance data is missing")
            runCatching(active::activateCommands).exceptionOrNull()?.let { error ->
                return failure(entry.name, phase, "Script command activation failed: ${error.message}", error)
            }
        }
        entries.forEach { entry ->
            val active = entry.active ?: return ScriptDiagnostic(entry.name, phase, "Script instance data is missing")
            runCatching(active::activateListeners).exceptionOrNull()?.let { error ->
                return failure(entry.name, phase, "Script listener activation failed: ${error.message}", error)
            }
        }
        entries.forEach { entry ->
            val active = entry.active ?: return ScriptDiagnostic(entry.name, phase, "Script instance data is missing")
            runCatching(active::invokeLoad).exceptionOrNull()?.let { error ->
                return failure(entry.name, phase, "Script onLoad failed: ${error.message}", error)
            }
        }
        return null
    }

    private fun disposeForReplacement(entries: List<LoadedScript>): ScriptDiagnostic? {
        var first: ScriptDiagnostic? = null
        entries.forEach { entry ->
            entry.active?.let { active ->
                runCatching(active::dispose).onFailure { error ->
                    reportCleanupFailure(entry.name, error)
                    if (first == null) {
                        first = ScriptDiagnostic(
                            entry.name,
                            ScriptDiagnosticPhase.ACTIVATE,
                            "Script onUnload or resource cleanup failed: ${error.message}"
                        )
                    }
                }
            }
        }
        return first
    }

    private fun disposeEntries(entries: List<LoadedScript>) {
        entries.forEach { entry ->
            entry.active?.let { active ->
                runCatching(active::dispose).onFailure { error ->
                    reportCleanupFailure(entry.name, error)
                }
            }
        }
    }

    private fun disposeEvaluatedDeclarations(
        evaluated: EvaluatedComponentGeneration,
        paths: Set<String>
    ) {
        evaluated.compiled.graph.initializationOrder.asReversed()
            .asSequence()
            .filter(paths::contains)
            .mapNotNull(evaluated.scripts::get)
            .forEach { evaluatedScript ->
                runCatching {
                    evaluated.executionContext.executeOrNull(evaluatedScript.script::disposeDeclarations)
                        ?: evaluatedScript.script.disposeDeclarations()
                }.onFailure { error ->
                    reportCleanupFailure(evaluatedScript.compiled.source.name, error)
                }
            }
    }

    private fun retireEvaluatedSafely(generation: EvaluatedComponentGeneration?) {
        generation ?: return
        runCatching(generation::close).onFailure(::reportGenerationCleanupFailure)
    }

    private fun closeCompiledSafely(generation: CompiledComponentGeneration?) {
        generation ?: return
        runCatching(generation::close).onFailure(::reportGenerationCleanupFailure)
    }

    private fun reportGenerationCleanupFailure(error: Throwable) {
        reportCleanupFailure("<generation>", error)
    }

    private fun reportCleanupFailure(source: String, error: Throwable) {
        runCatching {
            system(
                systemMessage(
                    MessageLevel.WARNING,
                    MessageKey.SYSTEM_SCRIPT_CLEANUP_FAILED,
                    "source" to source,
                    "error" to (error.message ?: error.javaClass.name),
                    cause = error
                )
            )
        }
        error.suppressed.forEach { suppressed ->
            runCatching {
                system(
                    systemMessage(
                        MessageLevel.WARNING,
                        MessageKey.SYSTEM_SCRIPT_CLEANUP_ADDITIONAL,
                        "source" to source,
                        "error" to (suppressed.message ?: suppressed.javaClass.name),
                        cause = suppressed
                    )
                )
            }
        }
    }

    private fun orderedActive(
        entries: List<LoadedScript>,
        compiled: CompiledComponentGeneration?
    ): List<LoadedScript> {
        val byPath = entries.associateBy(LoadedScript::name)
        return compiled?.graph?.initializationOrder?.mapNotNull(byPath::get) ?: entries
    }

    private fun failure(
        source: String,
        phase: ScriptDiagnosticPhase,
        message: String,
        error: Throwable
    ): ScriptDiagnostic {
        system(
            systemMessage(
                MessageLevel.ERROR,
                MessageKey.SYSTEM_SCRIPT_FAILURE_CAUSE,
                "source" to source,
                "message" to message,
                cause = error
            )
        )
        return ScriptDiagnostic(source, phase, message)
    }

    private fun logCause(diagnostic: eternalscript.scripting.repl.SharedReplDiagnostic) {
        diagnostic.cause?.let { error ->
            system(
                systemMessage(
                    MessageLevel.ERROR,
                    MessageKey.SYSTEM_SCRIPT_FAILURE_CAUSE,
                    "source" to diagnostic.source,
                    "message" to diagnostic.message,
                    cause = error
                )
            )
        }
    }

}
