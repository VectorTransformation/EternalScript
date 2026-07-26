package eternalScript.core.script

import eternalScript.core.script.data.ScriptLifecycle
import eternalScript.core.manager.ScriptManager
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.script.manager.ScriptCommandManager
import eternalScript.core.script.manager.ScriptFunctionManager
import eternalScript.core.script.manager.ScriptListenerManager
import eternalScript.core.script.manager.ScriptTaskManager
import eternalScript.core.the.Root
import kotlinx.coroutines.Job
import org.bukkit.scheduler.BukkitTask
import org.bukkit.event.Event
import org.bukkit.event.EventPriority

abstract class Script {
    val functionManager = ScriptFunctionManager()
    val commandManager = ScriptCommandManager()
    val listenerManager = ScriptListenerManager()
    val taskManager = ScriptTaskManager()

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
        register(priority, block)
    }

    inline fun <reified T : Event> register(
        priority: EventPriority = EventPriority.NORMAL,
        noinline block: (T) -> Unit
    ) {
        listenerManager.add(T::class, priority, block)
    }

    // command

    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        register(name, block)
    }

    fun register(name: String, block: ScriptCommandBuilder.() -> Unit) {
        commandManager.addCommand(ScriptCommandBuilder(name).apply(block))
    }

    // task

    fun <T : Job> track(job: T) = taskManager.track(job)

    fun <T : BukkitTask> track(task: T) = taskManager.track(task)

    // util

    fun instance() = Root.INSTANCE

    fun scripts() = ScriptManager.scripts()

    fun script(script: String) = ScriptManager.script(script)
}
