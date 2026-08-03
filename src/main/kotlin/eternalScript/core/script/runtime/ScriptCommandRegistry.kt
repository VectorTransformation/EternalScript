package eternalScript.core.script.runtime

import eternalScript.core.script.command.ScriptCommand
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.script.data.ScriptExecutionGate
import eternalScript.core.script.data.ScriptRegistrationGate
import eternalScript.core.the.Root
import org.bukkit.command.Command
import java.util.concurrent.ConcurrentHashMap

internal class ScriptCommandRegistry(
    private val executionGate: ScriptExecutionGate,
    private val registrationGate: ScriptRegistrationGate
) {
    companion object {
        private val commandMap by lazy { Root.INSTANCE.server.commandMap }
        private val knownCommands by lazy { commandMap.knownCommands }
        private val prefix = Root.ORIGIN.lowercase()
    }
    private val definitions = ConcurrentHashMap.newKeySet<Command>()
    private val runtimeCommands = ConcurrentHashMap.newKeySet<Command>()

    @Volatile
    private var active = false

    fun addCommand(builder: ScriptCommandBuilder) {
        check(!active || registrationGate.isOpen) {
            "Commands can only be registered at script top level or during the enable lifecycle."
        }
        val commandKeys = commandKeys(builder.name, builder.aliases)
        if (commandKeys.any { commandMap.getCommand(it) !is ScriptCommand }) {
            if (commandKeys.any { commandMap.getCommand(it) != null }) return
            commands().forEach { command ->
                if (commandKeys(command.name, command.aliases).any { commandMap.getCommand(it) != null }) return
            }
        }
        val command = ScriptCommand(builder, executionGate)
        if (active) {
            runtimeCommands.add(command)
            commandMap.register(command.name, prefix, command)
            updateCommands()
        } else {
            definitions.add(command)
        }
    }

    fun commandKeys(name: String, aliases: List<String>) = (listOf(name) + aliases).flatMap { listOf(it, "$prefix:$it") }

    fun register() {
        if (active) return
        if (definitions.isEmpty()) {
            active = true
            return
        }
        definitions.forEach { command ->
            commandMap.register(command.name, prefix, command)
        }
        active = true
        updateCommands()
    }

    fun unregister() {
        val registered = commands()
        registered.forEach { command ->
            runCatching {
                commandKeys(command.name, command.aliases).forEach { key ->
                    if (knownCommands[key] === command) {
                        knownCommands.remove(key)
                    }
                }
                command.unregister(commandMap)
            }
        }
        active = false
        runtimeCommands.clear()
        if (registered.isNotEmpty()) {
            updateCommands()
        }
    }

    fun clear() {
        if (active) {
            unregister()
        } else {
            runtimeCommands.clear()
        }
        definitions.clear()
    }

    private fun commands() = definitions + runtimeCommands

    fun updateCommands() {
        Root.onlinePlayers().forEach { player ->
            player.scheduler.run(
                Root.INSTANCE,
                { player.updateCommands() },
                null
            )
        }
    }
}
