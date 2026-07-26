package eternalScript.core.script.manager

import eternalScript.core.script.command.ScriptCommand
import eternalScript.core.script.command.ScriptCommandBuilder
import eternalScript.core.the.Root
import org.bukkit.command.Command
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class ScriptCommandManager {
    companion object {
        private val commandMap = Root.INSTANCE.server.commandMap
        private val knownCommands = commandMap.knownCommands
        private val prefix = Root.ORIGIN.lowercase()
    }
    private val definitions = ConcurrentHashMap.newKeySet<Command>()
    private val runtimeCommands = ConcurrentHashMap.newKeySet<Command>()

    @Volatile
    private var active = false

    fun addCommand(builder: ScriptCommandBuilder) {
        val commandKeys = commandKeys(builder.name, builder.aliases)
        if (commandKeys.any { commandMap.getCommand(it) !is ScriptCommand }) {
            if (commandKeys.any { commandMap.getCommand(it) != null }) return
            commands().forEach { command ->
                if (commandKeys(command.name, command.aliases).any { commandMap.getCommand(it) != null }) return
            }
        }
        val command = ScriptCommand(builder)
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
        definitions.forEach { command ->
            commandMap.register(command.name, prefix, command)
        }
        active = true
        updateCommands()
    }

    fun unregister() {
        commands().forEach { command ->
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
        updateCommands()
    }

    fun clear() {
        unregister()
        definitions.clear()
    }

    private fun commands() = definitions + runtimeCommands

    fun updateCommands() {
        Root.onlinePlayers().forEach(Player::updateCommands)
    }
}
