package eternalscript.scripting.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Binds a callback to the state table and classloader lifetime of the generation that
 * accepted it. Retiring a generation rejects new entries and defers loader disposal
 * until every already-running asynchronous callback has left.
 */
internal class ScriptExecutionContext(
    val state: ReplStateBridge.StateTable,
    private val dispose: () -> Unit
) {
    private val accepting = AtomicBoolean(true)
    private val executions = AtomicInteger()
    private val disposed = AtomicBoolean()

    fun <T> executeOrNull(block: () -> T): T? {
        while (accepting.get()) {
            executions.incrementAndGet()
            if (accepting.get()) {
                return try {
                    ReplStateBridge.bind(state, block)
                } finally {
                    if (executions.decrementAndGet() == 0 && !accepting.get()) disposeOnce()
                }
            }
            if (executions.decrementAndGet() == 0 && !accepting.get()) disposeOnce()
        }
        return null
    }

    fun retire() {
        if (!accepting.compareAndSet(true, false)) return
        if (executions.get() == 0) disposeOnce()
    }

    private fun disposeOnce() {
        if (disposed.compareAndSet(false, true)) dispose()
    }
}

internal class ScriptExecutionHandle(initial: ScriptExecutionContext) {
    private val current = AtomicReference(initial)

    fun update(context: ScriptExecutionContext) {
        current.set(context)
    }

    fun tryExecute(block: () -> Unit): Boolean =
        current.get().executeOrNull {
            block()
            true
        } == true

    fun <T> executeOrNull(block: () -> T): T? = current.get().executeOrNull(block)

    fun <T> execute(block: () -> T): T =
        current.get().executeOrNull(block)
            ?: error("The script generation is no longer accepting executions")
}
