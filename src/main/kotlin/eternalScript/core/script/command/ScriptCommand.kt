package eternalScript.core.script.command

import org.bukkit.command.Command
import org.bukkit.command.CommandSender

class ScriptCommand(val builder: ScriptCommandBuilder) : Command(builder.name) {
    init {
        aliases = builder.aliases
        permission = builder.permission
    }

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<String>
    ) = builder.tabCompleter(sender, alias, args.toList())

    override fun execute(
        sender: CommandSender,
        label: String,
        args: Array<String>
    ) = if (testPermissionSilent(sender)) {
        builder.executor(sender, label, args.toList())
        true
    } else false
}