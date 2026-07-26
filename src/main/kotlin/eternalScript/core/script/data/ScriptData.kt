package eternalScript.core.script.data

import eternalScript.core.script.Script

data class ScriptData(val script: Script) {
    val scriptParser = ScriptParser(script::class)

    fun activate() {
        script.listenerManager.register()
        script.commandManager.register()
        script.functionManager.call(script, ScriptLifecycle.ENABLE)
    }

    fun deactivate() {
        val failures = mutableListOf<Throwable>()
        try {
            script.functionManager.call(script, ScriptLifecycle.DISABLE)
        } catch (exception: Throwable) {
            failures.add(exception)
        }
        cleanup(failures, script.taskManager::clear)
        cleanup(failures, script.listenerManager::unregister)
        cleanup(failures, script.commandManager::unregister)

        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    fun dispose() {
        runCatching(script.taskManager::clear)
        runCatching(script.listenerManager::clear)
        runCatching(script.commandManager::clear)
        runCatching(script.functionManager::clear)
    }

    private fun cleanup(failures: MutableList<Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (exception: Throwable) {
            failures.add(exception)
        }
    }
}
