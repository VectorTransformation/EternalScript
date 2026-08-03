package eternalScript.core.script.generation

import eternalScript.api.script.Script
import eternalScript.core.script.data.ScriptLifecycle

/**
 * Runtime state owned by one Script instance inside a project generation.
 *
 * The lifecycle marker stays beside its Script so activation and reverse
 * deactivation cannot drift into parallel-list bookkeeping.
 */
internal class ScriptInstanceRuntime(
    val script: Script
) {
    private val lifecycle = ScriptInstanceLifecycleState()

    fun activate() {
        script.executionGate.withContext {
            lifecycle.activate {
                // Mark the lifecycle active before invoking user code so a
                // failed enable still owns exactly one reverse-order cleanup.
                script.taskScope.open()
                script.registrationGate.withOpen {
                    script.lifecycleRegistry.call(script, ScriptLifecycle.ENABLE)
                }
                script.listenerRegistry.register()
                script.commandRegistry.register()
            }
        }
    }

    fun deactivate(failures: MutableList<Throwable>) {
        script.executionGate.withContext {
            lifecycle.deactivate {
                script.taskScope.close()
                cleanup(failures) {
                    script.lifecycleRegistry.call(script, ScriptLifecycle.DISABLE)
                }
                cleanup(failures, script.taskScope::clear)
                cleanup(failures, script.listenerRegistry::unregister)
                cleanup(failures, script.commandRegistry::unregister)
            }
        }
    }

    fun dispose(failures: MutableList<Throwable>) {
        script.executionGate.withContext {
            cleanup(failures, script::disposeRuntime)
        }
    }
}

internal fun cleanup(
    failures: MutableList<Throwable>,
    block: () -> Unit
) {
    try {
        block()
    } catch (exception: Throwable) {
        failures.add(exception)
    }
}
