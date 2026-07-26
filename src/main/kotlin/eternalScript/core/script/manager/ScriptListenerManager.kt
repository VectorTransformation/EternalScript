package eternalScript.core.script.manager

import eternalScript.core.the.Root
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

class ScriptListenerManager : Listener {
    private val definitions = ConcurrentLinkedQueue<() -> Unit>()
    private val runtimeRegistrations = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile
    private var active = false

    fun <T : Event> add(
        event: KClass<T>,
        priority: EventPriority,
        block: (T) -> Unit
    ) {
        val registration = {
            Root.register(event, this, priority, block)
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
        unregister()
        definitions.clear()
    }
}
