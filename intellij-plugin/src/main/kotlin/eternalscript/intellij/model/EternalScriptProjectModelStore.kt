package eternalscript.intellij.model

import java.util.concurrent.atomic.AtomicReference

/** Atomically publishes the project model consumed by Kotlin analysis. */
internal class EternalScriptProjectModelStore {
    private val state = AtomicReference(EternalScriptProjectSnapshot.EMPTY)

    fun snapshot(): EternalScriptProjectSnapshot = state.get()

    fun compareAndSet(
        expected: EternalScriptProjectSnapshot,
        next: EternalScriptProjectSnapshot
    ): Boolean {
        return state.compareAndSet(expected, next)
    }
}
