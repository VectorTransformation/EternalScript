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

    override fun reload(path: String): CompletionStage<ScriptOperationResult> = engine.reload(path)

    override fun check(): CompletionStage<ScriptOperationResult> = engine.check()

    override fun check(path: String): CompletionStage<ScriptOperationResult> = engine.check(path)

    override fun recompile(): CompletionStage<ScriptOperationResult> = engine.recompile()

    override fun enable(path: String): CompletionStage<ScriptOperationResult> = engine.enable(path)

    override fun disable(path: String): CompletionStage<ScriptOperationResult> = engine.disable(path)

    override fun cancel(): CompletionStage<ScriptOperationResult> = engine.cancel()
}
