package eternalScript.api.script.command

import eternalScript.api.script.EternalScript
import eternalScript.api.script.EternalScriptDsl
import eternalScript.api.script.InternalEternalScriptRuntimeApi

/** Command definitions for one [EternalScript] activation. */
@EternalScriptDsl
@OptIn(InternalEternalScriptRuntimeApi::class)
class ScriptCommands internal constructor(
    private val script: EternalScript
) {
    fun command(name: String, block: ScriptCommandBuilder.() -> Unit) {
        val definition = ScriptCommandBuilder(name).apply(block).build()
        script.runtimeForDsl().command(definition)
    }
}
