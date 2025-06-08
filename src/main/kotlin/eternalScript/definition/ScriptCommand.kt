package eternalScript.definition

import eternalScript.the.Root
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

class ScriptCommand() {
    companion object {
        private val knownCommands = Root.instance().server.commandMap.knownCommands
        private val lock = Any()
    }
    private val cache = ConcurrentHashMap<String, Command>()

    fun register(
        name: String,
        aliases: List<String>,
        permission: String?,
        tabCompleter: (sender: CommandSender, alias: String, args: List<String>) -> List<String>,
        executor: (sender: CommandSender, label: String, args: List<String>) -> Unit
    ) {
        val command = object : Command(name) {
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

        register(command)
    }

    fun addCommand(name: String, command: Command) {
        synchronized(lock) {
            if (knownCommands.containsKey(name)) return
            knownCommands[name] = command
            cache[name] = command
        }
    }

    fun removeCommand(name: String, command: Command) {
        synchronized(lock) {
            if (!knownCommands.containsKey(name)) return
            knownCommands.remove(name, command)
            cache.remove(name, command)
        }
    }

    fun register(command: Command) {
        addCommand(command.name, command)
        command.aliases.forEach { name ->
            addCommand(name, command)
        }
        if (cache.isEmpty()) return
        updateCommands()
    }

    fun clear() {
        cache.forEach { (name, command) ->
            removeCommand(name, command)
        }
        if (cache.isNotEmpty()) return
        updateCommands()
    }

    fun updateCommands() {
        Root.runTask {
            Root.onlinePlayers().forEach(Player::updateCommands)
        }
    }
}