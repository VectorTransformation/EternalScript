package eternalscript.scripting.repl.k2

import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource
import eternalscript.scripting.runtime.CleanupFailureCollector
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class ComponentCompilationMetrics(
    val analyzed: Int,
    val compiled: Int,
    val reused: Int,
    val components: Int
)

internal sealed interface ComponentCompilationResult {
    data class Success(
        val generation: CompiledComponentGeneration,
        val affectedPaths: List<String>,
        val metrics: ComponentCompilationMetrics
    ) : ComponentCompilationResult

    data class Failure(val diagnostic: SharedReplDiagnostic) : ComponentCompilationResult
}

internal data class CompiledComponent(
    val component: ScriptComponent,
    val sources: List<SharedReplSource>,
    val scripts: List<BatchCompiledScript>,
    val artifact: CompiledComponentArtifact
) : AutoCloseable {
    val descriptors: List<ProviderSnippetDescriptor> = scripts.map { script ->
        ProviderSnippetDescriptor(script.source.name, script.className, script.stateKey)
    }

    fun retained(): CompiledComponent = copy(artifact = artifact.retain())

    override fun close() {
        artifact.close()
    }
}

internal data class CompiledComponentGeneration(
    val graph: ScriptDependencyGraph,
    val sources: List<SharedReplSource>,
    val components: Map<String, CompiledComponent>
) : AutoCloseable {
    private val closed = AtomicBoolean()

    val scripts: List<BatchCompiledScript> = graph.initializationOrder.map { path ->
        components.values.asSequence()
            .flatMap { component -> component.scripts.asSequence() }
            .single { script -> script.source.name == path }
    }

    fun retained(): CompiledComponentGeneration = CompiledComponentGeneration(
        graph,
        sources,
        retainComponents(components.keys)
    )

    fun retainedSubset(paths: Collection<String>): CompiledComponentGeneration {
        val selected = paths.toSet()
        val subsetGraph = graph.induced(selected)
        return CompiledComponentGeneration(
            subsetGraph,
            sources.filter { source -> source.name in selected },
            retainComponents(subsetGraph.components.map(ScriptComponent::id))
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failures = CleanupFailureCollector()
        closeComponents(components.values, failures)
        failures.throwIfAny()
    }

    private fun retainComponents(componentIds: Iterable<String>): Map<String, CompiledComponent> {
        val retained = linkedMapOf<String, CompiledComponent>()
        try {
            componentIds.forEach { componentId ->
                retained[componentId] = components.getValue(componentId).retained()
            }
            return retained
        } catch (error: Throwable) {
            val failures = CleanupFailureCollector()
            closeComponents(retained.values, failures)
            failures.suppressInto(error)
            throw error
        }
    }

    private fun closeComponents(
        values: Collection<CompiledComponent>,
        failures: CleanupFailureCollector
    ) {
        val seen = Collections.newSetFromMap(IdentityHashMap<CompiledComponent, Boolean>())
        values.forEach { component ->
            if (seen.add(component)) failures.attempt(component::close)
        }
    }
}
