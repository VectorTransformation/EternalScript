package eternalScript.api.script.command

import eternalScript.api.script.EternalScriptDsl
import eternalScript.api.script.InternalEternalScriptRuntimeApi
import org.bukkit.command.CommandSender

/** Values available while a script command is executing. */
@EternalScriptDsl
data class ScriptCommandContext(
    val sender: CommandSender,
    val label: String,
    val arguments: List<String>
)

/** Values available while a script command is producing suggestions. */
@EternalScriptDsl
data class ScriptSuggestionContext(
    val sender: CommandSender,
    val alias: String,
    val arguments: List<String>
)

/** Immutable runtime command definition produced by [ScriptCommandBuilder]. */
@InternalEternalScriptRuntimeApi
class ScriptCommandDefinition(
    val name: String,
    aliases: List<String> = emptyList(),
    val permission: String? = null,
    private val suggestions: ScriptSuggestionContext.() -> List<String> = {
        emptyList()
    },
    private val execution: ScriptCommandContext.() -> Unit = {}
) {
    val aliases: List<String> = aliases.toList()

    fun suggest(context: ScriptSuggestionContext): List<String> =
        suggestions(context)

    fun execute(context: ScriptCommandContext) {
        execution(context)
    }
}
