package eternalScript.core.script.command

import org.bukkit.command.CommandSender

class ScriptCommandBuilder(val name: String) {
    var aliases: List<String> = emptyList()
    var permission: String? = null
    var tabCompleter: (sender: CommandSender, alias: String, args: List<String>) -> List<String> = { _, _, _ -> emptyList() }
    var executor: (sender: CommandSender, label: String, args: List<String>) -> Unit = { _, _, _ -> }

    fun aliases(vararg alias: String) {
        aliases = alias.toList()
    }

    fun permission(permission: String?) {
        this.permission = permission
    }

    fun tabCompleter(block: (sender: CommandSender, alias: String, args: List<String>) -> List<String>) {
        tabCompleter = block
    }

    fun executor(block: (sender: CommandSender, alias: String, args: List<String>) -> Unit) {
        executor = block
    }
}