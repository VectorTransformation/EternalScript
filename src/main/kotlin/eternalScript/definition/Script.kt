package eternalScript.definition

import eternalScript.data.Lifecycle
import eternalScript.manager.ScriptManager
import eternalScript.the.Root
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import kotlin.script.experimental.annotations.KotlinScript

@KotlinScript(
    compilationConfiguration = ScriptCompilerConfig::class,
    evaluationConfiguration = ScriptEvaluatorConfig::class
)
abstract class Script : Listener {
    val scriptFunction = ScriptFunction()
    val scriptCommand = ScriptCommand()

    fun <T : Any> save(function: String, block: (T) -> Unit) = scriptFunction.save(function, block)

    fun save(function: String, block: () -> Unit) = scriptFunction.save(function, block)

    fun <T : Any> call(script: Script, function: String, arg: T) = scriptFunction.call(script, function, arg)

    fun call(script: Script, function: String) = scriptFunction.call(script, function)

    fun <T : Any> call(function: String, arg: T) = call(this, function, arg)

    fun call(function: String) = call(this, function)

    // lifecycle

    fun enable(block: () -> Unit) = save(Lifecycle.ENABLE.function, block)

    fun disable(block: () -> Unit) = save(Lifecycle.DISABLE.function, block)

    // event

    inline fun <reified T : Event> event(priority: EventPriority = EventPriority.NORMAL, noinline block: (T) -> Unit) {
        val event = T::class.java
        val function = event.name
        val hasEvent = scriptFunction.hasEvent(function)

        save(function, block)

        if (hasEvent) return

        scriptFunction.addEvent(function)

        Root.register(event, this, priority) {
            call(function, it)
        }
    }

    // command

    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        val builder = ScriptCommandBuilder(name).apply(block)
        scriptCommand.register(
            builder.name,
            builder.description,
            builder.aliases,
            builder.usage,
            builder.tabCompleter,
            builder.executor,
            builder.permission
        )
    }

    // util

    fun scripts() = ScriptManager.scripts()

    fun script(script: String) = ScriptManager.script(script)
}