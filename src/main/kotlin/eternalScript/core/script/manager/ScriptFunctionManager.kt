package eternalScript.core.script.manager

import eternalScript.core.script.Script
import eternalScript.core.script.data.ScriptLifecycle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class ScriptFunctionManager() {
    val cache = ConcurrentHashMap<String, ConcurrentLinkedQueue<(Any) -> Unit>>()

    inline fun <reified T : Any> save(function: String, noinline block: T.() -> Unit) {
        val queue = cache.getOrPut(function) {
            ConcurrentLinkedQueue()
        }
        queue.add { (it as T).block() }
    }

    fun save(function: String, block: () -> Unit) = save<Unit>(function) { block() }

    fun save(lifecycle: ScriptLifecycle, block: () -> Unit) = save(lifecycle.function, block)

    fun <T : Any> call(script: Script, function: String, arg: T) {
        cache[function]?.forEach { it.invoke(arg) }
    }

    fun call(script: Script, function: String) = call(script, function, Unit)

    fun call(script: Script, lifecycle: ScriptLifecycle) = call(script, lifecycle.function)

    fun clear() {
        cache.clear()
    }
}
