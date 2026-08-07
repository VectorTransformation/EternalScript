package eternalScript.core.script.runtime

import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.runtime.ServerAccess
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import kotlin.reflect.KClass

internal class ScriptListenerRegistry(
    private val executionGate: ScriptExecutionGate,
    private val registrationGate: ScriptRegistrationGate,
    private val server: ServerAccess? = null
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
            checkNotNull(server) { "Server access is required to register script events." }
                .registerEvent(event, this, priority) { value ->
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
            checkNotNull(server).unregister(this)
        }
    }

    fun clear() {
        val release = registrations.dispose()
        if (release.wasActive && release.registrations.isNotEmpty()) {
            checkNotNull(server).unregister(this)
        }
    }
}
