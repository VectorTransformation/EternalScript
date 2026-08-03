package eternalScript.core.script.generation

import java.util.concurrent.atomic.AtomicBoolean

internal class GenerationDisposalState {
    private val disposed = AtomicBoolean()

    fun dispose(block: () -> Unit) {
        if (disposed.compareAndSet(false, true)) {
            block()
        }
    }
}
