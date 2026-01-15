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
    private val cache = ConcurrentHashMap.newKeySet<Command>()

    fun addCommand(builder: ScriptCommandBuilder) {
        val commandKeys = commandKeys(builder.name, builder.aliases)
        if (commandKeys.any { commandMap.getCommand(it) !is ScriptCommand }) {
            if (commandKeys.any { commandMap.getCommand(it) != null }) return
            cache.forEach { command ->
                if (commandKeys(command.name, command.aliases).any { commandMap.getCommand(it) != null }) return
            }
        }
        cache.add(ScriptCommand(builder))
    }

    fun commandKeys(name: String, aliases: List<String>) = (listOf(name) + aliases).flatMap { listOf(it, "$prefix:$it") }

    fun register() {
        cache.forEach { command ->
            commandMap.register(command.name, prefix, command)
        }
        updateCommands()
    }

    fun clear() {
        cache.forEach { command ->
            commandKeys(command.name, command.aliases).forEach(knownCommands::remove)
        }
        cache.clear()
        updateCommands()
    }

    fun updateCommands() {
        Root.onlinePlayers().forEach(Player::updateCommands)
    }
}