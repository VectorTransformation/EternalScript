package eternalScript.core.script.manager

import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.the.Root
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

class ScriptListenerManager(
    private val executionGate: ScriptExecutionGate,
    private val registrationGate: ScriptRegistrationGate
) : Listener {
    private val definitions = ConcurrentLinkedQueue<() -> Unit>()
    private val runtimeRegistrations = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile
    private var active = false

    fun <T : Event> add(
        event: KClass<T>,
        priority: EventPriority,
        block: (T) -> Unit
    ) {
        check(!active || registrationGate.isOpen) {
            "Events can only be registered at script top level or during the enable lifecycle."
        }
        val registration = {
            Root.register(event, this, priority) { value ->
                executionGate.withActive {
                    block(value)
                }
            }
        }
        if (active) {
            runtimeRegistrations.add(registration)
            registration()
        } else {
            definitions.add(registration)
        }
    }

    fun register() {
        if (active) return
        active = true
        definitions.forEach { it() }
    }

    fun unregister() {
        Root.unregister(this)
        active = false
        runtimeRegistrations.clear()
    }

    fun clear() {
        if (active) {
            unregister()
        } else {
            runtimeRegistrations.clear()
        }
        definitions.clear()
    }
}
