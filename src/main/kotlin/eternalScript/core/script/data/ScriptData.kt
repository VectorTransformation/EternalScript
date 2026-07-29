package eternalScript.core.script.data

import eternalScript.core.script.Script
import eternalScript.core.script.project.ScriptProjectSource
import eternalScript.core.script.project.remapRuntimeStackTrace
import java.util.concurrent.atomic.AtomicBoolean

data class ScriptData(val script: Script) {
    val scriptParser = ScriptParser(script::class)
    private val lifecycle = ScriptLifecycleState()
    private val disposal = ScriptDisposalState()
    val executionGate: ScriptExecutionGate
        get() = script.executionGate

    val isActive: Boolean
        get() = executionGate.isActive

    val isDrained: Boolean
        get() = executionGate.isDrained

    internal fun mapRuntimeExceptions(project: ScriptProjectSource) {
        executionGate.mapExceptions(project::remapRuntimeStackTrace)
    }

    internal fun tryFreeze(): Boolean {
        if (!executionGate.tryFreeze()) return false
        script.taskManager.close()
        return true
    }

    internal fun publish(): Boolean =
        executionGate.publish().also { published ->
            if (!published) script.taskManager.close()
        }

    internal fun restore(): Boolean {
        script.taskManager.open()
        return executionGate.restore().also { restored ->
            if (!restored) script.taskManager.close()
        }
    }

    internal fun retire(): Boolean {
        val retired = executionGate.retire()
        script.taskManager.close()
        return retired
    }

    internal fun <T> withActive(block: (Script) -> T): T? =
        executionGate.withActive {
            block(script)
        }

    internal fun functions(): List<String> =
        (
            scriptParser.functionCache
                .filterValues { function -> function.parameters.size == 1 }
                .keys +
                script.projectFunctionNames()
            )
            .distinct()
            .sorted()

    internal fun call(function: String, vararg args: Any?): Any? {
        val projectInvocation = script.callProjectFunction(function, *args)
        return if (projectInvocation == null) {
            scriptParser.call(script, function, *args)
        } else {
            projectInvocation.value
        }
    }

    internal suspend fun cancelTrackedWorkAndJoin(timeoutMillis: Long) =
        script.taskManager.cancelTrackedWorkAndJoin(timeoutMillis)

    internal fun activate() = lifecycle.activate {
        try {
            script.taskManager.open()
            script.listenerManager.register()
            script.commandManager.register()
            script.registrationGate.withOpen {
                script.functionManager.call(script, ScriptLifecycle.ENABLE)
            }
        } catch (exception: Throwable) {
            // Keep the active marker set so failed-activation cleanup owns
            // exactly one matching deactivate transition.
            throw exception
        }
    }

    internal fun deactivate() = lifecycle.deactivate {
        script.taskManager.close()
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

    internal fun dispose() {
        disposal.dispose {
            val failures = mutableListOf<Throwable>()
            cleanup(failures, scriptParser::clear)
            cleanup(failures, script::disposeRuntime)
            failures.firstOrNull()?.let { failure ->
                failures.drop(1).forEach(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun cleanup(failures: MutableList<Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (exception: Throwable) {
            failures.add(exception)
        }
    }
}

internal class ScriptLifecycleState {
    private val monitor = Any()
    private var active = false

    fun activate(block: () -> Unit) {
        synchronized(monitor) {
            if (active) return
            active = true
            block()
        }
    }

    fun deactivate(block: () -> Unit) {
        synchronized(monitor) {
            if (!active) return
            active = false
            block()
        }
    }
}

internal class ScriptDisposalState {
    private val disposed = AtomicBoolean()

    fun dispose(block: () -> Unit) {
        if (disposed.compareAndSet(false, true)) {
            block()
        }
    }
}
