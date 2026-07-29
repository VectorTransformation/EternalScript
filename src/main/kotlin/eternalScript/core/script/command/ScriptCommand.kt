package eternalScript.core.script.command

import eternalScript.core.script.data.ScriptExecutionGate
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

class ScriptCommand(
    val builder: ScriptCommandBuilder,
    private val executionGate: ScriptExecutionGate
) : Command(builder.name) {
    init {
        aliases = builder.aliases
        permission = builder.permission
    }

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<String>
    ) = executionGate.withActive {
        builder.tabCompleter(sender, alias, args.toList())
    } ?: emptyList()

    override fun execute(
        sender: CommandSender,
        label: String,
        args: Array<String>
    ) = executionGate.withActive {
        if (testPermissionSilent(sender)) {
            builder.executor(sender, label, args.toList())
            true
        } else {
            false
        }
    } ?: false
}
