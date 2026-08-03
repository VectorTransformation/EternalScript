package eternalScript.core.script.generation

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class GenerationCoordinatorLifecycle {
    private data class State(
        val epoch: Long,
        val open: Boolean
    )

    private val state = AtomicReference(State(epoch = 0, open = false))

    fun open(): Long =
        state.updateAndGet { current ->
            if (current.open) current else State(current.epoch + 1, true)
        }.epoch

    fun close(): Long =
        state.updateAndGet { current ->
            if (!current.open) current else State(current.epoch + 1, false)
        }.epoch

    fun openEpoch(): Long? =
        state.get().let { current ->
            current.epoch.takeIf { current.open }
        }

    fun accepts(epoch: Long): Boolean =
        state.get().let { current ->
            current.open && current.epoch == epoch
        }
}

internal class ScriptEnvironmentFence {
    private val revision = AtomicLong()

    fun snapshot(): Long = revision.get()

    fun invalidate(): Long = revision.incrementAndGet()

    fun accepts(snapshot: Long): Boolean = revision.get() == snapshot
}

internal class GenerationOwnershipRegistry<T : Any> {
    private val generations = ConcurrentHashMap.newKeySet<T>()

    fun transfer(generation: T): Boolean = generations.add(generation)

    fun claim(generation: T): Boolean = generations.remove(generation)

    fun claimAll(): List<T> = generations.toList().filter(generations::remove)
}
