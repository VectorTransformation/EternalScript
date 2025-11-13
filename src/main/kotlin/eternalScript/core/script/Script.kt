package eternalScript.core.script

import eternalScript.core.data.ScriptLifecycle
import eternalScript.core.manager.ScriptManager
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.script.manager.ScriptCommandManager
import eternalScript.core.script.manager.ScriptFunctionManager
import eternalScript.core.script.manager.ScriptListenerManager
import eternalScript.core.the.Root
import org.bukkit.event.Event
import org.bukkit.event.EventPriority

abstract class Script {
    val functionManager = ScriptFunctionManager()
    val commandManager = ScriptCommandManager()
    val listenerManager = ScriptListenerManager()

    // lifecycle

    fun enable(block: () -> Unit) {
        functionManager.save(ScriptLifecycle.ENABLE, block)
    }

    fun disable(block: () -> Unit) {
        functionManager.save(ScriptLifecycle.DISABLE, block)
    }

    // event

    inline fun <reified T : Event> event(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        Root.register(T::class.java, listenerManager, priority, block)
    }

    // command

    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        val builder = ScriptCommandBuilder(name).apply(block)
        commandManager.addCommand(
            builder.name,
            builder.aliases,
            builder.permission,
            builder.tabCompleter,
            builder.executor
        )
    }

    // util

    fun instance() = Root.instance()

    fun scripts() = ScriptManager.scripts()

    fun script(script: String) = ScriptManager.script(script)
}