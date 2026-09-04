package eternalscript.intellij.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes incremental analysis for one workspace while allowing unrelated workspaces to run.
 * Gate identities are deliberately retained so removing and recreating a deterministic workspace
 * id cannot let workers from the two incarnations overlap.
 */
internal class WorkspaceAnalysisGate {
    private val gates = ConcurrentHashMap<String, Any>()

    fun <T> withWorkspace(workspaceId: String, action: () -> T): T =
        synchronized(gates.computeIfAbsent(workspaceId) { Any() }, action)

    fun <T> withWorkspaces(workspaceIds: Collection<String>, action: () -> T): T {
        val orderedIds = workspaceIds.toSortedSet().toList()

        fun acquire(index: Int): T = if (index == orderedIds.size) {
            action()
        } else {
            withWorkspace(orderedIds[index]) { acquire(index + 1) }
        }

        return acquire(0)
    }
}
