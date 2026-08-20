package eternalscript.scripting.runtime.declaration

import org.bukkit.command.CommandSender
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import kotlin.reflect.KClass

internal data class ScriptEventDefinition(
    val eventType: KClass<out Event>,
    val priority: EventPriority,
    val handler: (Event) -> Unit
)

internal data class ScriptCommandDefinition(
    val name: String,
    val aliases: List<String>,
    val permission: String?,
    val tabCompleter: (CommandSender, String, List<String>) -> List<String>,
    val executor: (CommandSender, String, List<String>) -> Unit
)

internal data class ScriptDeclarationSnapshot(
    val loadCallbacks: List<() -> Unit>,
    val unloadCallbacks: List<() -> Unit>,
    val events: List<ScriptEventDefinition>,
    val commands: List<ScriptCommandDefinition>
)

internal class ScriptDeclarations {
    private val loadCallbacks = mutableListOf<() -> Unit>()
    private val unloadCallbacks = mutableListOf<() -> Unit>()
    private val disposal = ScriptResourceDisposal()
    private val events = mutableListOf<ScriptEventDefinition>()
    private val commands = mutableListOf<ScriptCommandDefinition>()
    private var frozen = false

    fun addLoad(block: () -> Unit) {
        checkMutable()
        loadCallbacks += block
    }

    fun addUnload(block: () -> Unit) {
        checkMutable()
        unloadCallbacks += block
    }

    fun addDispose(block: () -> Unit) {
        disposal.add(block)
    }

    fun addEvent(definition: ScriptEventDefinition) {
        checkMutable()
        events += definition
    }

    fun addCommand(definition: ScriptCommandDefinition) {
        checkMutable()
        commands += definition
    }

    fun freeze(): ScriptDeclarationSnapshot {
        frozen = true
        return ScriptDeclarationSnapshot(
            loadCallbacks.toList(),
            unloadCallbacks.toList(),
            events.toList(),
            commands.toList()
        )
    }

    fun dispose() {
        frozen = true
        disposal.dispose()
    }

    private fun checkMutable() {
        check(!frozen) { "Script declarations are frozen after evaluation" }
    }
}

private class ScriptResourceDisposal {
    private enum class State {
        OPEN,
        DISPOSING,
        DISPOSED
    }

    private val lock = Any()
    private val callbacks = mutableListOf<() -> Unit>()
    private var state = State.OPEN

    fun add(block: () -> Unit) {
        synchronized(lock) {
            check(state == State.OPEN) { "Script resources are already being disposed" }
            callbacks += block
        }
    }

    fun dispose() {
        val pending = synchronized(lock) {
            if (state != State.OPEN) return
            state = State.DISPOSING
            callbacks.asReversed().toList().also { callbacks.clear() }
        }
        val failures = mutableListOf<Throwable>()
        pending.forEach { callback ->
            try {
                callback()
            } catch (error: Throwable) {
                failures += error
            }
        }
        synchronized(lock) {
            state = State.DISPOSED
        }
        failures.firstOrNull()?.let { failure ->
            failures.drop(1).forEach(failure::addSuppressed)
            throw failure
        }
    }
}
