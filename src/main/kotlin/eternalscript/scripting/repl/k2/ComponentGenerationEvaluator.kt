package eternalscript.scripting.repl.k2

import eternalscript.api.script.Script
import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.runtime.CleanupFailureCollector
import eternalscript.scripting.runtime.ReplStateBridge
import eternalscript.scripting.runtime.ScriptExecutionContext
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal sealed interface ComponentEvaluationResult {
    data class Success(
        val generation: EvaluatedComponentGeneration,
        val evaluatedPaths: List<String>
    ) : ComponentEvaluationResult

    data class Failure(
        val diagnostic: SharedReplDiagnostic,
        val cacheRetryable: Boolean = false
    ) : ComponentEvaluationResult
}

internal class EvaluatedComponentGeneration(
    val compiled: CompiledComponentGeneration,
    val scripts: Map<String, BatchEvaluatedScript>,
    val loaders: Map<String, ManagedComponentClassLoader>,
    val state: ReplStateBridge.StateTable,
    val executionContext: ScriptExecutionContext
) : AutoCloseable {
    override fun close() {
        executionContext.retire()
    }
}

internal class ManagedComponentClassLoader private constructor(
    private val delegate: ChildFirstComponentClassLoader
) : ClassLoader(delegate), AutoCloseable {
    private val references = AtomicInteger(1)

    fun retained(): ManagedComponentClassLoader {
        while (true) {
            val current = references.get()
            check(current > 0) { "A disposed component classloader cannot be retained" }
            if (references.compareAndSet(current, current + 1)) return this
        }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = delegate.loadClass(name)

    override fun close() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "A component classloader was closed more than once" }
        if (remaining == 0) delegate.close()
    }

    companion object {
        fun create(
            component: CompiledComponent,
            providers: List<ManagedComponentClassLoader>,
            baseClassLoader: ClassLoader
        ): ManagedComponentClassLoader {
            val parent = ComponentDependencyClassLoader(providers, baseClassLoader)
            return ManagedComponentClassLoader(
                ChildFirstComponentClassLoader(
                    arrayOf(component.artifact.jar.toUri().toURL()),
                    parent,
                    component.artifact.ownedClasses
                )
            )
        }
    }
}

internal object ComponentGenerationEvaluator {
    fun retainedSubset(
        previous: EvaluatedComponentGeneration,
        compiled: CompiledComponentGeneration,
        paths: Collection<String>,
        onDisposed: () -> Unit = {},
        onDisposalFailure: (Throwable) -> Unit = {}
    ): EvaluatedComponentGeneration {
        var executionArtifacts: CompiledComponentGeneration? = null
        val retainedLoaders = linkedMapOf<String, ManagedComponentClassLoader>()
        try {
            executionArtifacts = compiled.retained()
            val selected = paths.toSet()
            val scripts = previous.scripts.filterKeys(selected::contains)
            val stateKeys = scripts.values.mapTo(hashSetOf()) { script -> script.compiled.stateKey }
            val state = ReplStateBridge.StateTable(
                previous.state.instances.filterKeys(stateKeys::contains).toMutableMap(),
                previous.state.ready.filterTo(linkedSetOf(), stateKeys::contains)
            )
            compiled.graph.components.forEach { component ->
                retainedLoaders[component.id] = previous.loaders.getValue(component.id).retained()
            }
            val ownedArtifacts = requireNotNull(executionArtifacts)
            val ownedLoaders = retainedLoaders.toMap()
            val executionContext = ScriptExecutionContext(
                state = state,
                dispose = { disposeOwnedResources(ownedLoaders.values, ownedArtifacts, onDisposed) },
                reportDisposalFailure = onDisposalFailure
            )
            return EvaluatedComponentGeneration(ownedArtifacts, scripts, ownedLoaders, state, executionContext)
        } catch (error: Throwable) {
            val failures = CleanupFailureCollector()
            closeLoaders(retainedLoaders.values, failures)
            executionArtifacts?.let { artifacts -> failures.attempt(artifacts::close) }
            failures.attempt(onDisposed)
            failures.suppressInto(error)
            throw error
        }
    }

    fun evaluate(
        compiled: CompiledComponentGeneration,
        affectedPaths: Collection<String>,
        baseClassLoader: ClassLoader,
        previous: EvaluatedComponentGeneration? = null,
        onDisposed: () -> Unit = {},
        onDisposalFailure: (Throwable) -> Unit = {}
    ): ComponentEvaluationResult {
        val affected = affectedPaths.toSet()
        var executionArtifacts: CompiledComponentGeneration? = null
        val loaders = linkedMapOf<String, ManagedComponentClassLoader>()
        val evaluated = linkedMapOf<String, BatchEvaluatedScript>()
        var state: ReplStateBridge.StateTable? = null
        var topLevelStarted = false

        try {
            executionArtifacts = compiled.retained()
            val workingState = previous?.state?.copy() ?: ReplStateBridge.StateTable()
            state = workingState
            val oldStateKeys = previous?.scripts.orEmpty()
                .filterKeys(affected::contains)
                .values
                .map { script -> script.compiled.stateKey }
            val newStateKeys = compiled.scripts.filter { script -> script.source.name in affected }
                .map(BatchCompiledScript::stateKey)
            (oldStateKeys + newStateKeys).forEach { stateKey ->
                workingState.instances.remove(stateKey)
                workingState.ready.remove(stateKey)
            }
            compiled.graph.componentOrder().forEach { component ->
                val oldLoader = previous?.loaders?.get(component.id)
                val componentAffected = component.paths.any(affected::contains) || oldLoader == null
                if (!componentAffected) {
                    loaders[component.id] = oldLoader.retained()
                    component.paths.forEach { path ->
                        previous.scripts[path]?.let { script -> evaluated[path] = script }
                    }
                } else {
                    val providerLoaders = component.dependencies.map { provider ->
                        requireNotNull(loaders[provider]) { "Provider component loader is not ready: $provider" }
                    }
                    loaders[component.id] = ManagedComponentClassLoader.create(
                        compiled.components.getValue(component.id),
                        providerLoaders,
                        baseClassLoader
                    )
                }
            }

            ReplStateBridge.stage(workingState) {
                val scriptByPath = compiled.scripts.associateBy { script -> script.source.name }
                compiled.graph.initializationOrder.filter(affected::contains).forEach { path ->
                    val compiledScript = scriptByPath.getValue(path)
                    val component = compiled.graph.componentOf(path)
                        ?: error("No component owns script: $path")
                    val loader = loaders.getValue(component.id)
                    evaluated[path] = evaluateScript(compiledScript, loader) {
                        topLevelStarted = true
                    }
                }
            }
        } catch (error: Throwable) {
            state?.let { workingState ->
                runCatching {
                    ReplStateBridge.stage(workingState) {
                        disposeEvaluatedScripts(compiled, affected, evaluated, error)
                    }
                }.exceptionOrNull()?.let { cleanupFailure ->
                    if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
                }
            }
            val failures = CleanupFailureCollector()
            closeLoaders(loaders.values, failures)
            executionArtifacts?.let { artifacts -> failures.attempt(artifacts::close) }
            failures.attempt(onDisposed)
            failures.suppressInto(error)
            val source = compiled.graph.initializationOrder.firstOrNull { path ->
                path in affected && path !in evaluated
            } ?: "<component-evaluation>"
            return ComponentEvaluationResult.Failure(
                SharedReplDiagnostic(source, error.message ?: error.javaClass.name, cause = error),
                cacheRetryable = !topLevelStarted
            )
        }

        val ownedArtifacts = requireNotNull(executionArtifacts)
        val ownedState = requireNotNull(state)
        val ownedLoaders = loaders.toMap()
        val executionContext = ScriptExecutionContext(
            state = ownedState,
            dispose = { disposeOwnedResources(ownedLoaders.values, ownedArtifacts, onDisposed) },
            reportDisposalFailure = onDisposalFailure
        )
        return ComponentEvaluationResult.Success(
            EvaluatedComponentGeneration(
                ownedArtifacts,
                evaluated.toMap(),
                ownedLoaders,
                ownedState,
                executionContext
            ),
            compiled.graph.initializationOrder.filter(affected::contains)
        )
    }

    private fun disposeOwnedResources(
        loaders: Collection<ManagedComponentClassLoader>,
        artifacts: CompiledComponentGeneration,
        onDisposed: () -> Unit
    ) {
        val failures = CleanupFailureCollector()
        closeLoaders(loaders, failures)
        failures.attempt(artifacts::close)
        failures.attempt(onDisposed)
        failures.throwIfAny()
    }

    private fun closeLoaders(
        loaders: Collection<ManagedComponentClassLoader>,
        failures: CleanupFailureCollector
    ) {
        val seen = Collections.newSetFromMap(IdentityHashMap<ManagedComponentClassLoader, Boolean>())
        loaders.forEach { loader ->
            if (seen.add(loader)) failures.attempt(loader::close)
        }
    }

    private fun evaluateScript(
        script: BatchCompiledScript,
        classLoader: ClassLoader,
        onTopLevelStarted: () -> Unit = {}
    ): BatchEvaluatedScript {
        val type = classLoader.loadClass(script.className)
        val instance = type.getField("INSTANCE").get(null)
        val eval = type.methods.singleOrNull { method -> method.name == "\$\$eval" }
            ?: error("Generated script has no \$\$eval method: ${script.className}")
        val scriptDsl = ComponentRuntimeScript().also { instance ->
            instance.attachRuntimeSource(script.source.name)
        }
        ReplStateBridge.beginEvaluation(script.stateKey)
        try {
            try {
                onTopLevelStarted()
                when (eval.parameterCount) {
                    0 -> eval.invoke(instance)
                    1 -> eval.invoke(instance, scriptDsl)
                    else -> error(
                        "Generated script has unsupported \$\$eval parameters: ${eval.parameterTypes.joinToString()}"
                    )
                }
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            val value = script.resultFieldName?.let { fieldName ->
                type.getDeclaredField(fieldName).apply { trySetAccessible() }.get(instance)
            }
            ReplStateBridge.markReady(script.stateKey)
            return BatchEvaluatedScript(script, instance, scriptDsl, value)
        } catch (error: Throwable) {
            runCatching(scriptDsl::disposeDeclarations).exceptionOrNull()?.let { cleanupFailure ->
                if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
            }
            throw error
        } finally {
            ReplStateBridge.endEvaluation(script.stateKey)
        }
    }

    private fun disposeEvaluatedScripts(
        compiled: CompiledComponentGeneration,
        affected: Set<String>,
        evaluated: Map<String, BatchEvaluatedScript>,
        failure: Throwable
    ) {
        compiled.graph.initializationOrder.asReversed()
            .asSequence()
            .filter(affected::contains)
            .mapNotNull(evaluated::get)
            .forEach { evaluatedScript ->
                runCatching(evaluatedScript.script::disposeDeclarations).exceptionOrNull()?.let { cleanupFailure ->
                    if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
                }
            }
    }
}

private class ComponentRuntimeScript : Script()

private class ComponentDependencyClassLoader(
    private val providers: List<ManagedComponentClassLoader>,
    private val baseClassLoader: ClassLoader
) : ClassLoader(null) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        providers.asReversed().forEach { provider ->
            try {
                return provider.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }
        return baseClassLoader.loadClass(name)
    }
}

private class ChildFirstComponentClassLoader(
    urls: Array<java.net.URL>,
    parent: ClassLoader,
    private val ownedClasses: Set<String>
) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { loaded -> return loaded }
            if (name in ownedClasses) {
                try {
                    return findClass(name).also { loaded -> if (resolve) resolveClass(loaded) }
                } catch (_: ClassNotFoundException) {}
            }
            return super.loadClass(name, resolve)
        }
    }
}
