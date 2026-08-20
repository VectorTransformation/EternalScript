package eternalscript.scripting.runtime

import eternalscript.scripting.runtime.declaration.ScriptEventDefinition
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin

internal class ScriptListenerRegistration(
    private val plugin: JavaPlugin,
    private val definitions: List<ScriptEventDefinition>,
    private val execution: ScriptExecutionHandle
) : Listener {
    @Volatile
    private var active = false

    private var disposed = false

    fun activate() {
        check(!disposed) { "Script listener registration is disposed" }
        if (active) return
        active = true
        try {
            definitions.forEach { definition -> register(definition) }
        } catch (error: Throwable) {
            runCatching(::dispose).exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    fun dispose() {
        if (disposed) return
        active = false
        disposed = true
        if (definitions.isNotEmpty()) HandlerList.unregisterAll(this)
    }

    private fun register(definition: ScriptEventDefinition) {
        plugin.server.pluginManager.registerEvent(
            definition.eventType.java,
            this,
            definition.priority,
            { _, event ->
                if (active && !disposed && definition.eventType.java.isInstance(event)) {
                    execution.tryExecute { definition.handler(event) }
                }
            },
            plugin
        )
    }
}
