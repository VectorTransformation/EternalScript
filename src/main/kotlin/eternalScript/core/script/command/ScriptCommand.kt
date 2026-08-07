package eternalScript.core.script.command

import eternalScript.api.script.InternalEternalScriptRuntimeApi
import eternalScript.api.script.command.ScriptCommandContext
import eternalScript.api.script.command.ScriptCommandDefinition
import eternalScript.api.script.command.ScriptSuggestionContext
import eternalScript.core.script.data.ScriptExecutionGate
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

@OptIn(InternalEternalScriptRuntimeApi::class)
class ScriptCommand(
    val definition: ScriptCommandDefinition,
    private val executionGate: ScriptExecutionGate
) : Command(definition.name) {
    init {
        aliases = definition.aliases
        permission = definition.permission
    }

    override fun tabComplete(
        sender: CommandSender,
        alias: String,
        args: Array<String>
    ) = executionGate.withActive {
        definition.suggest(
            ScriptSuggestionContext(sender, alias, args.toList())
        )
    } ?: emptyList()

    override fun execute(
        sender: CommandSender,
        label: String,
        args: Array<String>
    ) = executionGate.withActive {
        if (testPermissionSilent(sender)) {
            definition.execute(
                ScriptCommandContext(sender, label, args.toList())
            )
            true
        } else {
            false
        }
    } ?: false
}
