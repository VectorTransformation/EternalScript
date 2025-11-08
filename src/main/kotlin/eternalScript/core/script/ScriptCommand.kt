package eternalScript.core.script

import eternalScript.core.the.Root
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class ScriptCommand() {
    companion object {
        private val commandMap = Root.instance().server.commandMap
        private val knownCommands = commandMap.knownCommands
        private val prefix = Root.ORIGIN.lowercase()
    }
    private val cache = ConcurrentHashMap.newKeySet<Command>()

    fun addCommand(
        name: String,
        aliases: List<String>,
        permission: String?,
        tabCompleter: (sender: CommandSender, alias: String, args: List<String>) -> List<String>,
        executor: (sender: CommandSender, label: String, args: List<String>) -> Unit
    ) {
        val commandKeys = commandKeys(name, aliases)
        if (commandKeys.any { commandMap.getCommand(it) !is CustomScriptCommand }) {
            if (commandKeys.any { commandMap.getCommand(it) != null }) return
            cache.forEach { command ->
                if (commandKeys(command.name, command.aliases).any { commandMap.getCommand(it) != null }) return
            }
        }
        val command = object : CustomScriptCommand(name) {
            init {
                setAliases(aliases)
                setPermission(permission)
            }

            override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): List<String> {
                return tabCompleter(sender, alias, args.toList())
            }
            override fun execute(sender: CommandSender, label: String, args: Array<String>): Boolean {
                if (!testPermissionSilent(sender)) return true
                executor(sender, label, args.toList())
                return true
            }
        }
        cache.add(command)
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