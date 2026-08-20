package eternalscript.scripting.repl.k2

import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource

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
    val scripts: List<BatchCompiledScript> = graph.initializationOrder.map { path ->
        components.values.asSequence()
            .flatMap { component -> component.scripts.asSequence() }
            .single { script -> script.source.name == path }
    }

    fun retained(): CompiledComponentGeneration = CompiledComponentGeneration(
        graph,
        sources,
        components.mapValues { (_, component) -> component.retained() }
    )

    fun retainedSubset(paths: Collection<String>): CompiledComponentGeneration {
        val selected = paths.toSet()
        val subsetGraph = graph.induced(selected)
        return CompiledComponentGeneration(
            subsetGraph,
            sources.filter { source -> source.name in selected },
            subsetGraph.components.associate { component ->
                component.id to components.getValue(component.id).retained()
            }
        )
    }

    override fun close() {
        components.values.toSet().forEach(CompiledComponent::close)
    }
}
