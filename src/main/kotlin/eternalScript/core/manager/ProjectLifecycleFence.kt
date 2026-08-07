package eternalScript.core.manager

import java.util.concurrent.atomic.AtomicReference

/** Rejects work that belongs to an expired project-controller lifecycle. */
internal class ProjectLifecycleFence {
    private data class State(
        val session: Long,
        val open: Boolean
    )

    private val state = AtomicReference(State(session = 0, open = false))

    fun open(): Long =
        state.updateAndGet { current ->
            if (current.open) current else State(current.session + 1, true)
        }.session

    fun close(): Long =
        state.updateAndGet { current ->
            if (!current.open) current else State(current.session + 1, false)
        }.session

    fun openSession(): Long? =
        state.get().let { current ->
            current.session.takeIf { current.open }
        }

    fun accepts(session: Long): Boolean =
        state.get().let { current ->
            current.open && current.session == session
        }
}
