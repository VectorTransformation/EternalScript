package eternalScript.definition

import org.bukkit.command.CommandSender

class ScriptCommandBuilder(val name: String) {
    var aliases: List<String> = emptyList()
    var permission: String? = null
    var tabCompleter: (sender: CommandSender, alias: String, args: List<String>) -> List<String> = { _, _, _ -> emptyList() }
    var executor: (sender: CommandSender, label: String, args: List<String>) -> Unit = { _, _, _ -> }
}