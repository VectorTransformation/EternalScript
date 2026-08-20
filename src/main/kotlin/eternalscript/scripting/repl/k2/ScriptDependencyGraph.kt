package eternalscript.scripting.repl.k2

import eternalscript.util.Sha256
import java.util.PriorityQueue

/**
 * A dependency edge is stored as consumer -> provider.  Components and scripts
 * are consequently ordered by walking the inverse edge (provider -> consumer).
 */
internal data class ScriptDependencyGraph(
    val paths: List<String>,
    val dependencies: Map<String, Set<String>>,
    val initializationDependencies: Map<String, Set<String>>,
    val components: List<ScriptComponent>,
    val initializationOrder: List<String>
) {
    private val componentByPath: Map<String, ScriptComponent> = buildMap {
        components.forEach { component -> component.paths.forEach { path -> put(path, component) } }
    }

    fun componentOf(path: String): ScriptComponent? = componentByPath[path]

    fun componentOrder(): List<ScriptComponent> {
        val byId = components.associateBy(ScriptComponent::id)
        val consumers = components.associate { component -> component.id to sortedSetOf<String>() }.toMutableMap()
        val remainingProviders = components.associate { component ->
            component.id to component.dependencies.size
        }.toMutableMap()
        components.forEach { component ->
            component.dependencies.forEach { provider -> consumers.getValue(provider) += component.id }
        }
        val ready = PriorityQueue(compareBy<String> { id -> byId.getValue(id).paths.first() })
        remainingProviders.filterValues { count -> count == 0 }.keys.forEach(ready::add)
        val order = mutableListOf<ScriptComponent>()
        while (ready.isNotEmpty()) {
            val provider = ready.remove()
            order += byId.getValue(provider)
            consumers.getValue(provider).forEach { consumer ->
                val next = remainingProviders.getValue(consumer) - 1
                remainingProviders[consumer] = next
                if (next == 0) ready += consumer
            }
        }
        check(order.size == components.size) { "The SCC condensation graph contains a cycle" }
        return order
    }

    fun affectedComponents(changedPaths: Set<String>): Set<String> {
        if (changedPaths.isEmpty()) return emptySet()
        val seeds = changedPaths.mapNotNull(componentByPath::get).mapTo(linkedSetOf(), ScriptComponent::id)
        if (seeds.isEmpty()) return emptySet()
        val consumers = buildMap<String, MutableSet<String>> {
            components.forEach { component ->
                component.dependencies.forEach { provider ->
                    getOrPut(provider) { linkedSetOf() } += component.id
                }
            }
        }
        val affected = LinkedHashSet(seeds)
        val queue = ArrayDeque(seeds)
        while (queue.isNotEmpty()) {
            consumers[queue.removeFirst()].orEmpty().sorted().forEach { consumer ->
                if (affected.add(consumer)) queue.addLast(consumer)
            }
        }
        return affected
    }

    fun affectedPaths(changedPaths: Set<String>): List<String> {
        val affected = affectedComponents(changedPaths)
        return initializationOrder.filter { path -> componentByPath[path]?.id in affected }
    }

    fun providerClosure(seedPaths: Collection<String>): Set<String> {
        val selectedComponents = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        fun addPath(path: String) {
            componentByPath[path]?.id?.let { componentId ->
                if (selectedComponents.add(componentId)) queue.addLast(componentId)
            }
        }
        seedPaths.forEach(::addPath)
        val byId = components.associateBy(ScriptComponent::id)
        while (queue.isNotEmpty()) {
            byId.getValue(queue.removeFirst()).dependencies.forEach { provider ->
                if (selectedComponents.add(provider)) queue.addLast(provider)
            }
        }
        return components.asSequence()
            .filter { component -> component.id in selectedComponents }
            .flatMap { component -> component.paths.asSequence() }
            .toCollection(linkedSetOf())
    }

    fun consumerClosure(seedPaths: Collection<String>): Set<String> {
        val affected = affectedComponents(seedPaths.toSet())
        return components.asSequence()
            .filter { component -> component.id in affected }
            .flatMap { component -> component.paths.asSequence() }
            .toCollection(linkedSetOf())
    }

    fun induced(selectedPaths: Collection<String>): ScriptDependencyGraph {
        val selected = selectedPaths.toSet()
        val result = create(
            paths.filter(selected::contains),
            dependencies.filterKeys(selected::contains)
                .mapValues { (_, providers) -> providers.filterTo(linkedSetOf(), selected::contains) },
            initializationDependencies.filterKeys(selected::contains)
                .mapValues { (_, providers) -> providers.filterTo(linkedSetOf(), selected::contains) }
        )
        return (result as ScriptGraphResult.Success).graph
    }

    companion object {
        fun create(
            paths: Collection<String>,
            dependencies: Map<String, Set<String>>,
            initializationDependencies: Map<String, Set<String>>
        ): ScriptGraphResult {
            val orderedPaths = paths.distinct().sorted()
            val pathSet = orderedPaths.toSet()
            val normalizedDependencies = normalizeEdges(orderedPaths, pathSet, dependencies)
            val normalizedInitialization = normalizeEdges(orderedPaths, pathSet, initializationDependencies)
            val components = stronglyConnectedComponents(orderedPaths, normalizedDependencies)
            val componentByPath = buildMap {
                components.forEach { component -> component.paths.forEach { path -> put(path, component.id) } }
            }
            val componentDependencies = components.associate { component ->
                component.id to component.paths.asSequence()
                    .flatMap { path -> normalizedDependencies.getValue(path).asSequence() }
                    .mapNotNull(componentByPath::get)
                    .filter { provider -> provider != component.id }
                    .toSortedSet()
            }
            val linkedComponents = components.map { component ->
                component.copy(dependencies = componentDependencies.getValue(component.id))
            }
            val initializationOrder = topologicalOrder(orderedPaths, normalizedInitialization)
                ?: return ScriptGraphResult.InitializationCycle(
                    findCycleNodes(orderedPaths, normalizedInitialization)
                )
            return ScriptGraphResult.Success(
                ScriptDependencyGraph(
                    orderedPaths,
                    normalizedDependencies,
                    normalizedInitialization,
                    linkedComponents,
                    initializationOrder
                )
            )
        }

        private fun normalizeEdges(
            orderedPaths: List<String>,
            paths: Set<String>,
            edges: Map<String, Set<String>>
        ): Map<String, Set<String>> = orderedPaths.associateWith { consumer ->
            edges[consumer].orEmpty().filterTo(sortedSetOf()) { provider -> provider in paths }
        }

        private fun stronglyConnectedComponents(
            paths: List<String>,
            dependencies: Map<String, Set<String>>
        ): List<ScriptComponent> {
            var nextIndex = 0
            val indices = mutableMapOf<String, Int>()
            val lowLinks = mutableMapOf<String, Int>()
            val stack = ArrayDeque<String>()
            val onStack = mutableSetOf<String>()
            val members = mutableListOf<List<String>>()

            fun visit(path: String) {
                indices[path] = nextIndex
                lowLinks[path] = nextIndex
                nextIndex++
                stack.addLast(path)
                onStack += path

                dependencies.getValue(path).sorted().forEach { provider ->
                    if (provider !in indices) {
                        visit(provider)
                        lowLinks[path] = minOf(lowLinks.getValue(path), lowLinks.getValue(provider))
                    } else if (provider in onStack) {
                        lowLinks[path] = minOf(lowLinks.getValue(path), indices.getValue(provider))
                    }
                }

                if (lowLinks.getValue(path) == indices.getValue(path)) {
                    val component = mutableListOf<String>()
                    while (true) {
                        val member = stack.removeLast()
                        onStack -= member
                        component += member
                        if (member == path) break
                    }
                    members += component.sorted()
                }
            }

            paths.forEach { path -> if (path !in indices) visit(path) }
            return members.sortedBy { component -> component.first() }.map { componentPaths ->
                ScriptComponent(componentId(componentPaths), componentPaths, emptySet())
            }
        }

        private fun topologicalOrder(
            nodes: List<String>,
            dependencies: Map<String, Set<String>>
        ): List<String>? {
            val providerConsumers = nodes.associateWithTo(mutableMapOf()) { sortedSetOf<String>() }
            val remainingProviders = nodes.associateWithTo(mutableMapOf()) { 0 }
            dependencies.forEach { (consumer, providers) ->
                remainingProviders[consumer] = providers.size
                providers.forEach { provider -> providerConsumers.getValue(provider) += consumer }
            }
            val ready = PriorityQueue<String>()
            remainingProviders.filterValues { count -> count == 0 }.keys.forEach(ready::add)
            val order = mutableListOf<String>()
            while (ready.isNotEmpty()) {
                val provider = ready.remove()
                order += provider
                providerConsumers.getValue(provider).forEach { consumer ->
                    val next = remainingProviders.getValue(consumer) - 1
                    remainingProviders[consumer] = next
                    if (next == 0) ready += consumer
                }
            }
            return order.takeIf { result -> result.size == nodes.size }
        }

        private fun findCycleNodes(
            nodes: List<String>,
            dependencies: Map<String, Set<String>>
        ): List<String> {
            val order = topologicalOrder(nodes, dependencies)
            if (order != null) return emptyList()
            val removed = mutableSetOf<String>()
            val providerConsumers = nodes.associateWithTo(mutableMapOf()) { mutableSetOf<String>() }
            val remainingProviders = nodes.associateWithTo(mutableMapOf()) { dependencies.getValue(it).size }
            dependencies.forEach { (consumer, providers) ->
                providers.forEach { provider -> providerConsumers.getValue(provider) += consumer }
            }
            val queue = ArrayDeque(nodes.filter { remainingProviders.getValue(it) == 0 }.sorted())
            while (queue.isNotEmpty()) {
                val provider = queue.removeFirst()
                removed += provider
                providerConsumers.getValue(provider).forEach { consumer ->
                    val next = remainingProviders.getValue(consumer) - 1
                    remainingProviders[consumer] = next
                    if (next == 0) queue.addLast(consumer)
                }
            }
            return nodes.filterNot(removed::contains)
        }

        private fun componentId(paths: List<String>): String = Sha256.text(paths.joinToString("\n")).take(16)
    }
}

internal data class ScriptComponent(
    val id: String,
    val paths: List<String>,
    val dependencies: Set<String>
)

internal sealed interface ScriptGraphResult {
    data class Success(val graph: ScriptDependencyGraph) : ScriptGraphResult
    data class InitializationCycle(val paths: List<String>) : ScriptGraphResult
}
