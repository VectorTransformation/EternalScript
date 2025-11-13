package eternalScript.core.script.manager

import eternalScript.core.data.ScriptLifecycle
import eternalScript.core.script.Script
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
        if (ScriptLifecycle.ENABLE.check(function)) {
            register(script)
        }
        cache[function]?.forEach { it.invoke(arg) }
        if (ScriptLifecycle.DISABLE.check(function)) {
            clear(script)
        }
    }

    fun register(script: Script) {
        script.commandManager.register()
    }

    fun clear(script: Script) {
        script.listenerManager.clear()
        script.commandManager.clear()
    }

    fun call(script: Script, function: String) = call(script, function, Unit)

    fun call(script: Script, lifecycle: ScriptLifecycle) = call(script, lifecycle.function)
}