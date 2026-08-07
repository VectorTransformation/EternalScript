package eternalScript.core.script.runtime

import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.the.Root
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import kotlin.reflect.KClass

class ScriptListenerRegistry(
    private val executionGate: ScriptExecutionGate,
    private val registrationGate: ScriptRegistrationGate
) : Listener {
    private val registrations = ScriptRegistrationLifecycle<() -> Unit>()

    internal fun beginActivation() {
        registrations.beginActivation()
    }

    @PublishedApi
    internal fun <T : Event> add(
        event: KClass<T>,
        priority: EventPriority,
        block: (T) -> Unit
    ) {
        val registration = {
            Root.register(event, this, priority) { value ->
                executionGate.withActive {
                    block(value)
                }
            }
        }
        if (
            registrations.add(
                registration,
                registrationGateOpen = registrationGate.isOpen
            ) == ScriptRegistrationLifecycle.Placement.LIVE
        ) {
            registration()
        }
    }

    fun register() {
        registrations.activate().forEach { it() }
    }

    fun unregister() {
        val release = registrations.deactivate()
        if (release.wasActive && release.registrations.isNotEmpty()) {
            Root.unregister(this)
        }
    }

    fun clear() {
        val release = registrations.dispose()
        if (release.wasActive && release.registrations.isNotEmpty()) {
            Root.unregister(this)
        }
    }
}
