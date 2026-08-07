package eternalScript.core.script.generation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Single owner of active, pending, invalidation, and lifecycle epoch state. */
internal class GenerationStateStore {
    internal val active = AtomicReference<ManagedProjectGeneration?>()
    internal val activeView = ActiveGenerationView(active::get)
    internal val lifecycle = GenerationCoordinatorLifecycle()
    internal val pendingCandidates = GenerationOwnershipRegistry<ManagedProjectGeneration>()
    internal val pendingRetirements = GenerationOwnershipRegistry<ManagedProjectGeneration>()
    internal val invalidatedGenerations =
        ConcurrentHashMap.newKeySet<ManagedProjectGeneration>()
    internal val environmentFence = ScriptEnvironmentFence()

    fun open() = lifecycle.open()

    fun close() = lifecycle.close()

    fun invalidateEnvironment() = environmentFence.invalidate()

    fun openEpoch(): Long? = lifecycle.openEpoch()

    fun accepts(epoch: Long, environment: Long): Boolean =
        lifecycle.accepts(epoch) && environmentFence.accepts(environment)

    fun environmentEpoch(): Long = environmentFence.snapshot()
}
