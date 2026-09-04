package eternalscript.scripting.runtime

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.asContextElement

/**
 * Binds a callback to the state table and classloader lifetime of the generation that
 * accepted it. Retiring a generation rejects new entries and defers loader disposal
 * until every already-running asynchronous callback has left.
 */
internal class ScriptExecutionContext(
    val state: ReplStateBridge.StateTable,
    private val reportDisposalFailure: (Throwable) -> Unit = {},
    private val dispose: () -> Unit
) {
    private val accepting = AtomicBoolean(true)
    private val executions = AtomicInteger()
    private val disposed = AtomicBoolean()

    fun <T> executeOrNull(block: () -> T): T? {
        val lease = tryAcquire() ?: return null
        return lease.use { acquired -> acquired.execute(block) }
    }

    fun tryAcquire(): ScriptExecutionLease? {
        while (accepting.get()) {
            executions.incrementAndGet()
            if (accepting.get()) {
                return ScriptExecutionLease(this)
            }
            release()
        }
        return null
    }

    fun retire() {
        if (!accepting.compareAndSet(true, false)) return
        if (executions.get() == 0) disposeOnce()
    }

    private fun disposeOnce() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching(dispose).exceptionOrNull()?.let { error ->
            runCatching { reportDisposalFailure(error) }
        }
    }

    private fun release() {
        if (executions.decrementAndGet() == 0 && !accepting.get()) disposeOnce()
    }

    internal class ScriptExecutionLease(
        private val owner: ScriptExecutionContext
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        fun <T> execute(block: () -> T): T = bindCurrent(owner) {
            ReplStateBridge.bind(owner.state, block)
        }

        fun coroutineContext(): CoroutineContext =
            current.asContextElement(owner) + ReplStateBridge.contextElement(owner.state)

        override fun close() {
            if (closed.compareAndSet(false, true)) owner.release()
        }
    }

    companion object {
        private val current = ThreadLocal<ScriptExecutionContext?>()

        fun acquireCurrent(): ScriptExecutionLease = current.get()?.tryAcquire()
            ?: error("storageTask may only start from an active script callback")

        private fun <T> bindCurrent(context: ScriptExecutionContext, block: () -> T): T {
            val previous = current.get()
            current.set(context)
            return try {
                block()
            } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }
    }
}

/** Collects cleanup failures without preventing the remaining cleanup attempts. */
internal class CleanupFailureCollector {
    private var first: Throwable? = null

    fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            add(error)
        }
    }

    fun add(error: Throwable) {
        val current = first
        if (current == null) {
            first = error
        } else if (current !== error) {
            current.addSuppressed(error)
        }
    }

    fun suppressInto(error: Throwable) {
        first?.let { cleanupFailure ->
            if (cleanupFailure !== error) error.addSuppressed(cleanupFailure)
        }
    }

    fun throwIfAny() {
        first?.let { throw it }
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
