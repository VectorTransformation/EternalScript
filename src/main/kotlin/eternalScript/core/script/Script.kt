package eternalScript.core.script

import eternalScript.core.data.ScriptLifecycle
import eternalScript.core.manager.ScriptManager
import eternalScript.core.the.Root
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

abstract class Script {
    val scriptFunction = ScriptFunction()
    val scriptCommand = ScriptCommand()
    val scriptListener = object : Listener {

    }

    // lifecycle

    fun enable(block: () -> Unit) {
        scriptFunction.save(ScriptLifecycle.ENABLE, block)
    }

    fun disable(block: () -> Unit) {
        scriptFunction.save(ScriptLifecycle.DISABLE, block)
    }

    // event

    inline fun <reified T : Event> event(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        Root.register(T::class.java, scriptListener, priority, block)
    }

    // command

    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        val builder = ScriptCommandBuilder(name).apply(block)
        scriptCommand.addCommand(
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