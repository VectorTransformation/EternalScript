package eternalscript.scripting.repl.k2

import eternalscript.scripting.repl.SharedReplDiagnostic
import eternalscript.scripting.repl.SharedReplSource
import java.nio.file.Path
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.jvm.withUpdatedClasspath

internal class ScriptComponentCompiler(
    private val baseConfiguration: ScriptCompilationConfiguration,
    private val artifactRoot: Path,
    private val baseClassLoader: ClassLoader
) {
    fun compile(
        candidateSources: List<SharedReplSource>,
        previous: CompiledComponentGeneration? = null,
        forceAll: Boolean = false,
        forcedPaths: Set<String> = emptySet(),
        analyzedGraph: ScriptDependencyGraph? = null,
        analyzedCount: Int = candidateSources.size
    ): ComponentCompilationResult {
        val sourceChanges = changedPaths(previous?.sources.orEmpty(), candidateSources)
        val canCompileWholeGeneration = analyzedGraph == null &&
            forcedPaths.isEmpty() &&
            (
                previous == null ||
                    forceAll ||
                    (previous.graph.components.size == 1 && sourceChanges.isNotEmpty())
            )
        val wholeBatch = if (canCompileWholeGeneration) {
            val result = BatchK2Compiler(
                componentConfiguration(emptyList()),
                batchScriptingHostConfiguration(baseClassLoader = baseClassLoader)
            ).use { compiler -> compiler.compile(candidateSources) }
            when (result) {
                is BatchCompilationResult.Success -> result.generation
                is BatchCompilationResult.Failure -> return ComponentCompilationResult.Failure(result.diagnostic)
            }
        } else {
            null
        }
        val graph = analyzedGraph ?: wholeBatch?.graph ?: run {
            val analysis = BatchK2Compiler(
                baseConfiguration,
                batchScriptingHostConfiguration(baseClassLoader = baseClassLoader)
            ).use { compiler -> compiler.analyze(candidateSources) }
            when (analysis) {
                is BatchAnalysisResult.Success -> analysis.graph
                is BatchAnalysisResult.Failure -> return ComponentCompilationResult.Failure(analysis.diagnostic)
            }
        }
        check(graph.paths == candidateSources.map(SharedReplSource::name).sorted()) {
            "The analyzed graph does not match the candidate sources"
        }
        check(graph.paths.containsAll(forcedPaths)) {
            "Forced script paths are not present in the candidate sources: " +
                forcedPaths.filterNot(graph.paths::contains).sorted().joinToString()
        }

        val changedPaths = sourceChanges.toMutableSet()
        changedPaths += forcedPaths
        if (forceAll || previous == null) {
            changedPaths += previous?.graph?.paths.orEmpty()
            changedPaths += graph.paths
        } else {
            val allPaths = previous.graph.paths.toSet() + graph.paths
            allPaths.forEach { path ->
                val oldComponent = previous.graph.componentOf(path)?.paths
                val newComponent = graph.componentOf(path)?.paths
                if (
                    oldComponent != newComponent ||
                    previous.graph.dependencies[path] != graph.dependencies[path] ||
                    previous.graph.initializationDependencies[path] != graph.initializationDependencies[path]
                ) {
                    changedPaths += path
                }
            }
        }

        val affectedPaths = linkedSetOf<String>()
        if (previous != null) {
            val oldAffected = previous.graph.affectedComponents(changedPaths)
            previous.graph.components.filter { component -> component.id in oldAffected }
                .flatMapTo(affectedPaths, ScriptComponent::paths)
        }
        val newAffected = graph.affectedComponents(changedPaths)
        graph.components.filter { component -> component.id in newAffected }
            .flatMapTo(affectedPaths, ScriptComponent::paths)

        val sourceByPath = candidateSources.associateBy(SharedReplSource::name)
        val compiled = linkedMapOf<String, CompiledComponent>()
        var compiledScripts = 0
        var reusedScripts = 0
        try {
            val orderedComponents = graph.componentOrder()
            val componentById = graph.components.associateBy(ScriptComponent::id)
            val componentsToCompile = mutableListOf<ScriptComponent>()
            orderedComponents.forEach { component ->
                val componentSources = component.paths.map(sourceByPath::getValue)
                val reusable = previous?.components?.get(component.id)
                    ?.takeIf { old ->
                        component.id !in newAffected &&
                            old.sources.map { it.name to it.hash } == componentSources.map { it.name to it.hash }
                    }
                if (reusable != null) {
                    compiled[component.id] = reusable.retained()
                    reusedScripts += component.paths.size
                    return@forEach
                }
                componentsToCompile += component
            }

            if (componentsToCompile.isNotEmpty()) {
                val sourceComponentIds = componentsToCompile.mapTo(linkedSetOf(), ScriptComponent::id)
                val requiredProviders = linkedSetOf<String>()
                fun collectBinaryProviders(componentId: String) {
                    componentById.getValue(componentId).dependencies.forEach { providerId ->
                        if (providerId !in sourceComponentIds && requiredProviders.add(providerId)) {
                            collectBinaryProviders(providerId)
                        }
                    }
                }
                componentsToCompile.forEach { component -> collectBinaryProviders(component.id) }
                val availableProviders = orderedComponents
                    .filter { provider -> provider.id in requiredProviders }
                    .map { provider -> compiled.getValue(provider.id) }
                val configuration = componentConfiguration(
                    availableProviders.map { provider -> provider.artifact.jar.toFile() }
                )
                val descriptors = availableProviders.flatMap(CompiledComponent::descriptors)
                val batchGeneration = wholeBatch ?: run {
                    val result = BatchK2Compiler(
                        configuration,
                        batchScriptingHostConfiguration(descriptors, baseClassLoader)
                    ).use { compiler ->
                        compiler.compile(
                            componentsToCompile.flatMap { component -> component.paths.map(sourceByPath::getValue) }
                        )
                    }
                    when (result) {
                        is BatchCompilationResult.Success -> result.generation
                        is BatchCompilationResult.Failure -> return failureAndClose(compiled, result.diagnostic)
                    }
                }
                val scriptByPath = batchGeneration.scripts.associateBy { script -> script.source.name }
                componentsToCompile.forEach { component ->
                    val componentSources = component.paths.map(sourceByPath::getValue)
                    val componentScripts = component.paths.map(scriptByPath::getValue)
                    val classPrefixes = componentScripts.mapTo(linkedSetOf()) { script ->
                        script.className.replace('.', '/')
                    }
                    val componentOutput = batchGeneration.outputFiles.filterKeys { name ->
                        if (!name.endsWith(".class")) return@filterKeys false
                        val internalName = name.removeSuffix(".class")
                        classPrefixes.any { prefix -> internalName == prefix || internalName.startsWith("$prefix$") }
                    }
                    val missingClasses = classPrefixes.filterNot { prefix -> "$prefix.class" in componentOutput }
                    if (missingClasses.isNotEmpty()) {
                        return failureAndClose(
                            compiled,
                            SharedReplDiagnostic(
                                component.paths.first(),
                                "K2 batch output is missing script class(es): ${missingClasses.joinToString()}"
                            )
                        )
                    }
                    val artifact = try {
                        CompiledComponentArtifact.create(artifactRoot, component.id, componentOutput)
                    } catch (error: Throwable) {
                        return failureAndClose(
                            compiled,
                            SharedReplDiagnostic(
                                component.paths.first(),
                                "Could not materialize component JAR: ${error.message}",
                                cause = error
                            )
                        )
                    }
                    compiled[component.id] = CompiledComponent(
                        component,
                        componentSources,
                        componentScripts.map { script -> script.copy(compiledScript = null) },
                        artifact
                    )
                    compiledScripts += component.paths.size
                }
            }
        } catch (error: Throwable) {
            return failureAndClose(
                compiled,
                SharedReplDiagnostic("<component-compiler>", error.message ?: error.javaClass.name, cause = error)
            )
        }

        val generation = CompiledComponentGeneration(graph, candidateSources, compiled.toMap())
        val orderedAffectedPaths = buildList {
            previous?.graph?.initializationOrder
                ?.filter(affectedPaths::contains)
                ?.let(::addAll)
            addAll(graph.initializationOrder.filter(affectedPaths::contains))
        }.distinct()
        return ComponentCompilationResult.Success(
            generation,
            orderedAffectedPaths,
            ComponentCompilationMetrics(
                analyzed = analyzedCount,
                compiled = compiledScripts,
                reused = reusedScripts,
                components = graph.components.size
            )
        )
    }

    private fun componentConfiguration(providerJars: List<java.io.File>): ScriptCompilationConfiguration =
        baseConfiguration.withUpdatedClasspath(providerJars)

    private fun failureAndClose(
        components: Map<String, CompiledComponent>,
        diagnostic: SharedReplDiagnostic
    ): ComponentCompilationResult.Failure {
        components.values.toSet().forEach { component -> runCatching(component::close) }
        return ComponentCompilationResult.Failure(diagnostic)
    }
}

private fun changedPaths(
    previous: List<SharedReplSource>,
    candidate: List<SharedReplSource>
): Set<String> {
    val oldHashes = previous.associate { source -> source.name to source.hash }
    val newHashes = candidate.associate { source -> source.name to source.hash }
    return (oldHashes.keys + newHashes.keys).filterTo(linkedSetOf()) { path -> oldHashes[path] != newHashes[path] }
}
