package eternalScript.definition

import org.bukkit.command.CommandSender

class ScriptCommandBuilder(val name: String) {
    var description: String = ""
    var aliases: List<String> = emptyList()
    var usage: String = ""
    var tabCompleter: (sender: CommandSender, label: String, args: List<String>) -> List<String> = { _, _, _ -> emptyList() }
    var executor: (sender: CommandSender, label: String, args: List<String>) -> Boolean = { _, _, _ -> false }
    var permission: String? = null
}