package eternalscript.scripting.runtime

import eternalscript.api.script.Script
import eternalscript.scripting.runtime.declaration.ScriptDeclarationSnapshot
import org.bukkit.plugin.java.JavaPlugin

internal class ActiveScript(
    private val script: Script,
    source: String,
    plugin: JavaPlugin,
    private val commandRegistry: ScriptCommandRegistry,
    executionContext: ScriptExecutionContext
) {
    internal enum class State {
        DECLARED,
        COMMANDS_ACTIVE,
        RESOURCES_ACTIVE,
        LOAD_STARTED,
        ACTIVE,
        DISPOSING,
        DISPOSED
    }

    private val declarations: ScriptDeclarationSnapshot = script
        .also { instance -> instance.attachRuntimeSource(source) }
        .freezeDeclarations()
    private val lifecycle = ScriptLifecycleHooks(
        declarations.loadCallbacks,
        declarations.unloadCallbacks
    )
    private val execution = ScriptExecutionHandle(executionContext)
    private val listeners = ScriptListenerRegistration(plugin, declarations.events, execution)
    private var commands: ScriptCommandRegistration? = null
    var state: State = State.DECLARED
        private set

    fun activateCommands() {
        check(state == State.DECLARED) { "Script commands cannot be activated from state $state" }
        commands = commandRegistry.activate(declarations.commands, execution)
        state = State.COMMANDS_ACTIVE
    }

    fun activateListeners() {
        check(state == State.COMMANDS_ACTIVE) { "Script listeners cannot be activated from state $state" }
        listeners.activate()
        state = State.RESOURCES_ACTIVE
    }

    fun invokeLoad() {
        check(state == State.RESOURCES_ACTIVE) { "Script onLoad cannot run from state $state" }
        state = State.LOAD_STARTED
        execution.execute(lifecycle::invokeLoad)
        state = State.ACTIVE
    }

    fun dispose() {
        if (state == State.DISPOSED || state == State.DISPOSING) return

        val invokeUnload = state == State.LOAD_STARTED || state == State.ACTIVE
        state = State.DISPOSING
        val failures = mutableListOf<Throwable>()
        if (invokeUnload) {
            cleanup(failures) { execution.execute(lifecycle::invokeUnload) }
        }
        cleanup(failures, listeners::dispose)
        cleanup(failures) { commands?.dispose() }
        commands = null
        cleanup(failures) {
            execution.executeOrNull(script::disposeDeclarations) ?: script.disposeDeclarations()
        }
        state = State.DISPOSED

        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }

    fun updateExecutionContext(context: ScriptExecutionContext) {
        check(state != State.DISPOSED && state != State.DISPOSING) {
            "A disposed script cannot move to another generation"
        }
        execution.update(context)
    }

    private fun cleanup(failures: MutableList<Throwable>, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            failures += error
        }
    }
}
