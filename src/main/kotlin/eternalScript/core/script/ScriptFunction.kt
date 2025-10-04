package eternalScript.core.script

import eternalScript.core.data.ScriptLifecycle
import eternalScript.core.the.Root
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class ScriptFunction() {
    val cache = ConcurrentHashMap<String, ConcurrentLinkedQueue<(Any) -> Unit>>()
    private val eventCache = ConcurrentHashMap.newKeySet<String>()

    inline fun <reified T : Any> save(function: String, noinline block: T.() -> Unit) {
        val queue = cache.getOrPut(function) {
            ConcurrentLinkedQueue()
        }
        queue.add { (it as T).block() }
    }

    fun save(function: String, block: () -> Unit) = save<Unit>(function) { block() }

    fun <T : Any> call(script: Script, function: String, arg: T) {
        cache[function]?.forEach { it.invoke(arg) }
        if (ScriptLifecycle.DISABLE.check(function)) {
            clear(script)
        }
    }

    fun clear(script: Script) {
        Root.unregister(script)
        script.scriptCommand.clear()
    }

    fun call(script: Script, function: String) = call(script, function, Unit)

    fun addEvent(event: String) = eventCache.add(event)

    fun hasEvent(event: String) = eventCache.contains(event)
}