package eternalscript.scripting.runtime

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * Stable state object referenced by generated K2 REPL classes. Candidate evaluation uses a
 * thread-confined staging table; published callbacks use the active immutable table reference.
 */
internal object ReplStateBridge : HashMap<String, Any?>() {
    private val active = AtomicReference<StateTable>(StateTable())
    private val staging = ThreadLocal<StateTable?>()
    private val execution = ThreadLocal<StateTable?>()
    private val evaluating = ThreadLocal<String?>()

    override fun get(key: String): Any? = requireReady(key)

    override fun put(key: String, value: Any?): Any? = table().instances.put(key, value)

    override fun clear() {
        table().instances.clear()
        table().ready.clear()
    }

    fun <T> stage(base: StateTable = active.get().copy(), block: (StateTable) -> T): T {
        check(staging.get() == null) { "A script state staging transaction is already active" }
        staging.set(base)
        return try {
            block(base)
        } finally {
            staging.remove()
        }
    }

    fun publish(table: StateTable): StateTable = active.getAndSet(table)

    fun <T> bind(table: StateTable, block: () -> T): T {
        val previous = execution.get()
        execution.set(table)
        return try {
            block()
        } finally {
            if (previous == null) execution.remove() else execution.set(previous)
        }
    }

    fun contextElement(table: StateTable): ThreadContextElement<StateTable?> =
        execution.asContextElement(table)

    fun snapshot(): StateTable = active.get().copy()

    fun markReady(key: String) {
        table().ready += key
    }

    fun beginEvaluation(key: String) {
        check(evaluating.get() == null) { "A script is already being evaluated: ${evaluating.get()}" }
        evaluating.set(key)
    }

    fun endEvaluation(key: String) {
        check(evaluating.get() == key) { "The active script evaluation does not match: $key" }
        evaluating.remove()
    }

    fun requireReady(key: String): Any? {
        val current = table()
        check(key in current.ready || evaluating.get() == key) {
            "Script instance has not finished top-level evaluation: $key"
        }
        return current.instances[key]
            ?: error("Script instance is unavailable: $key")
    }

    private fun table(): StateTable = execution.get() ?: staging.get() ?: active.get()

    internal class StateTable(
        val instances: MutableMap<String, Any?> = linkedMapOf(),
        val ready: MutableSet<String> = linkedSetOf()
    ) {
        fun copy(): StateTable = StateTable(instances.toMutableMap(), ready.toMutableSet())
    }
}
