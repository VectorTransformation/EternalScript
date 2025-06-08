package eternalScript.definition

import eternalScript.data.Lifecycle
import eternalScript.the.Root
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class ScriptFunction() {
    private val cache = ConcurrentHashMap<String, ConcurrentLinkedQueue<(Any) -> Unit>>()
    private val eventCache = ConcurrentHashMap.newKeySet<String>()

    fun <T : Any> save(function: String, block: (T) -> Unit) {
        val queue = cache.getOrPut(function) {
            ConcurrentLinkedQueue()
        }
        queue.add { block(it as T) }
    }

    fun save(function: String, block: () -> Unit) = save<Unit>(function) { block() }

    fun <T : Any> call(script: Script, function: String, arg: T) {
        cache[function]?.forEach { it.invoke(arg) }
        if (Lifecycle.DISABLE.check(function)) {
            clear(script)
        }
    }

    fun clear(script: Script) {
        Root.unregisters(script)
        script.scriptCommand.clear()
    }

    fun call(script: Script, function: String) = call(script, function, Unit)

    fun addEvent(event: String) = eventCache.add(event)

    fun hasEvent(event: String) = eventCache.contains(event)
}