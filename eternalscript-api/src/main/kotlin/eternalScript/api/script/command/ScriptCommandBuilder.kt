package eternalScript.api.script.command

import eternalScript.api.script.EternalScriptDsl
import eternalScript.api.script.InternalEternalScriptRuntimeApi

/** Builds one immutable command definition inside [ScriptCommands]. */
@EternalScriptDsl
@OptIn(InternalEternalScriptRuntimeApi::class)
class ScriptCommandBuilder internal constructor(val name: String) {
    private var aliases: List<String> = emptyList()
    private var permission: String? = null
    private var suggestions: ScriptSuggestionContext.() -> List<String> = {
        emptyList()
    }
    private var execution: ScriptCommandContext.() -> Unit = {}

    fun aliases(vararg alias: String) {
        aliases = alias.toList()
    }

    fun permission(permission: String?) {
        this.permission = permission
    }

    fun suggests(block: ScriptSuggestionContext.() -> List<String>) {
        suggestions = block
    }

    fun executes(block: ScriptCommandContext.() -> Unit) {
        execution = block
    }

    internal fun build() = ScriptCommandDefinition(
        name = name,
        aliases = aliases,
        permission = permission,
        suggestions = suggestions,
        execution = execution
    )
}
