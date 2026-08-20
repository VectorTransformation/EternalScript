package eternalscript.bootstrap

import eternalscript.api.EternalScriptApi
import eternalscript.api.ScriptOperationResult
import eternalscript.api.ScriptSnapshot
import eternalscript.scripting.runtime.ScriptEngine
import java.util.concurrent.CompletionStage

internal class EternalScriptApiService(
    private val engine: ScriptEngine
) : EternalScriptApi {
    override fun snapshot(): ScriptSnapshot = engine.snapshot()

    override fun reload(): CompletionStage<ScriptOperationResult> = engine.reload()

    override fun recompile(): CompletionStage<ScriptOperationResult> = engine.recompile()

    override fun load(path: String): CompletionStage<ScriptOperationResult> = engine.load(path)

    override fun unload(path: String): CompletionStage<ScriptOperationResult> = engine.unload(path)

    override fun clear(): CompletionStage<ScriptOperationResult> = engine.clear()
}
