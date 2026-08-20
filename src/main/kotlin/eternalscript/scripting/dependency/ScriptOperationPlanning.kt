package eternalscript.scripting.dependency

import eternalscript.scripting.repl.k2.ScriptDependencyGraph
import eternalscript.scripting.source.ScriptTarget

internal sealed interface ScriptLoadPlan {
    data class Ready(val selectedPaths: Set<String>) : ScriptLoadPlan

    data class MissingPaths(
        val targetPaths: Set<String>,
        val activePaths: Set<String>
    ) : ScriptLoadPlan
}

internal data class ScriptUnloadPlan(
    val selectedPaths: Set<String>,
    val blockingConsumers: Set<String>,
    val missingGraphPaths: Set<String>
)

internal fun planScriptLoad(
    graph: ScriptDependencyGraph,
    targetPaths: Collection<String>,
    activePaths: Collection<String>
): ScriptLoadPlan {
    val graphPaths = graph.paths.toSet()
    val missingTargets = targetPaths.filterNotTo(linkedSetOf(), graphPaths::contains)
    val missingActive = activePaths.filterNotTo(linkedSetOf(), graphPaths::contains)
    if (missingTargets.isNotEmpty() || missingActive.isNotEmpty()) {
        return ScriptLoadPlan.MissingPaths(missingTargets, missingActive)
    }
    return ScriptLoadPlan.Ready(
        buildSet {
            addAll(activePaths)
            addAll(graph.providerClosure(targetPaths))
        }
    )
}

internal fun planScriptUnload(
    graph: ScriptDependencyGraph?,
    activePaths: Collection<String>,
    target: ScriptTarget
): ScriptUnloadPlan {
    val active = activePaths.toCollection(linkedSetOf())
    val selected = active.filterTo(linkedSetOf(), target::contains)
    if (selected.isEmpty()) {
        return ScriptUnloadPlan(emptySet(), emptySet(), emptySet())
    }

    val graphPaths = graph?.paths?.toSet().orEmpty()
    val missing = selected.filterNotTo(linkedSetOf(), graphPaths::contains)
    if (graph == null || missing.isNotEmpty()) {
        return ScriptUnloadPlan(selected, emptySet(), if (missing.isEmpty()) selected else missing)
    }

    val blocking = graph.consumerClosure(selected)
        .asSequence()
        .filter { path -> path in active && path !in selected }
        .toCollection(linkedSetOf())
    return ScriptUnloadPlan(selected, blocking, emptySet())
}
